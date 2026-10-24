package com.sari.astro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.sari.astro.nativebridge.DeviceProfile
import com.sari.astro.nativebridge.NativeCore
import com.sari.astro.ui.SariKit
import com.sari.astro.ui.SariPalette
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ProcessingActivity : Activity() {
    private lateinit var kit: SariKit
    private lateinit var deviceText: TextView
    private lateinit var phaseText: TextView
    private lateinit var detailText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var framesText: TextView
    private lateinit var noticeText: TextView
    private lateinit var pauseBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var openBtn: Button

    private lateinit var lights: Array<String>
    private var w = 0
    private var h = 0
    private var cfa = 0
    private var dark: String? = null
    private var flat: String? = null
    private var bias: String? = null
    private var projectDir: File? = null
    private lateinit var output: File
    private lateinit var progress: File
    private lateinit var report: File

    private val ex = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // The tile size is fixed for the whole job. Resuming must reuse it, because
    // the saved resume index is a tile index and changes meaning with tile size.
    private var tile = 0
    private var workers = 1
    private var mode = 0
    private var startTile = 0
    private var running = false
    private var finished = false
    private var cancelRequested = false
    private var throttleRequested = false
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            updateFromProgress()
            tickCount++
            if (tickCount % 3 == 0) checkDevice()
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        lights = intent.getStringArrayListExtra("lights")?.toTypedArray() ?: emptyArray()
        w = intent.getIntExtra("w", 0)
        h = intent.getIntExtra("h", 0)
        cfa = intent.getIntExtra("cfa", 0)
        dark = intent.getStringExtra("dark")?.takeIf { it.isNotBlank() }
        flat = intent.getStringExtra("flat")?.takeIf { it.isNotBlank() }
        bias = intent.getStringExtra("bias")?.takeIf { it.isNotBlank() }
        projectDir = intent.getStringExtra("projectDir")?.let(::File)
        kit = SariKit(this)
        buildUi()
        prepare()
    }

    private fun lp(bottomDp: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(bottomDp) }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(SariPalette.BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(18), kit.dp(22), kit.dp(18), kit.dp(22))
        }

        root.addView(kit.text("Stacking", 24f, bold = true))
        root.addView(
            kit.text("Full-resolution master • registration, normalisation, robust rejection", 12f, SariPalette.TEXT_MUTED)
                .apply { setPadding(0, kit.dp(4), 0, kit.dp(16)) }
        )

        val deviceCard = kit.card()
        deviceCard.addView(kit.section("Device"))
        deviceText = kit.text("Reading device…", 13f, SariPalette.TEXT_MUTED).apply { setPadding(0, kit.dp(6), 0, 0) }
        deviceCard.addView(deviceText)
        root.addView(deviceCard, lp(12))

        val progressCard = kit.card()
        progressCard.addView(kit.section("Progress"))
        phaseText = kit.text("Preparing…", 18f, bold = true).apply { setPadding(0, kit.dp(6), 0, kit.dp(10)) }
        progressCard.addView(phaseText)
        bar = kit.progressBar()
        progressCard.addView(bar, LinearLayout.LayoutParams(-1, kit.dp(8)))
        detailText = kit.text("", 12f, SariPalette.TEXT_MUTED).apply { setPadding(0, kit.dp(10), 0, 0) }
        progressCard.addView(detailText)
        root.addView(progressCard, lp(12))

        val framesCard = kit.card()
        framesCard.addView(kit.section("Frames"))
        framesText = kit.text("", 14f).apply {
            setPadding(0, kit.dp(6), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        framesCard.addView(framesText)
        root.addView(framesCard, lp(12))

        noticeText = kit.text("", 12f, SariPalette.WARNING).apply {
            setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(12))
            visibility = View.GONE
        }
        root.addView(noticeText, lp(0))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = kit.button("Pause", primary = true).apply {
            isEnabled = false
            setOnClickListener { togglePause() }
        }
        cancelBtn = kit.button("Cancel").apply { setOnClickListener { cancelRun() } }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, kit.dp(52), 1f))
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, kit.dp(52), 1f).apply { leftMargin = kit.dp(10) })
        root.addView(row, lp(10))

        openBtn = kit.button("Open result", primary = true).apply {
            visibility = View.GONE
            setOnClickListener { openResult() }
        }
        root.addView(openBtn, LinearLayout.LayoutParams(-1, kit.dp(52)))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun prepare() {
        if (lights.size < 2 || w <= 0 || h <= 0) {
            fail("Not enough frames", "Select at least two light frames with valid dimensions.")
            return
        }
        val d = DeviceProfile.read(this)
        mode = NativeCore.recommendMode(d)
        val plan = NativeCore.planStack(w, h, lights.size, d, mode)
        deviceText.text = "${modeName(mode)} profile • ${d.totalRam / 1048576} MB RAM • ${d.cores} cores • ${thermalName(d.thermal)}"
        if (plan == null) {
            fail("Native engine unavailable", "The stacking library could not be loaded on this device.")
            return
        }
        if (!plan.feasible) {
            fail(
                "Not enough memory",
                "This stack needs about ${plan.peakMb} MB but the safe budget is ${plan.budgetMb} MB. Use fewer frames or a smaller crop."
            )
            return
        }
        tile = plan.tile
        workers = plan.workers
        deviceText.append("\nTile ${tile} px • ${workers} worker(s) • budget ${plan.budgetMb} MB • peak ≈ ${plan.peakMb} MB")
        framesText.text = "${lights.size} light frames queued.\nAccepted and rejected counts appear when the stack completes."

        val dir = projectDir ?: ProjectRepository.latestSession(this)?.dir ?: File(filesDir, "projects").apply { mkdirs() }
        output = File(dir, "results/stack-${System.currentTimeMillis()}.f32")
        output.parentFile?.mkdirs()
        val base = output.nameWithoutExtension
        progress = File(dir, "results/$base.progress")
        report = File(dir, "results/$base.report")
        startRun()
    }

    private fun fail(title: String, message: String) {
        phaseText.text = title
        detailText.text = message
        pauseBtn.isEnabled = false
        pauseBtn.alpha = 0.4f
        cancelBtn.text = "Close"
    }

    private fun startRun() {
        running = true
        cancelRequested = false
        throttleRequested = false
        pauseBtn.isEnabled = true
        pauseBtn.alpha = 1f
        pauseBtn.text = "Pause"
        cancelBtn.text = "Cancel"
        phaseText.text = if (startTile > 0) "Resuming…" else "Analysing frames…"
        detailText.text = "Aligning and normalising ${lights.size} frames"

        val jobTile = tile
        val jobWorkers = workers
        val jobStart = startTile
        val jobCfa = cfa
        ex.submit {
            val r = NativeCore.stackProject(
                lights, w, h, dark, flat, bias,
                output.absolutePath, progress.absolutePath, report.absolutePath,
                jobTile, jobWorkers, jobStart, jobCfa
            )
            handler.post { onRunFinished(r) }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 600)
    }

    private fun onRunFinished(r: NativeCore.StackResult?) {
        if (isFinishing || isDestroyed) return
        running = false
        when {
            r == null -> {
                discardOutput()
                fail("Native processing failed", "The engine stopped unexpectedly. Your frames are untouched; start again to retry.")
            }
            r.completed -> showDone(r)
            r.cancelled && cancelRequested -> {
                discardOutput()
                finish()
            }
            r.cancelled && throttleRequested -> {
                // Same tile size, fewer workers: the saved tile index stays valid.
                workers = 1
                startTile = r.tilesDone
                throttleRequested = false
                notice("Device is hot or low on memory. Continuing on one worker to protect it.")
                startRun()
            }
            r.cancelled -> {
                startTile = r.tilesDone
                setPaused(r)
            }
            r.status == OUT_OF_MEMORY && tile > MIN_TILE -> {
                // Allocation failed: restart from scratch with smaller tiles instead of crashing.
                discardOutput()
                tile = maxOf(MIN_TILE, tile / 2)
                workers = 1
                startTile = 0
                notice("Memory limit reached. Retrying with ${tile} px tiles.")
                startRun()
            }
            else -> {
                discardOutput()
                fail("Stacking stopped", "${statusHint(r.status)} (native status ${r.status}).")
            }
        }
    }

    private fun setPaused(r: NativeCore.StackResult) {
        phaseText.text = "Paused"
        detailText.text = "Tile ${r.tilesDone} of ${r.tilesTotal} saved. Resume continues from the same tile."
        pauseBtn.text = "Resume"
        pauseBtn.isEnabled = true
        pauseBtn.alpha = 1f
        cancelBtn.isEnabled = true
    }

    private fun showDone(r: NativeCore.StackResult) {
        finished = true
        bar.progress = bar.max
        phaseText.text = "Stack complete"
        detailText.text = "${r.tilesTotal} tiles • ${w}×${h} full resolution"

        val rejected = r.inputFrames - r.includedFrames
        val breakdown = parseRejections()
        framesText.text = buildString {
            append("Accepted ${r.includedFrames} of ${r.inputFrames} frames")
            if (rejected > 0) {
                append("\nRejected $rejected")
                breakdown.forEach { (reason, count) -> append("\n$count × ${reasonLabel(reason)}") }
            }
        }
        pauseBtn.visibility = View.GONE
        cancelBtn.visibility = View.GONE
        openBtn.visibility = View.VISIBLE
    }

    private fun updateFromProgress() {
        if (!::progress.isInitialized || !progress.exists()) return
        val parts = runCatching { progress.readText().trim().split(',') }.getOrNull() ?: return
        if (parts.size < 3) return
        val done = parts[0].toIntOrNull() ?: 0
        val total = parts[1].toIntOrNull() ?: 0
        val sec = parts[2].toDoubleOrNull() ?: 0.0
        if (total <= 0) return
        bar.progress = (done * 1000L / total).toInt()
        val pct = done * 100 / total
        phaseText.text = if (done > 0) "Stacking $done of $total tiles" else "Analysing frames…"
        val eta = if (done > 0 && total > done) sec / done * (total - done) else -1.0
        detailText.text = "$pct% • elapsed ${fmt(sec)}" + if (eta >= 0) " • about ${fmt(eta)} left" else ""
    }

    /** Throttle to one worker when the device overheats or is nearly out of RAM. */
    private fun checkDevice() {
        if (!running || cancelRequested || throttleRequested || workers <= 1) return
        val d = DeviceProfile.read(this)
        val hot = d.thermal >= 2
        val lowMemory = d.availRam < 160L * 1048576L
        if (hot || lowMemory) {
            throttleRequested = true
            pauseBtn.isEnabled = false
            NativeCore.cancelStack()
            notice("Pausing briefly to protect the device…")
        }
    }

    private fun togglePause() {
        if (running) {
            cancelRequested = false
            NativeCore.cancelStack()
            pauseBtn.isEnabled = false
            phaseText.text = "Pausing…"
            detailText.text = "Finishing the current tile, then pausing."
        } else if (!finished && startTile > 0) {
            startRun()
        }
    }

    private fun cancelRun() {
        if (running) {
            cancelRequested = true
            NativeCore.cancelStack()
            cancelBtn.isEnabled = false
            phaseText.text = "Cancelling…"
            detailText.text = "Finishing the current tile."
        } else {
            discardOutput()
            finish()
        }
    }

    private fun openResult() {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra("path", output.absolutePath)
            putExtra("w", w)
            putExtra("h", h)
            putExtra("cfa", cfa)
            putExtra("report", report.absolutePath)
        })
    }

    private fun parseRejections(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        if (!::report.isInitialized || !report.isFile) return counts
        runCatching { report.readLines() }.getOrNull()?.forEach { line ->
            if (!line.startsWith("frame=")) return@forEach
            val included = Regex("included=(\\d)").find(line)?.groupValues?.get(1) == "1"
            if (included) return@forEach
            val reason = Regex("reason=(\\S+)").find(line)?.groupValues?.get(1) ?: "unknown"
            counts[reason] = (counts[reason] ?: 0) + 1
        }
        return counts
    }

    private fun discardOutput() {
        if (::output.isInitialized) output.delete()
    }

    private fun notice(message: String) {
        noticeText.text = message
        noticeText.visibility = View.VISIBLE
    }

    private fun reasonLabel(reason: String) = when (reason) {
        "not-enough-stars" -> "frames with too few stars"
        "low-quality" -> "frames with low quality score"
        "registration-failed" -> "frames that failed alignment"
        "registration-rms-too-high" -> "frames with alignment error too high"
        "registration-confidence-low" -> "frames with low alignment confidence"
        else -> reason
    }

    private fun statusHint(status: Int) = when (status) {
        1 -> "Invalid input"
        2 -> "No usable stars found"
        3 -> "Alignment failed"
        4 -> "Not enough memory"
        5 -> "File read or write error"
        else -> "Unexpected error"
    }

    private fun modeName(m: Int) = when (m) {
        0 -> "Safe"
        1 -> "Balanced"
        2 -> "Pro"
        else -> "Safe"
    }

    private fun thermalName(t: Int) = when (t) {
        0 -> "cool"
        1 -> "warm"
        2 -> "hot"
        else -> "very hot"
    }

    private fun fmt(v: Double) =
        if (v < 60) String.format(Locale.US, "%.0fs", v)
        else String.format(Locale.US, "%dm %02ds", (v / 60).toInt(), (v % 60).toInt())

    override fun onDestroy() {
        if (running) NativeCore.cancelStack()
        handler.removeCallbacksAndMessages(null)
        ex.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val OUT_OF_MEMORY = 4
        const val MIN_TILE = 128
    }
}
