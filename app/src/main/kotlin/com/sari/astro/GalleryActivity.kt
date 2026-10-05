package com.sari.astro

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sari.astro.nativebridge.NativeCore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {
    private lateinit var grid: GridLayout
    private lateinit var empty: TextView
    private lateinit var subtitle: TextView
    private val work = Executors.newFixedThreadPool(2)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        chooseProjectForImport(uris)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        buildUi()
        loadProjects()
    }

    override fun onResume() { super.onResume(); if (::grid.isInitialized) loadProjects() }
    override fun onDestroy() { work.shutdownNow(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun bg(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.rgb(42, 55, 72))
    }
    private fun button(textValue: String, primary: Boolean = false) = Button(this).apply {
        text = textValue; isAllCaps = false; minHeight = 0; minimumHeight = 0
        setTextColor(Color.WHITE)
        background = bg(if (primary) Color.rgb(28, 92, 119) else Color.rgb(22, 29, 40), 13)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.rgb(7, 11, 18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@GalleryActivity).apply {
                text = "SARI Astro"; textSize = 23f; setTextColor(Color.WHITE); setTypeface(typeface, 1)
            })
            subtitle = TextView(this@GalleryActivity).apply {
                text = "Your astrophotography projects"; textSize = 12f; setTextColor(Color.rgb(151, 165, 183))
            }
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("IMPORT").apply { setOnClickListener { startImport() } }, LinearLayout.LayoutParams(dp(92), dp(44)).apply { rightMargin = dp(6) })
        header.addView(button("+ PROJECT", true).apply { setOnClickListener { createProject() } }, LinearLayout.LayoutParams(dp(110), dp(44)))
        root.addView(header)

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER }
        nav.addView(button("CAMERA").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, dp(44), 1f))
        nav.addView(button("SETTINGS").apply { setOnClickListener { startActivity(android.content.Intent(this@GalleryActivity, SettingsActivity::class.java)) } }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        empty = TextView(this).apply {
            text = "No projects yet. Capture RAW frames or import DNG/JPEG/PNG."
            textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        root.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = false; alignmentMode = GridLayout.ALIGN_BOUNDS }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        setContentView(root)
    }

    private fun loadProjects() {
        grid.removeAllViews()
        val projects = ProjectRepository.listProjects(this)
        empty.visibility = if (projects.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        subtitle.text = if (projects.isEmpty()) "No projects" else "${projects.size} project${if (projects.size == 1) "" else "s"}"
        for ((index, project) in projects.withIndex()) addProjectCard(project, index)
    }

    private fun addProjectCard(project: ProjectRepository.ProjectInfo, index: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(Color.rgb(15, 21, 30), 16); setPadding(dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener { openProject(project) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = bg(Color.rgb(8, 11, 16), 12)
        }
        card.addView(image, LinearLayout.LayoutParams(-1, dp(145)))
        card.addView(TextView(this).apply {
            text = project.title; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, 1); setPadding(dp(4), dp(8), dp(4), 0)
        })
        card.addView(TextView(this).apply {
            text = buildString {
                append("${project.lightCount} RAW")
                if (project.importedCount > 0) append(" • ${project.importedCount} imports")
                if (project.calibrationCount > 0) append(" • ${project.calibrationCount} cal")
            }
            textSize = 11f; setTextColor(Color.rgb(151, 165, 183)); setPadding(dp(4), dp(3), dp(4), dp(2))
        })
        card.addView(TextView(this).apply {
            text = if (project.totalBytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", project.totalBytes / 1048576.0) else "${project.totalBytes / 1024} KB"
            textSize = 10f; setTextColor(Color.rgb(112, 128, 148)); setPadding(dp(4), 0, dp(4), dp(3))
        })
        val params = GridLayout.LayoutParams().apply {
            width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(index % 2, 1f); rowSpec = GridLayout.spec(index / 2)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        grid.addView(card, params)

        work.execute {
            val bitmap = loadProjectThumb(project)
            runOnUiThread { if (!isFinishing && !isDestroyed && bitmap != null) image.setImageBitmap(bitmap) }
        }
    }

    private fun loadProjectThumb(project: ProjectRepository.ProjectInfo): Bitmap? {
        val resultPng = File(project.dir, "results").listFiles { f -> f.extension.equals("png", true) }
            ?.maxByOrNull { it.lastModified() }
        if (resultPng != null) return decodeThumb(resultPng, 640)
        val light = project.firstLight ?: return null
        if (light.type == ProjectRepository.FrameType.IMPORTED) return decodeThumb(File(light.path), 640)
        return runCatching {
            NativeCore.renderPreview(light.path, light.width, light.height, light.cfa, 1, 2.0f, 320)?.let { p ->
                val pixels = IntArray(p.width * p.height)
                val b = java.nio.ByteBuffer.wrap(p.rgba)
                for (i in pixels.indices) {
                    val r = b.get().toInt() and 255; val g = b.get().toInt() and 255; val bl = b.get().toInt() and 255; val a = b.get().toInt() and 255
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
                Bitmap.createBitmap(pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
            }
        }.getOrNull()
    }

    private fun decodeThumb(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun openProject(project: ProjectRepository.ProjectInfo) {
        startActivity(android.content.Intent(this, ProjectActivity::class.java).apply { putExtra("projectDir", project.dir.absolutePath) })
    }

    private fun createProject() {
        val input = EditText(this).apply { hint = "e.g. Milky Way - October"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("New Astro Project").setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CREATE") { _, _ ->
                val width = ProjectRepository.latestSession(this)?.width ?: 0
                val height = ProjectRepository.latestSession(this)?.height ?: 0
                val cfa = ProjectRepository.latestSession(this)?.cfa ?: 0
                val session = ProjectRepository.newSession(this, width, height, cfa, null, input.text.toString().ifBlank { "Astro Project" })
                startActivity(android.content.Intent(this, ProjectActivity::class.java).apply { putExtra("projectDir", session.dir.absolutePath) })
            }.show()
    }

    private fun startImport() {
        importLauncher.launch(arrayOf("image/*", "image/x-adobe-dng"))
    }

    private fun chooseProjectForImport(uris: List<android.net.Uri>) {
        val projects = ProjectRepository.listProjects(this)
        val labels = projects.map { "${it.title} • ${it.lightCount} RAW" }.toMutableList()
        labels += "Create a new project"
        AlertDialog.Builder(this).setTitle("Import to project").setItems(labels.toTypedArray()) { _, which ->
            if (which == projects.size) createImportProject(uris) else importInto(projects[which].dir, uris)
        }.show()
    }

    private fun createImportProject(uris: List<android.net.Uri>) {
        val input = EditText(this).apply { hint = "Project name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("New project for import").setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT") { _, _ ->
                val session = ProjectRepository.newSession(this, 0, 0, 0, null, input.text.toString().ifBlank { "Imported Astro Project" })
                importInto(session.dir, uris)
            }.show()
    }

    private fun importInto(dir: File, uris: List<android.net.Uri>) {
        Toast.makeText(this, "Importing ${uris.size} file${if (uris.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
        work.execute {
            val r = ProjectRepository.importUris(this, dir, uris)
            runOnUiThread {
                loadProjects()
                val msg = "Imported ${r.imported} • skipped ${r.skipped}" + if (r.errors.isEmpty()) "" else " • ${r.errors.size} failed"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
