package com.sari.astro

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.sari.astro.nativebridge.NativeCore
import com.sari.astro.ui.SariKit
import com.sari.astro.ui.SariPalette
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result editor. The stacked F32 master is never modified: every adjustment is a
 * render-time parameter, so the original can always be reproduced and compared.
 */
class EditorActivity : Activity() {

    /** Adjustments in slider units; converted to native parameters on render. */
    private data class Look(
        val stretch: Int,      // detail: 0..90 -> stretch 1.0..10.0
        val denoise: Int,      // noise reduction: 0..100 %
        val nebula: Int,       // nebula visibility: 0..100 %
        val brightness: Int,   // -30..30 -> -0.30..+0.30
        val saturation: Int    // colour: 0..200 %
    )

    private inner class Adjustment(
        val title: String,
        val min: Int,
        val max: Int,
        val read: (Look) -> Int,
        val write: (Look, Int) -> Look,
        val format: (Int) -> String
    ) {
        lateinit var bar: SeekBar
        lateinit var value: TextView
    }

    private lateinit var kit: SariKit
    private lateinit var image: ImageView
    private lateinit var statusText: TextView
    private lateinit var modeHolder: LinearLayout
    private lateinit var compareBtn: Button
    private val adjustments = listOf(
        Adjustment("Detail", 0, 90, { it.stretch }, { l, v -> l.copy(stretch = v) }) { "%.1f×".format(Locale.US, 1f + it / 10f) },
        Adjustment("Nebula visibility", 0, 100, { it.nebula }, { l, v -> l.copy(nebula = v) }) { "$it%" },
        Adjustment("Brightness", -30, 30, { it.brightness }, { l, v -> l.copy(brightness = v) }) { "%+.2f".format(Locale.US, it / 100f) },
        Adjustment("Colour", 0, 200, { it.saturation }, { l, v -> l.copy(saturation = v) }) { "$it%" },
        Adjustment("Noise reduction", 0, 100, { it.denoise }, { l, v -> l.copy(denoise = v) }) { "$it%" }
    )

    private val modeIds = intArrayOf(1, 0, 2)              // Color, Mono, Natural (native mode ids)
    private val modeLabels = listOf("Color", "Mono", "Natural")
    private var path = ""
    private var w = 0
    private var h = 0
    private var cfa = 0
    private var mode = 1
    private val looks = HashMap<Int, Look>()
    private var showingOriginal = false
    private var pendingFits: File? = null

    private val ex = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val renderSeq = AtomicInteger(0)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        path = intent.getStringExtra("path") ?: ""
        w = intent.getIntExtra("w", 0)
        h = intent.getIntExtra("h", 0)
        cfa = intent.getIntExtra("cfa", 0)
        // Mono stacks (from imported JPEG/PNG/WebP) have no colour filter array: only Mono applies.
        if (cfa < 0) mode = 0
        for (id in modeIds) looks[id] = defaultLook(id)
        kit = SariKit(this)
        buildUi()
        renderNow()
    }

    private fun defaultLook(modeId: Int) = when (modeId) {
        0 -> Look(stretch = 28, denoise = 18, nebula = 0, brightness = 0, saturation = 100)   // Mono
        2 -> Look(stretch = 28, denoise = 20, nebula = 0, brightness = 0, saturation = 100)   // Natural
        else -> Look(stretch = 30, denoise = 24, nebula = 0, brightness = 0, saturation = 100) // Color
    }

    private val currentLook: Look get() = looks.getValue(mode)

    private fun lp(bottomDp: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(bottomDp) }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(SariPalette.BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(18))
        }

        // Header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(kit.text("Result", 22f, bold = true))
        titles.addView(kit.text("${w}×${h} linear master", 12f, SariPalette.TEXT_MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.button("Back").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(kit.dp(84), kit.dp(42)))
        root.addView(header, lp(12))

        // Preview
        val frame = FrameLayout(this).apply {
            background = kit.surface(SariPalette.SURFACE, 18)
            setPadding(kit.dp(6), kit.dp(6), kit.dp(6), kit.dp(6))
        }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.40f).toInt()).apply {
            bottomMargin = kit.dp(8)
        })

        statusText = kit.text("", 12f, SariPalette.TEXT_MUTED).apply { gravity = Gravity.CENTER }
        root.addView(statusText, lp(12))

        // Compare: press and hold to see the default result for this mode.
        compareBtn = kit.button("Hold to compare with original").apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { showingOriginal = true; renderNow() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { showingOriginal = false; renderNow() }
                }
                true
            }
        }
        root.addView(compareBtn, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { bottomMargin = kit.dp(12) })

        // Mode
        modeHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshModeSelector()
        root.addView(modeHolder, lp(14))

        // Adjustments
        val card = kit.card()
        card.addView(kit.section("Adjust"))
        adjustments.forEach { a ->
            val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(10), 0, 0) }
            labelRow.addView(kit.text(a.title, 13f), LinearLayout.LayoutParams(0, -2, 1f))
            a.value = kit.text("", 13f, SariPalette.ACCENT, bold = true)
            labelRow.addView(a.value)
            card.addView(labelRow)
            a.bar = SeekBar(this).apply {
                max = a.max - a.min
                progressTintList = android.content.res.ColorStateList.valueOf(SariPalette.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(SariPalette.ACCENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = p + a.min
                        looks[mode] = a.write(currentLook, v)
                        a.value.text = a.format(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = renderNow()
                })
            }
            card.addView(a.bar, LinearLayout.LayoutParams(-1, kit.dp(40)))
        }
        root.addView(card, lp(12))
        syncSliders()

        root.addView(kit.button("Reset this mode").apply {
            setOnClickListener {
                looks[mode] = defaultLook(mode)
                syncSliders()
                renderNow()
            }
        }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { bottomMargin = kit.dp(16) })

        // Export
        val export = kit.card()
        export.addView(kit.section("Export"))
        export.addView(
            kit.button("Save 16-bit PNG", primary = true).apply { setOnClickListener { exportFullPng() } },
            LinearLayout.LayoutParams(-1, kit.dp(52)).apply { topMargin = kit.dp(12) }
        )
        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondary.addView(
            kit.button("FITS").apply { setOnClickListener { exportFits() } },
            LinearLayout.LayoutParams(0, kit.dp(48), 1f)
        )
        secondary.addView(
            kit.button("F32 master").apply { setOnClickListener { exportF32() } },
            LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { leftMargin = kit.dp(10) }
        )
        export.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
        root.addView(export, lp(0))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshModeSelector() {
        if (cfa < 0) { modeHolder.visibility = android.view.View.GONE; return }
        modeHolder.visibility = android.view.View.VISIBLE
        modeHolder.removeAllViews()
        modeHolder.addView(
            kit.segmented(modeLabels, modeIds.indexOf(mode)) { index -> selectMode(modeIds[index]) },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun selectMode(newMode: Int) {
        if (newMode == mode) return
        mode = newMode
        refreshModeSelector()
        syncSliders()
        renderNow()
    }

    private fun syncSliders() {
        val look = currentLook
        adjustments.forEach { a ->
            val v = a.read(look)
            a.bar.progress = v - a.min
            a.value.text = a.format(v)
        }
    }

    // Native parameter conversions
    private val Look.stretchF get() = 1f + stretch / 10f
    private val Look.denoiseF get() = denoise / 100f
    private val Look.nebulaF get() = nebula / 100f
    private val Look.brightnessF get() = brightness / 100f
    private val Look.saturationF get() = saturation / 100f

    /** Latest-wins preview: stale renders are dropped, so dragging never queues up work. */
    private fun renderNow() {
        if (path.isBlank()) return
        val look = if (showingOriginal) defaultLook(mode) else currentLook
        val seq = renderSeq.incrementAndGet()
        val rendering = mode
        statusText.text = if (showingOriginal) "Showing original stack" else "Rendering preview…"
        ex.submit {
            val p = NativeCore.renderPreview(
                path, w, h, cfa, rendering,
                look.stretchF, look.denoiseF,
                maxDim = 1600,
                brightness = look.brightnessF, saturation = look.saturationF, nebula = look.nebulaF
            )
            val pixels = p?.let { toArgb(it) }
            ui.post {
                if (seq != renderSeq.get()) return@post
                if (p == null || pixels == null) {
                    statusText.text = "Preview render failed"
                    return@post
                }
                image.setImageBitmap(Bitmap.createBitmap(pixels, p.width, p.height, Bitmap.Config.ARGB_8888))
                statusText.text = if (showingOriginal) "Original • release to return"
                else "Preview ${p.width}×${p.height} • full resolution on export"
            }
        }
    }

    private fun toArgb(p: NativeCore.Preview): IntArray {
        val px = IntArray(p.width * p.height)
        val src = p.rgba
        var j = 0
        for (i in px.indices) {
            val r = src[j].toInt() and 255
            val g = src[j + 1].toInt() and 255
            val b = src[j + 2].toInt() and 255
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            j += 4
        }
        return px
    }

    private fun currentFullLook(): Look = currentLook

    private fun exportFullPng() {
        statusText.text = "Exporting full-resolution 16-bit PNG…"
        val temp = File(cacheDir, "sari_master_${System.currentTimeMillis()}.png")
        val look = currentFullLook()
        val rendering = mode
        ex.submit {
            val result = NativeCore.exportFullPng(
                path, w, h, cfa, rendering,
                look.stretchF, look.denoiseF,
                look.brightnessF, look.saturationF, look.nebulaF,
                temp.absolutePath
            )
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (result != 0 || !temp.isFile || temp.length() < 100) {
                    statusText.text = "Full PNG export failed (status $result)"
                    temp.delete()
                    return@post
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_Astro_master_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Astro/Results")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    statusText.text = "Could not create gallery item"
                    temp.delete()
                    return@post
                }
                val pngSize = temp.length()
                val ok = ProjectRepository.copyFileToUri(this, temp, uri)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, if (ok) 0 else 1) }, null, null)
                }
                if (!ok) contentResolver.delete(uri, null, null)
                temp.delete()
                statusText.text = if (ok) "Saved 16-bit PNG • ${String.format(Locale.US, "%.1f MB", pngSize / 1048576.0)}" else "Export failed"
                if (ok) Toast.makeText(this, "Full-resolution PNG saved", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportFits() {
        statusText.text = "Preparing linear FITS…"
        val temp = File(cacheDir, "sari_master_${System.currentTimeMillis()}.fits")
        ex.submit {
            val result = NativeCore.exportLinearFits(path, w, h, cfa, temp.absolutePath)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (result != 0 || !temp.isFile || temp.length() < 2880) {
                    statusText.text = "FITS export failed (status $result)"
                    temp.delete()
                    return@post
                }
                pendingFits = temp
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/fits"
                    putExtra(Intent.EXTRA_TITLE, "SARI_Astro_linear_${System.currentTimeMillis()}.fits")
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, REQ_FITS)
            }
        }
    }

    private fun exportF32() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, File(path).name)
        }
        startActivityForResult(intent, REQ_F32)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_F32 && resultCode == RESULT_OK && data?.data != null) {
            val ok = ProjectRepository.copyFileToUri(this, File(path), data.data!!)
            Toast.makeText(this, if (ok) "F32 exported" else "F32 export failed", Toast.LENGTH_SHORT).show()
        } else if (requestCode == REQ_FITS) {
            val temp = pendingFits
            if (resultCode == RESULT_OK && data?.data != null && temp?.isFile == true) {
                val ok = ProjectRepository.copyFileToUri(this, temp, data.data!!)
                Toast.makeText(this, if (ok) "Linear FITS exported" else "FITS export failed", Toast.LENGTH_LONG).show()
            }
            temp?.delete()
            pendingFits = null
        }
    }

    override fun onDestroy() {
        pendingFits?.delete()
        pendingFits = null
        ui.removeCallbacksAndMessages(null)
        ex.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val REQ_F32 = 31
        const val REQ_FITS = 32
    }
}
