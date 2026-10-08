package com.sari.astro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import com.sari.astro.nativebridge.DeviceProfile
import com.sari.astro.nativebridge.NativeCore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ProcessingActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var pause: Button
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
    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var cancelRequested = false
    private var startTile = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        lights = intent.getStringArrayListExtra("lights")?.toTypedArray() ?: emptyArray()
        w = intent.getIntExtra("w", 0); h = intent.getIntExtra("h", 0); cfa = intent.getIntExtra("cfa", 0)
        dark = intent.getStringExtra("dark")?.takeIf { it.isNotBlank() }
        flat = intent.getStringExtra("flat")?.takeIf { it.isNotBlank() }
        bias = intent.getStringExtra("bias")?.takeIf { it.isNotBlank() }
        projectDir = intent.getStringExtra("projectDir")?.let(::File)
        buildUi(); prepare()
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun btn(t:String)=Button(this).apply{text=t;isAllCaps=false;minHeight=0;minimumHeight=0;setTextColor(Color.WHITE)}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));setBackgroundColor(Color.rgb(8,12,19))}
        root.addView(TextView(this).apply{text="SARI Astro • Smart Stack";textSize=22f;setTextColor(Color.WHITE);setTypeface(typeface,1)})
        status=TextView(this).apply{textSize=14f;setTextColor(Color.LTGRAY);setPadding(0,dp(12),0,dp(12))};root.addView(status)
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100};root.addView(bar,LinearLayout.LayoutParams(-1,dp(18)))
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER}
        pause=btn("PAUSE").apply{isEnabled=false;setOnClickListener{togglePause()}}
        row.addView(pause,LinearLayout.LayoutParams(0,dp(54),1f))
        row.addView(btn("CANCEL").apply{setOnClickListener{cancelRun()}},LinearLayout.LayoutParams(0,dp(54),1f).apply{leftMargin=dp(7)})
        root.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        root.addView(TextView(this).apply{text="The master stays full-resolution F32. Registration, photometric normalization and robust outlier rejection run before the final conservative non-generative denoise/export stage.";setTextColor(Color.rgb(145,158,176));setPadding(0,dp(14),0,0)})
        setContentView(root)
    }

    private fun prepare(){
        if(lights.size<2||w<=0||h<=0){status.text="Not enough frames to stack.";return}
        val d=DeviceProfile.read(this);val mode=NativeCore.recommendMode(d);val plan=NativeCore.planStack(w,h,lights.size,d,mode)
        status.text = "${modeName(mode)} • ${d.totalRam / 1048576}MB RAM • ${d.cores} cores\n" + if (plan == null) "Native engine unavailable." else "Tile ${plan.tile}px • ${plan.workers} workers • budget ${plan.budgetMb}MB • peak≈${plan.peakMb}MB"
        if(plan==null||!plan.feasible){status.append("\nThis stack is above the safe memory budget.");return}
        val dir=projectDir ?: ProjectRepository.latestSession(this)?.dir ?: File(filesDir,"projects").apply{mkdirs()}
        output=File(dir,"results/stack-${System.currentTimeMillis()}.f32");output.parentFile?.mkdirs();progress=File(dir,"results/${output.nameWithoutExtension}.progress");report=File(dir,"results/${output.nameWithoutExtension}.report")
        pause.isEnabled=true;startRun(plan.tile,plan.workers)
    }

    private fun startRun(tile:Int,workers:Int){
        running=true;cancelRequested=false;pause.text="PAUSE"
        ex.submit{
            val r=NativeCore.stackProject(lights,w,h,dark,flat,bias,output.absolutePath,progress.absolutePath,report.absolutePath,tile,workers,startTile)
            ui.post{
                running=false
                if(r==null){pause.isEnabled=true;status.text="Native processing failed.";return@post}
                if(r.cancelled&&!cancelRequested){startTile=r.tilesDone;pause.isEnabled=true;pause.text="RESUME";status.text="Paused • ${r.tilesDone}/${r.tilesTotal} tiles";return@post}
                if(r.completed){pause.isEnabled=true;pause.text="DONE";status.text="Stack complete • ${r.includedFrames}/${r.inputFrames} frames • ${r.tilesDone}/${r.tilesTotal} tiles";startActivity(Intent(this,EditorActivity::class.java).apply{putExtra("path",output.absolutePath);putExtra("w",w);putExtra("h",h);putExtra("cfa",cfa);putExtra("report",report.absolutePath)});return@post}
                pause.isEnabled=true;if(cancelRequested)output.delete();status.text="Processing ended with native status ${r.status}"
            }
        }
        poll()
    }

    private fun poll(){ui.postDelayed({
        val line=if(::progress.isInitialized&&progress.exists())progress.readText().trim().split(',')else emptyList()
        if(line.size>=3){val done=line[0].toIntOrNull()?:0;val total=line[1].toIntOrNull()?:0;val sec=line[2].toDoubleOrNull()?:0.0;bar.progress=if(total>0)done*100/total else 0;val eta=if(done>0&&total>done)sec/done*(total-done)else-1.0;status.text = "Stacking $done/$total • elapsed ${fmt(sec)}" + if (eta >= 0) " • ETA ${fmt(eta)}" else ""}
        if(running)poll()
    },500)}

    private fun togglePause(){if(running){cancelRequested=false;NativeCore.cancelStack();pause.isEnabled=false;status.text="Pausing after the current tile…"}else if(startTile>0){val d=DeviceProfile.read(this);val plan=NativeCore.planStack(w,h,lights.size,d,NativeCore.recommendMode(d));if(plan!=null)startRun(plan.tile,plan.workers)}}
    private fun cancelRun(){cancelRequested=true;NativeCore.cancelStack();if(!running)finish()else status.text="Cancelling after current tile…"}
    private fun modeName(m:Int)=when(m){0->"SAFE";1->"BALANCED";2->"PRO";else->"SAFE"}
    private fun fmt(v:Double)=if(v<60)String.format(Locale.US,"%.0fs",v)else String.format(Locale.US,"%dm %02ds",(v/60).toInt(),(v%60).toInt())
    override fun onDestroy(){if(running)NativeCore.cancelStack();ex.shutdownNow();ui.removeCallbacksAndMessages(null);super.onDestroy()}
}
