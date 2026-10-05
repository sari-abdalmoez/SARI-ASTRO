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
    private fun btn(t: String) = Button(this).apply {
        text = t
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "SARI Astro • Final Master"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, 1)
        })
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
            progress = 32
            setOnSeekBarChangeListener(simpleChange { render() })
        }
        root.addView(denoiseSeek, LinearLayout.LayoutParams(-1, dp(44)))

        val actions1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions1.addView(btn("SAVE FULL 16-BIT PNG").apply { setOnClickListener { exportFullPng() } }, LinearLayout.LayoutParams(0, dp(54), 2f))
        actions1.addView(btn("EXPORT F32").apply { setOnClickListener { exportF32() } }, LinearLayout.LayoutParams(0, dp(54), 1f))
        root.addView(actions1)
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
        status.text = "Rendering full-resolution master preview…"
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
                status.text = "Master ${w}×${h} • preview 1600px max • native 16-bit export ready"
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
                if (uri == null) {
                    status.text = "Could not create gallery item"
                    temp.delete()
                    return@post
                }
                val pngBytes = temp.length()
                val ok = ProjectRepository.copyFileToUri(this, temp, uri)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, if (ok) 0 else 1) }, null, null)
                }
                if (!ok) contentResolver.delete(uri, null, null)
                temp.delete()
                status.text = if (ok) "Saved full-resolution 16-bit PNG • ${String.format(java.util.Locale.US, "%.1f MB", pngBytes / 1048576.0)}" else "Export failed"
                if (ok) Toast.makeText(this, "Full-resolution PNG saved", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        ex.shutdownNow()
        super.onDestroy()
    }
}
