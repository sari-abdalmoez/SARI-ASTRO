package com.sari.astro

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sari.astro.camera.CameraProbe
import com.sari.astro.nativebridge.DeviceProfile
import com.sari.astro.nativebridge.NativeCore
import kotlinx.coroutines.*

/** Phase-1 shell: device/camera capability report + native memory planner. Heavy work runs off the main thread. */
class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setTextColor(0xFFE6EDF7.toInt()); typeface = Typeface.MONOSPACE; textSize = 13f; setPadding(32, 48, 32, 48); text = "Probing device…" }
        setContentView(ScrollView(this).apply { addView(LinearLayout(context).apply { addView(out, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }) })
        scope.launch {
            val report = withContext(Dispatchers.Default) {
                runCatching {
                    val d = DeviceProfile.read(applicationContext)
                    val mode = NativeCore.recommendMode(d)
                    val plan = NativeCore.planStack(4000, 3000, 100, d, mode)
                    buildString {
                        append("SARI ASTRO\nengine: ${NativeCore.version()}\n\n")
                        append("RAM ${d.totalRam shr 20} MB (avail ${d.availRam shr 20} MB)  cores ${d.cores}  thermal ${d.thermal}\n")
                        append("Recommended mode: ${listOf("SAFE", "BALANCED", "PRO")[mode.coerceIn(0, 2)]}\n")
                        append("100×12MP stack plan: ${plan?.let { "tile ${it.tile}, ${it.workers} workers, peak ${it.peakMb} MB / budget ${it.budgetMb} MB, feasible=${it.feasible}" } ?: "unavailable"}\n\n")
                        val cams = CameraProbe.probe(applicationContext)
                        if (cams.isEmpty()) append("No camera information available.\n") else cams.forEach { append(it.describe()).append('\n') }
                    }
                }.getOrElse { "Capability probe failed: ${it.javaClass.simpleName}: ${it.message}" }
            }
            out.text = report
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
