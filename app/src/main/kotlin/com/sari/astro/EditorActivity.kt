package com.sari.astro

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import com.sari.astro.nativebridge.NativeCore
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

class EditorActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var stretchSeek: SeekBar
    private lateinit var denoiseSeek: SeekBar
    private lateinit var status: TextView
    private var path = ""
    private var w = 0
    private var h = 0
    private var cfa = 0
    private var mode = 1
    private var pendingFits: File? = null
    private val ex = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        path = intent.getStringExtra("path") ?: ""
        w = intent.getIntExtra("w", 0)
        h = intent.getIntExtra("h", 0)
        cfa = intent.getIntExtra("cfa", 0)
        buildUi()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun btn(t: String, primary: Boolean = false) = Button(this).apply {
        text = t
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
        setBackgroundColor(if (primary) Color.rgb(28, 88, 112) else Color.rgb(20, 27, 38))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "SARI Astro • Final Master"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(btn("BACK").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(76), dp(42)))
        root.addView(header)

        status = TextView(this).apply {
            text = "Preparing full-resolution master…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(status)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        modes.addView(btn("COLOR").apply { setOnClickListener { mode = 1; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("MONO").apply { setOnClickListener { mode = 0; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("NATURAL").apply { setOnClickListener { mode = 2; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(modes)

        root.addView(TextView(this).apply { text = "Stretch"; setTextColor(Color.LTGRAY) })
        stretchSeek = SeekBar(this).apply {
            max = 90
            progress = 30
            setOnSeekBarChangeListener(simpleChange { render() })
        }
        root.addView(stretchSeek, LinearLayout.LayoutParams(-1, dp(44)))

        root.addView(TextView(this).apply { text = "Final noise reduction (master export only)"; setTextColor(Color.LTGRAY) })
        denoiseSeek = SeekBar(this).apply {
            max = 100
            progress = 24
            setOnSeekBarChangeListener(simpleChange { render() })
        }
        root.addView(denoiseSeek, LinearLayout.LayoutParams(-1, dp(44)))

        val export1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        export1.addView(btn("SAVE 16-BIT PNG", true).apply { setOnClickListener { exportFullPng() } }, LinearLayout.LayoutParams(0, dp(54), 1f))
        export1.addView(btn("EXPORT FITS").apply { setOnClickListener { exportFits() } }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(6) })
        root.addView(export1)

        val export2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        export2.addView(btn("EXPORT F32").apply { setOnClickListener { exportF32() } }, LinearLayout.LayoutParams(0, dp(50), 1f))
        export2.addView(TextView(this).apply {
            text = "${w}×${h} linear master • non-generative processing"
            textSize = 10f
            setTextColor(Color.rgb(125, 141, 162))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, dp(50), 1.6f))
        root.addView(export2)
        setContentView(root)
    }

    private fun simpleChange(onUser: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onUser() }
        override fun onStartTrackingTouch(s: SeekBar?) = Unit
        override fun onStopTrackingTouch(s: SeekBar?) = Unit
    }

    private fun render() {
        if (path.isBlank()) return
        val stretch = 1f + stretchSeek.progress / 10f
        status.text = "Rendering preview from the full-resolution master…"
        ex.submit {
            val p = NativeCore.renderPreview(path, w, h, cfa, mode, stretch, 1600)
            ui.post {
                if (p == null) {
                    status.text = "Preview render failed"
                    return@post
                }
                val pixels = IntArray(p.width * p.height)
                val b = ByteBuffer.wrap(p.rgba)
                for (i in pixels.indices) {
                    val r = b.get().toInt() and 255
                    val g = b.get().toInt() and 255
                    val bl = b.get().toInt() and 255
                    val a = b.get().toInt() and 255
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
                image.setImageBitmap(Bitmap.createBitmap(pixels, p.width, p.height, Bitmap.Config.ARGB_8888))
                status.text = "Master ${w}×${h} • preview ${p.width}×${p.height} • full-resolution export ready"
            }
        }
    }

    private fun exportFullPng() {
        status.text = "Exporting full-resolution 16-bit PNG…"
        val temp = File(cacheDir, "sari_master_${System.currentTimeMillis()}.png")
        val stretch = 1f + stretchSeek.progress / 10f
        val denoise = denoiseSeek.progress / 100f
        ex.submit {
            val result = NativeCore.exportFullPng(path, w, h, cfa, mode, stretch, denoise, temp.absolutePath)
            ui.post {
                if (result != 0 || !temp.isFile || temp.length() < 100) {
                    status.text = "Full PNG export failed (status $result)"
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
                if (uri == null) { status.text = "Could not create gallery item"; temp.delete(); return@post }
                val pngSize = temp.length()
                val ok = ProjectRepository.copyFileToUri(this, temp, uri)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, if (ok) 0 else 1) }, null, null)
                }
                if (!ok) contentResolver.delete(uri, null, null)
                temp.delete()
                status.text = if (ok) "Saved full-resolution 16-bit PNG • ${String.format(Locale.US, "%.1f MB", pngSize / 1048576.0)}" else "Export failed"
                if (ok) Toast.makeText(this, "Full-resolution PNG saved", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun exportFits() {
        status.text = "Exporting linear FITS master…"
        val temp = File(cacheDir, "sari_master_${System.currentTimeMillis()}.fits")
        ex.submit {
            val result = NativeCore.exportLinearFits(path, w, h, cfa, temp.absolutePath)
            ui.post {
                if (result != 0 || !temp.isFile || temp.length() < 2880) {
                    status.text = "FITS export failed (status $result)"
                    temp.delete()
                    return@post
                }
                pendingFits = temp
                val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/fits"
                    putExtra(android.content.Intent.EXTRA_TITLE, "SARI_Astro_linear_${System.currentTimeMillis()}.fits")
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, 32)
            }
        }
    }

    private fun exportF32() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_TITLE, File(path).name)
        }
        startActivityForResult(intent, 31)
    }

    override fun onActivityResult(r: Int, c: Int, d: android.content.Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 31 && c == RESULT_OK && d?.data != null) {
            val ok = ProjectRepository.copyFileToUri(this, File(path), d.data!!)
            Toast.makeText(this, if (ok) "F32 exported" else "F32 export failed", Toast.LENGTH_SHORT).show()
        } else if (r == 32) {
            val temp = pendingFits
            if (c == RESULT_OK && d?.data != null && temp?.isFile == true) {
                val ok = ProjectRepository.copyFileToUri(this, temp, d.data!!)
                Toast.makeText(this, if (ok) "Linear FITS exported" else "FITS export failed", Toast.LENGTH_LONG).show()
            }
            temp?.delete()
            pendingFits = null
        }
    }

    override fun onDestroy() {
        pendingFits?.delete()
        pendingFits = null
        ex.shutdownNow()
        super.onDestroy()
    }
}
