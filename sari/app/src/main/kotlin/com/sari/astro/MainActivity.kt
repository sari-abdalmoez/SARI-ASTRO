package com.sari.astro

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sari.astro.camera.CameraCaps
import com.sari.astro.camera.CameraProbe
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var isoText: TextView
    private lateinit var exposureText: TextView
    private lateinit var rawText: TextView
    private lateinit var focusButton: Button
    private lateinit var captureButton: Button
    private lateinit var sequenceButton: Button

    private lateinit var cameraManager: CameraManager
    private val cameraThread = HandlerThread("SARI-Astro-Camera")
    private lateinit var cameraHandler: Handler
    private var writer: ExecutorService? = null

    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var cameraChars: CameraCharacteristics? = null
    private var opening = false
    private var started = false

    // Image/result pairing by SENSOR_TIMESTAMP (either may arrive first). Guarded by pendingLock.
    private val pendingLock = Any()
    private val pendingImages = LinkedHashMap<Long, Image>()
    private val pendingResults = LinkedHashMap<Long, TotalCaptureResult>()

    // At most one frame in flight (capture -> RAW image -> DNG written). Bounds RAM use.
    private val outstanding = AtomicInteger(0)
    @Volatile private var lastCaptureAtMs = 0L
    private val savedFrames = AtomicInteger(0)
    private val failedFrames = AtomicInteger(0)

    private var iso = 800
    private var exposureNs = 4_000_000_000L
    private var manualFocus = true
    @Volatile private var sequenceRunning = false
    private var sequenceStarted = 0L

    private fun intervalMs() = max(3_000L, exposureNs / 1_000_000L + 750L)

    private val sequenceTick = object : Runnable {
        override fun run() {
            if (!sequenceRunning) return
            captureFrame(manual = false)
            cameraHandler.postDelayed(this, intervalMs())
            ui { updateSequenceUi() }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
            if (started && preview.isAvailable) openBackCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for SARI Astro.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        cameraManager = getSystemService(CameraManager::class.java)
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (started && hasCameraPermission()) openBackCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = applyPreviewTransform()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!hasCameraPermission()) {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= 28) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissionLauncher.launch(perms.toTypedArray())
        } else if (preview.isAvailable) {
            openBackCamera()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun ui(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = tv("SARI ASTRO", 16f, true)
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        rawText = pill("RAW — CHECKING")
        header.addView(rawText, LinearLayout.LayoutParams(-2, dp(36)))
        val gallery = button("Gallery").apply { setOnClickListener { openGallery() } }
        header.addView(gallery, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        top.addView(header, LinearLayout.LayoutParams(-1, -2))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        isoText = pill("ISO —")
        exposureText = pill("EXP —")
        info.addView(isoText, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        info.addView(exposureText, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) })
        top.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val isoBtn = button("ISO").apply { setOnClickListener { cycleIso() } }
        val expBtn = button("EXPOSURE").apply { setOnClickListener { cycleExposure() } }
        focusButton = button("∞ FOCUS")
        focusButton.setOnClickListener { toggleFocus() }
        row.addView(isoBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        row.addView(expBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        row.addView(focusButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        controls.addView(row, LinearLayout.LayoutParams(-1, -2))

        val mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        sequenceButton = button("START ASTRO")
        sequenceButton.setOnClickListener { toggleSequence() }
        captureButton = button("CAPTURE RAW")
        captureButton.setOnClickListener { captureFrame(manual = true) }
        mainRow.addView(sequenceButton, LinearLayout.LayoutParams(0, dp(60), 1f).apply { rightMargin = dp(4) })
        mainRow.addView(captureButton, LinearLayout.LayoutParams(0, dp(60), 1f).apply { leftMargin = dp(4) })
        controls.addView(mainRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Keep controls clear of status bar / navigation bar / cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(4))
            controls.setPadding(dp(12) + bars.left, dp(8), dp(12) + bars.right, dp(12) + bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun tv(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun pill(text: String) = tv(text, 12f, false).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(0x88000000.toInt())
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC141A24.toInt())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- Camera lifecycle

    private fun openBackCamera() {
        if (camera != null || opening) return
        val probed = CameraProbe.probe(this)
        val selected = probed.firstOrNull { it.facingBack } ?: probed.firstOrNull()
        if (selected == null) {
            status.text = "Camera could not be opened."
            return
        }
        caps = selected
        cameraChars = try { cameraManager.getCameraCharacteristics(selected.id) } catch (t: Throwable) {
            status.text = "Camera could not be opened."
            return
        }
        status.text = if (selected.usableForRawAstro) "ASTRO • ${selected.id}" else "ASTRO LIMITED • ${selected.id}"
        rawText.text = if (selected.rawSupported) "RAW SENSOR • READY" else "RAW UNSUPPORTED"
        if (!selected.rawSupported) status.text = "RAW capture is not supported by this camera."
        captureButton.isEnabled = selected.usableForRawAstro
        sequenceButton.isEnabled = selected.usableForRawAstro
        selected.isoRange?.let { iso = iso.coerceIn(it.first, it.last) }
        selected.exposureNs?.let { r ->
            val upper = selected.maxFrameDurationNs?.let { minOf(r.last, it) } ?: r.last
            exposureNs = exposureNs.coerceIn(r.first, max(r.first, upper))
        }
        updateTexts()
        writer = Executors.newSingleThreadExecutor { r -> Thread(r, "SARI-DNG-Writer") }
        opening = true
        try {
            cameraManager.openCamera(selected.id, stateCallback, cameraHandler)
        } catch (t: Throwable) {
            opening = false
            status.text = "Camera could not be opened."
            Toast.makeText(this, t.message ?: "Camera could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(c: CameraDevice) {
            opening = false
            if (!started) { c.close(); return }
            camera = c
            createSession()
        }
        override fun onDisconnected(c: CameraDevice) {
            opening = false; c.close(); camera = null; session = null
        }
        override fun onError(c: CameraDevice, error: Int) {
            opening = false; c.close(); camera = null; session = null
            ui { status.text = "Camera could not be opened. (error $error)" }
        }
    }

    /** Preview: small and responsive, independent of the RAW size. Aspect close to the sensor's. */
    private fun choosePreviewSize(map: android.hardware.camera2.params.StreamConfigurationMap, target: Size?): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val limit = 1280L * 960L
        val ratio = target?.let { it.width.toDouble() / it.height } ?: (4.0 / 3.0)
        val ok = sizes.filter { it.width.toLong() * it.height <= limit }
        return ok.minWithOrNull(
            compareBy<Size> { kotlin.math.abs(it.width.toDouble() / it.height - ratio) }
                .thenByDescending { it.width.toLong() * it.height }
        ) ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
    }

    private fun createSession() {
        val c = camera ?: return
        val texture = preview.surfaceTexture ?: return
        val map = cameraChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        try {
            rawReader?.close(); rawReader = null
            val rawSize = if (caps?.rawSupported == true)
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } else null
            val pSize = choosePreviewSize(map, rawSize)
            previewSize = pSize
            texture.setDefaultBufferSize(pSize.width, pSize.height)
            ui { applyPreviewTransform() }
            previewSurface?.release()
            previewSurface = Surface(texture)

            if (rawSize != null) {
                val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                reader.setOnImageAvailableListener({ r ->
                    try { r.acquireNextImage()?.let(::onRaw) } catch (_: Throwable) { /* reader closed or over limit */ }
                }, cameraHandler)
                rawReader = reader
            }
            // PRIV preview + RAW_SENSOR is the stream combination guaranteed on RAW-capable devices.
            val surfaces = mutableListOf(previewSurface!!)
            rawReader?.let { surfaces.add(it.surface) }
            c.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) { s.close(); return }
                    session = s
                    updatePreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    ui { status.text = "Camera could not be opened. (session)" }
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            ui { status.text = "Camera could not be opened." }
        }
    }

    /** TextureView stretches the buffer to the view; letterbox to keep the buffer's aspect ratio (portrait-locked). */
    private fun applyPreviewTransform() {
        val ps = previewSize ?: return
        val vw = preview.width.toFloat(); val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bufAspect = minOf(ps.width, ps.height).toFloat() / max(ps.width, ps.height).toFloat() // portrait w/h
        var dw = vw; var dh = vw / bufAspect
        if (dh > vh) { dh = vh; dw = vh * bufAspect }
        val m = Matrix()
        m.setScale(dw / vw, dh / vh, vw / 2f, vh / 2f)
        preview.setTransform(m)
    }

    private fun updatePreview() {
        val c = camera ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode())
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            s.setRepeatingRequest(req.build(), null, cameraHandler)
        } catch (_: Throwable) { }
    }

    private fun supportedAfMode(): Int {
        val modes = cameraChars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return when {
            modes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) -> CameraCharacteristics.CONTROL_AF_MODE_AUTO
            else -> CameraCharacteristics.CONTROL_AF_MODE_OFF
        }
    }

    // ---------------------------------------------------------------- Capture

    private fun captureFrame(manual: Boolean) {
        val c = camera ?: return
        val s = session ?: return
        val raw = rawReader ?: return
        if (outstanding.get() > 0) {
            val stale = SystemClock.elapsedRealtime() - lastCaptureAtMs > exposureNs / 1_000_000L + 10_000L
            if (stale) { // a buffer was lost: recover instead of blocking forever
                outstanding.set(0); clearPending()
            } else {
                if (manual) ui { Toast.makeText(this, "Previous frame is still being saved.", Toast.LENGTH_SHORT).show() }
                return
            }
        }
        clearPending()
        val af = manualFocus && caps?.manualFocus == true
        try {
            val request = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(raw.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                val maxFd = caps?.maxFrameDurationNs
                set(CaptureRequest.SENSOR_FRAME_DURATION, if (maxFd != null) minOf(exposureNs, maxFd) else exposureNs)
                set(CaptureRequest.CONTROL_AF_MODE, if (af) CameraCharacteristics.CONTROL_AF_MODE_OFF else supportedAfMode())
                if (af) set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // 0 diopters = infinity
            }
            outstanding.incrementAndGet()
            lastCaptureAtMs = SystemClock.elapsedRealtime()
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    onResult(result)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    releaseOutstanding(); failedFrames.incrementAndGet()
                    ui { updateSequenceUi() }
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            releaseOutstanding()
            ui { Toast.makeText(this, t.message ?: "Capture failed", Toast.LENGTH_LONG).show() }
        }
    }

    private fun releaseOutstanding() { outstanding.updateAndGet { if (it > 0) it - 1 else 0 } }

    private fun clearPending() {
        synchronized(pendingLock) {
            pendingImages.values.forEach { runCatching { it.close() } }
            pendingImages.clear(); pendingResults.clear()
        }
    }

    private fun onResult(result: TotalCaptureResult) {
        val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val img = synchronized(pendingLock) {
            val i = pendingImages.remove(ts)
            if (i == null) pendingResults[ts] = result
            i
        }
        if (img != null) dispatchWrite(img, result)
    }

    private fun onRaw(image: Image) {
        val ts = image.timestamp
        val res = synchronized(pendingLock) {
            val r = pendingResults.remove(ts)
            if (r == null) pendingImages[ts] = image
            r
        }
        if (res != null) dispatchWrite(image, res)
    }

    private fun exifOrientation(): Int = when (cameraChars?.get(CameraCharacteristics.SENSOR_ORIENTATION)) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    /** DNG encoding happens on a dedicated thread; the Image is closed as soon as it is written. */
    private fun dispatchWrite(image: Image, result: TotalCaptureResult) {
        val chars = cameraChars
        val w = writer
        if (chars == null || w == null) { image.close(); releaseOutstanding(); return }
        val orientation = exifOrientation()
        try {
            w.execute {
                var ok = false
                try {
                    ok = writeDng(chars, result, image, orientation)
                } finally {
                    image.close()
                    releaseOutstanding()
                }
                if (ok) savedFrames.incrementAndGet() else failedFrames.incrementAndGet()
                ui { updateSequenceUi() }
            }
        } catch (_: RejectedExecutionException) {
            image.close(); releaseOutstanding()
        }
    }

    private fun writeDng(chars: CameraCharacteristics, result: TotalCaptureResult, image: Image, orientation: Int): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_Astro_${System.currentTimeMillis()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Astro/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val creator = DngCreator(chars, result)
            try {
                creator.setOrientation(orientation)
                val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Unable to open DNG stream")
                out.use { creator.writeImage(it, image) }
            } finally {
                creator.close()
            }
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            true
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            ui { Toast.makeText(this, "DNG could not be saved: ${t.message}", Toast.LENGTH_LONG).show() }
            false
        }
    }

    // ---------------------------------------------------------------- Controls

    private fun cycleIso() {
        val r = caps?.isoRange ?: return
        val candidates = listOf(100, 200, 400, 800, 1600, 3200, 6400).filter { it in r }
        if (candidates.isEmpty()) return
        iso = candidates[(candidates.indexOf(iso) + 1) % candidates.size]
        updateTexts()
    }

    private fun cycleExposure() {
        val r = caps?.exposureNs ?: return
        val maxFd = caps?.maxFrameDurationNs
        val candidates = listOf(250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L)
            .filter { it in r && (maxFd == null || it <= maxFd) }
        if (candidates.isEmpty()) return
        exposureNs = candidates[(candidates.indexOf(exposureNs) + 1) % candidates.size]
        updateTexts()
    }

    private fun toggleFocus() {
        if (caps?.manualFocus != true) {
            focusButton.text = "AUTO FOCUS"
            return
        }
        manualFocus = !manualFocus
        focusButton.text = if (manualFocus) "∞ FOCUS" else "AUTO FOCUS"
    }

    private fun toggleSequence() {
        if (sequenceRunning) stopSequence()
        else {
            savedFrames.set(0); failedFrames.set(0)
            sequenceStarted = SystemClock.elapsedRealtime()
            sequenceRunning = true
            sequenceButton.text = "STOP ASTRO"
            status.text = "ASTRO • RUNNING"
            cameraHandler.post(sequenceTick)
        }
    }

    private fun stopSequence() {
        sequenceRunning = false
        cameraHandler.removeCallbacks(sequenceTick)
        sequenceButton.text = "START ASTRO"
        status.text = "ASTRO • STOPPED • ${savedFrames.get()} FRAMES SAVED"
    }

    private fun updateSequenceUi() {
        val elapsed = if (sequenceStarted == 0L) 0L else SystemClock.elapsedRealtime() - sequenceStarted
        val mins = elapsed / 60_000
        val secs = (elapsed / 1_000) % 60
        captureButton.text = if (sequenceRunning) "SAVED ${savedFrames.get()}" else "CAPTURE RAW"
        if (sequenceRunning) {
            val failed = failedFrames.get()
            status.text = String.format(Locale.US, "ASTRO • %02d:%02d • %d SAVED%s", mins, secs, savedFrames.get(),
                if (failed > 0) " • $failed FAILED" else "")
        }
        updateTexts()
    }

    private fun updateTexts() {
        isoText.text = "ISO $iso"
        exposureText.text = "EXP ${formatExposure(exposureNs)}"
    }

    private fun formatExposure(ns: Long): String = when {
        ns >= 1_000_000_000L -> String.format(Locale.US, "%.1fs", ns / 1e9)
        ns >= 1_000_000L -> String.format(Locale.US, "%.0fms", ns / 1e6)
        else -> String.format(Locale.US, "%.0fus", ns / 1e3)
    }

    private fun openGallery() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        } catch (_: Throwable) {
            Toast.makeText(this, "No gallery app is available.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Teardown

    /** Release the camera whenever the activity leaves the foreground (no camera / buffer leaks in background). */
    private fun closeCamera() {
        sequenceRunning = false
        cameraHandler.removeCallbacksAndMessages(null)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        session?.close(); session = null
        camera?.close(); camera = null
        opening = false
        writer?.let { w ->
            w.shutdown()
            try { if (!w.awaitTermination(3, TimeUnit.SECONDS)) w.shutdownNow() } catch (_: InterruptedException) { w.shutdownNow() }
        }
        writer = null
        clearPending()
        outstanding.set(0)
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
    }

    override fun onStop() {
        started = false
        closeCamera()
        sequenceButton.text = "START ASTRO"
        super.onStop()
    }

    override fun onDestroy() {
        closeCamera()
        cameraThread.quitSafely()
        super.onDestroy()
    }
}
