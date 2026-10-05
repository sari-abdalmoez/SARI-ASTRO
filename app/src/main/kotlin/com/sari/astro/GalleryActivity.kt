package com.sari.astro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.sari.astro.ProjectRepository.FrameType
import java.io.File
import java.util.Locale

class GalleryActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var selectedText: TextView
    private lateinit var stackButton: Button
    private var frames = emptyList<ProjectRepository.FrameInfo>()
    private val checks = mutableListOf<CheckBox>()
    private var darkPath: String? = null
    private var flatPath: String? = null
    private var biasPath: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        buildUi()
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(18, 24, 34)); cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.rgb(43, 53, 69))
    }
    private fun button(t: String, primary: Boolean = false) = Button(this).apply {
        text = t; isAllCaps = false; minHeight = 0; minimumHeight = 0
        setTextColor(Color.WHITE); background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(31, 99, 128) else Color.rgb(24, 31, 43))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.rgb(49, 62, 80))
        }
    }
    private fun label(t: String, size: Float = 13f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.LTGRAY)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(7, 11, 18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@GalleryActivity).apply {
                text = "SARI Astro Gallery"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, 1)
            })
            addView(label("RAW frames • calibration • smart stacking", 12f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectedText = label("0 selected", 12f).apply {
            setTextColor(Color.rgb(136, 209, 229)); gravity = Gravity.CENTER
        }
        header.addView(selectedText, LinearLayout.LayoutParams(dp(92), dp(44)))
        root.addView(header)

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER }
        nav.addView(button("Camera").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(button("Projects").apply { setOnClickListener { startActivity(Intent(this@GalleryActivity, ProjectsActivity::class.java)) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(button("Settings").apply { setOnClickListener { startActivity(Intent(this@GalleryActivity, SettingsActivity::class.java)) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        val actionsTop = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actionsTop.addView(button("Refresh").apply { setOnClickListener { load() } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actionsTop.addView(button("Clear").apply { setOnClickListener { checks.forEach { it.isChecked = false }; updateSelectionUi() } }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        root.addView(actionsTop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        stackButton = button("STACK SELECTED", true).apply { isEnabled = false; setOnClickListener { startStack() } }
        root.addView(stackButton, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(7) })
        setContentView(root)
    }

    private fun load() {
        frames = ProjectRepository.listFrames(this)
            .filter { it.type == FrameType.LIGHT }
            .sortedByDescending { File(it.path).lastModified() }
        checks.clear(); listBox.removeAllViews()
        addSection("LIGHT FRAMES", "Pick the subframes you want to register and stack.")
        if (frames.isEmpty()) {
            listBox.addView(label("No LIGHT RAW frames yet. Return to Camera and capture a sequence.", 15f).apply {
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        } else {
            frames.forEachIndexed { idx, f -> addFrameCard(idx, f) }
        }
        addCalibrationChoices()
        updateSelectionUi()
    }

    private fun addSection(title: String, subtitle: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(Color.rgb(136, 209, 229)); setTypeface(typeface, 1) })
        box.addView(label(subtitle, 11f).apply { setPadding(0, dp(3), 0, 0) })
        listBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun addFrameCard(index: Int, f: ProjectRepository.FrameInfo) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val cb = CheckBox(this).apply {
            text = f.name; isChecked = true; setTextColor(Color.WHITE); textSize = 13f
            setOnCheckedChangeListener { _, _ -> updateSelectionUi() }
        }
        checks += cb
        top.addView(cb, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(label("${formatExp(f.exposureNs)}", 12f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(76), -2))
        row.addView(top)
        row.addView(label("ISO ${f.iso}   •   ${f.width}×${f.height}   •   ${formatSize(f.sizeBytes)}", 11f).apply {
            setTextColor(Color.rgb(156, 168, 186)); setPadding(dp(48), 0, 0, dp(5))
        })
        row.setOnClickListener { cb.isChecked = !cb.isChecked }
        listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun addCalibrationChoices() {
        addSection("CALIBRATION MASTERS", "Optional DARK / FLAT / BIAS frames. Keep them matched to the light frames.")
        addChoice("Dark master", FrameType.DARK) { darkPath = it }
        addChoice("Flat master", FrameType.FLAT) { flatPath = it }
        addChoice("Bias master", FrameType.BIAS) { biasPath = it }
    }

    private fun addChoice(labelText: String, type: FrameType, apply: (String?) -> Unit) {
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = card(); setPadding(dp(10), dp(4), dp(10), dp(4)) }
        box.addView(label(labelText, 13f), LinearLayout.LayoutParams(0, dp(48), 1f))
        val framesForType = ProjectRepository.listFrames(this).filter { it.type == type }.sortedByDescending { File(it.path).lastModified() }
        val opts = mutableListOf("None").apply { addAll(framesForType.map { it.name }) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@GalleryActivity, android.R.layout.simple_spinner_dropdown_item, opts)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    apply(if (pos == 0) null else framesForType.getOrNull(pos - 1)?.path)
                }
            }
        }
        box.addView(spinner, LinearLayout.LayoutParams(dp(160), dp(48)))
        listBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun updateSelectionUi() {
        val count = checks.count { it.isChecked }
        selectedText.text = "$count selected"
        stackButton.isEnabled = count >= 2
        stackButton.alpha = if (count >= 2) 1f else .45f
        stackButton.text = if (count >= 2) "SMART STACK • $count FRAMES" else "SELECT AT LEAST 2 FRAMES"
    }

    private fun startStack() {
        val selected = frames.filterIndexed { idx, _ -> idx < checks.size && checks[idx].isChecked }
        if (selected.size < 2) return
        val first = selected.first()
        if (selected.any { it.width != first.width || it.height != first.height || it.cfa != first.cfa }) {
            Toast.makeText(this, "Selected frames must have matching sensor dimensions/CFA.", Toast.LENGTH_LONG).show(); return
        }
        startActivity(Intent(this, ProcessingActivity::class.java).apply {
            putStringArrayListExtra("lights", ArrayList(selected.map { it.path }))
            putExtra("w", first.width); putExtra("h", first.height); putExtra("cfa", first.cfa)
            putExtra("dark", darkPath ?: ""); putExtra("flat", flatPath ?: ""); putExtra("bias", biasPath ?: "")
        })
    }

    private fun formatExp(ns: Long) = if (ns >= 1_000_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9) else String.format(Locale.US, "%.0fms", ns / 1e6)
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
