package com.sari.astro

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.core.view.WindowCompat
import com.sari.astro.camera.CameraCaps
import com.sari.astro.camera.CameraProbe
import java.util.Locale
import kotlin.math.max
import java.util.ArrayDeque

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
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var caps: CameraCaps? = null
    private var cameraChars: CameraCharacteristics? = null
    private val pendingResults = ArrayDeque<TotalCaptureResult>()

    private var iso = 800
    private var exposureNs = 4_000_000_000L
    private var manualFocus = true
    private var sequenceRunning = false
    private var sequenceStarted = 0L
    private var sequenceFrames = 0
    private val sequenceTick = object : Runnable {
        override fun run() {
            if (!sequenceRunning) return
            captureFrame()
            cameraHandler.postDelayed(this, max(5_000L, exposureNs / 1_000_000L + 750L))
            updateSequenceUi()
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) openBackCamera()
        else Toast.makeText(this, "Camera permission is required for SARI Astro.", Toast.LENGTH_LONG).show()
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
                if (hasCameraPermission()) openBackCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (!hasCameraPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else if (preview.isAvailable) {
            openBackCamera()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 40, 24, 8)
        }
        status = tv("SARI ASTRO", 18f, true)
        top.addView(status, LinearLayout.LayoutParams(0, 52, 1f))
        rawText = pill("RAW — CHECKING")
        top.addView(rawText, LinearLayout.LayoutParams(-2, 44))
        val gallery = button("Gallery").apply { setOnClickListener { openGallery() } }
        top.addView(gallery, LinearLayout.LayoutParams(-2, 44).apply { leftMargin = 8 })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(18, 0, 18, 0)
        }
        isoText = pill("ISO —")
        exposureText = pill("EXP —")
        info.addView(isoText, LinearLayout.LayoutParams(0, 44, 1f).apply { rightMargin = 6 })
        info.addView(exposureText, LinearLayout.LayoutParams(0, 44, 1f).apply { leftMargin = 6 })
        root.addView(info, FrameLayout.LayoutParams(-1, 52, Gravity.TOP).apply { topMargin = 98 })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 20)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val iso = button("ISO").apply { setOnClickListener { cycleIso() } }
        val exp = button("EXPOSURE").apply { setOnClickListener { cycleExposure() } }
        focusButton = button("∞ FOCUS")
        focusButton.setOnClickListener { toggleFocus() }
        row.addView(iso, LinearLayout.LayoutParams(0, 48, 1f).apply { rightMargin = 6 })
        row.addView(exp, LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 6; rightMargin = 6 })
        row.addView(focusButton, LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 6 })
        controls.addView(row, LinearLayout.LayoutParams(-1, 54))

        val mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        sequenceButton = button("START ASTRO")
        sequenceButton.setOnClickListener { toggleSequence() }
        captureButton = button("CAPTURE RAW")
        captureButton.setOnClickListener { captureFrame() }
        mainRow.addView(sequenceButton, LinearLayout.LayoutParams(0, 64, 1f).apply { rightMargin = 6 })
        mainRow.addView(captureButton, LinearLayout.LayoutParams(0, 64, 1f).apply { leftMargin = 6 })
        controls.addView(mainRow, LinearLayout.LayoutParams(-1, 72))

        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
        setBackgroundColor(0x88000000.toInt())
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC141A24.toInt())
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openBackCamera() {
        val probed = CameraProbe.probe(this)
        val selected = probed.firstOrNull { it.facingBack } ?: probed.firstOrNull()
        if (selected == null) {
            status.text = "NO CAMERA"
            return
        }
        caps = selected
        cameraChars = cameraManager.getCameraCharacteristics(selected.id)
        status.text = if (selected.usableForRawAstro) "ASTRO • ${selected.id}" else "ASTRO LIMITED • ${selected.id}"
        rawText.text = if (selected.rawSupported) "RAW SENSOR • READY" else "RAW UNSUPPORTED"
        captureButton.isEnabled = selected.rawSupported
        sequenceButton.isEnabled = selected.rawSupported
        if (selected.isoRange != null) iso = iso.coerceIn(selected.isoRange.first, selected.isoRange.last)
        if (selected.exposureNs != null) exposureNs = exposureNs.coerceIn(selected.exposureNs.first, selected.exposureNs.last)
        updateTexts()
        try {
            cameraManager.openCamera(selected.id, stateCallback, cameraHandler)
        } catch (t: Throwable) {
            status.text = "CAMERA ERROR"
            Toast.makeText(this, t.message ?: "Unable to open camera", Toast.LENGTH_LONG).show()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(c: CameraDevice) {
            camera = c
            createSession()
        }
        override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
        override fun onError(c: CameraDevice, error: Int) { c.close(); camera = null; runOnUiThread { status.text = "CAMERA ERROR $error" } }
    }

    private fun createSession() {
        val c = camera ?: return
        val texture = preview.surfaceTexture ?: return
        val map = cameraChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val previewSize = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.maxByOrNull { it.width.toLong() * it.height } ?: android.util.Size(1920, 1080)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(texture)

        rawReader?.close(); rawReader = null
        jpegReader?.close(); jpegReader = null
        val rawSize = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        if (rawSize != null && caps?.rawSupported == true) {
            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 2)
            rawReader!!.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.let(::onRaw) }, cameraHandler)
        }
        val jpegSize = map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height } ?: android.util.Size(1280, 720)
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, android.graphics.ImageFormat.JPEG, 2)
        jpegReader!!.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, cameraHandler)

        val surfaces = mutableListOf(previewSurface!!, jpegReader!!.surface)
        rawReader?.let { surfaces.add(it.surface) }
        c.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                updatePreview()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                runOnUiThread { status.text = "SESSION FAILED" }
            }
        }, cameraHandler)
    }

    private fun updatePreview() {
        val c = camera ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode())
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        runCatching { s.setRepeatingRequest(req.build(), null, cameraHandler) }
    }

    private fun supportedAfMode(): Int {
        val modes = cameraChars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return when {
            modes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) -> CameraCharacteristics.CONTROL_AF_MODE_AUTO
            else -> CameraCharacteristics.CONTROL_AF_MODE_OFF
        }
    }

    private fun captureFrame() {
        val c = camera ?: return
        val s = session ?: return
        val jpeg = jpegReader ?: return
        val raw = rawReader
        val request = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpeg.surface)
            raw?.let { addTarget(it.surface) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            set(CaptureRequest.CONTROL_AF_MODE, if (manualFocus && caps?.manualFocus == true) CameraCharacteristics.CONTROL_AF_MODE_OFF else supportedAfMode())
            if (manualFocus && caps?.manualFocus == true) set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        sequenceFrames += 1
        try {
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    synchronized(pendingResults) { pendingResults.addLast(result) }
                    runOnUiThread { updateSequenceUi() }
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: "Capture failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun onRaw(image: Image) {
        val result = synchronized(pendingResults) { if (pendingResults.isEmpty()) null else pendingResults.removeFirst() }
        if (result == null) { image.close(); return }
        try {
            val name = "SARI_Astro_${System.currentTimeMillis()}.dng"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Astro/RAW")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) return
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    DngCreator(cameraChars!!, result).use { it.writeImage(out, image) }
                } ?: throw IllegalStateException("Unable to open DNG stream")
                if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
            }
        } finally {
            image.close()
        }
    }

    private fun cycleIso() {
        val r = caps?.isoRange ?: return
        val candidates = listOf(100, 200, 400, 800, 1600, 3200, 6400).filter { it in r }
        if (candidates.isEmpty()) return
        val idx = candidates.indexOf(iso).let { if (it < 0) -1 else it }
        iso = candidates[(idx + 1) % candidates.size]
        updateTexts()
    }

    private fun cycleExposure() {
        val r = caps?.exposureNs ?: return
        val candidates = listOf(250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L)
            .filter { it in r }
        if (candidates.isEmpty()) return
        val idx = candidates.indexOf(exposureNs).let { if (it < 0) -1 else it }
        exposureNs = candidates[(idx + 1) % candidates.size]
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
        if (sequenceRunning) {
            sequenceRunning = false
            cameraHandler.removeCallbacks(sequenceTick)
            sequenceButton.text = "START ASTRO"
            status.text = "ASTRO • STOPPED • $sequenceFrames FRAMES"
        } else {
            sequenceFrames = 0
            sequenceStarted = SystemClock.elapsedRealtime()
            sequenceRunning = true
            status.text = "ASTRO • RUNNING"
            sequenceButton.text = "STOP ASTRO"
            captureFrame()
            cameraHandler.postDelayed(sequenceTick, max(5_000L, exposureNs / 1_000_000L + 750L))
        }
    }

    private fun updateSequenceUi() {
        val elapsed = if (sequenceStarted == 0L) 0L else SystemClock.elapsedRealtime() - sequenceStarted
        val mins = elapsed / 60_000
        val secs = (elapsed / 1_000) % 60
        captureButton.text = if (sequenceRunning) "RAW FRAME $sequenceFrames" else "CAPTURE RAW"
        if (sequenceRunning) status.text = String.format(Locale.US, "ASTRO • %02d:%02d • %d FRAMES", mins, secs, sequenceFrames)
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
        startActivity(android.content.Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://media/external/images/media")
            type = "image/*"
        })
    }

    override fun onStop() {
        sequenceRunning = false
        cameraHandler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        synchronized(pendingResults) { pendingResults.clear() }
        sequenceRunning = false
        cameraHandler.removeCallbacksAndMessages(null)
        session?.close(); session = null
        camera?.close(); camera = null
        rawReader?.close(); rawReader = null
        jpegReader?.close(); jpegReader = null
        previewSurface?.release(); previewSurface = null
        cameraThread.quitSafely()
        super.onDestroy()
    }
}
