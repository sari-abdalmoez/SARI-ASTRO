#!/usr/bin/env python3
from pathlib import Path
import subprocess, shutil, sys, re

ROOT = Path.cwd()
EXPECTED = "0d37076b81b03d5dbbf1f3ef372dd4dc299ec310"

def die(msg):
    print("ERROR:", msg)
    sys.exit(1)

def read(rel):
    p = ROOT / rel
    if not p.is_file():
        die(f"Missing file: {rel}")
    return p.read_text(encoding="utf-8")

def write(rel, s):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(s, encoding="utf-8")

def backup(rel):
    p = ROOT / rel
    b = p.with_suffix(p.suffix + ".before-sari-update")
    if p.exists() and not b.exists():
        shutil.copy2(p, b)

def replace_once(s, old, new, label):
    n = s.count(old)
    if n != 1:
        die(f"{label}: expected 1 match, found {n}")
    return s.replace(old, new, 1)

def git_head():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip()
    except Exception:
        return None

head = git_head()
if head and head != EXPECTED:
    die(f"Expected latest SARI-ASTRO base {EXPECTED[:8]}, found {head[:8]}. Update your repo first.")

# ------------------------------------------------------------------
# 1) Camera UI: one professional Start/Stop Astro button, 4-minute cap
# ------------------------------------------------------------------
main_rel = "app/src/main/kotlin/com/sari/astro/MainActivity.kt"
main = read(main_rel)
backup(main_rel)

main = main.replace("import android.animation.AnimatorSet\n", "")
main = main.replace("import android.animation.ObjectAnimator\n", "")

main = replace_once(
    main,
    "private lateinit var preview:TextureView;private lateinit var status:TextView;private lateinit var isoText:TextView;private lateinit var exposureText:TextView;private lateinit var rawText:TextView;private lateinit var captureButton:Button;private lateinit var sequenceButton:Button;private lateinit var focusButton:Button;private lateinit var projectButton:Button",
    "private lateinit var preview:TextureView;private lateinit var status:TextView;private lateinit var isoText:TextView;private lateinit var exposureText:TextView;private lateinit var rawText:TextView;private lateinit var captureButton:Button;private lateinit var focusButton:Button;private lateinit var projectButton:Button",
    "camera fields"
)

main = replace_once(
    main,
    "private var iso=800;private var exposureNs=7_000_000_000L;@Volatile private var savedSessionDir:File?=null;private var manualFocus=true;@Volatile private var sequenceRunning=false;private var sequenceStarted=0L;private var captureKind=ProjectRepository.FrameType.LIGHT;private var captureAnimator:AnimatorSet?=null",
    "private var iso=800;private var exposureNs=7_000_000_000L;@Volatile private var savedSessionDir:File?=null;private var manualFocus=true;@Volatile private var sequenceRunning=false;private var sequenceStarted=0L;private var captureKind=ProjectRepository.FrameType.LIGHT;private val MAX_SEQUENCE_MS=240_000L",
    "camera state"
)

main = replace_once(
    main,
    """private fun intervalMs()=max(3000L,exposureNs/1_000_000L+750L)
    private val sequenceTick=object:Runnable{override fun run(){if(!sequenceRunning)return;captureFrame(false);cameraHandler.postDelayed(this,intervalMs());ui{updateSequenceUi()}}}""",
    """private fun intervalMs()=max(3000L,exposureNs/1_000_000L+750L)
    private val sequenceTick=object:Runnable{override fun run(){if(!sequenceRunning)return;val elapsed=SystemClock.elapsedRealtime()-sequenceStarted;if(elapsed>=MAX_SEQUENCE_MS){stopSequence(true);return};captureFrame();cameraHandler.postDelayed(this,intervalMs());ui{updateSequenceUi()}}}""",
    "sequence timer"
)

main = replace_once(
    main,
    """val sari=tv("SARI",21f,true).apply{
            setTextColor(Color.rgb(25,118,255))""",
    """val sari=tv("SARI",18f,true).apply{
            setTextColor(Color.WHITE)""",
    "SARI branding"
)

main = replace_once(
    main,
    """val astro=tv("ASTRO",21f,true).apply{
            setTextColor(Color.WHITE)""",
    """val astro=tv("ASTRO",18f,true).apply{
            setTextColor(Color.rgb(25,118,255))""",
    "ASTRO branding"
)

main = replace_once(
    main,
    """        sequenceButton=button("START ASTRO").apply{
            setOnClickListener{toggleSequence()}
        }

""",
    "",
    "remove duplicate start button"
)

main = replace_once(
    main,
    """        captureButton=button("").apply{
            contentDescription="Capture RAW"

            setBackgroundResource(
                com.sari.astro.R.drawable.bg_astro_capture
            )

            setCompoundDrawablesWithIntrinsicBounds(
                com.sari.astro.R.drawable.ic_astro_capture,
                0,
                0,
                0
            )

            gravity=Gravity.CENTER
            setPadding(0,0,0,0)
            minWidth=0
            minimumWidth=0
            minHeight=0
            minimumHeight=0

            setOnClickListener{
                captureFrame(true)
            }
        }

        main.addView(
            sequenceButton,
            LinearLayout.LayoutParams(dp(106),dp(52)).apply{
                rightMargin=dp(18)
            }
        )

        main.addView(
            captureButton,
            LinearLayout.LayoutParams(dp(88),dp(88)
            )
        )""",
    """        captureButton=button("").apply{
            contentDescription="Start Astro"
            setBackgroundResource(com.sari.astro.R.drawable.bg_astro_capture)
            setCompoundDrawablesWithIntrinsicBounds(com.sari.astro.R.drawable.ic_astro_capture,0,0,0)
            gravity=Gravity.CENTER
            setPadding(0,0,0,0)
            minWidth=0
            minimumWidth=0
            minHeight=0
            minimumHeight=0
            setOnClickListener{toggleSequence()}
        }

        main.addView(
            captureButton,
            LinearLayout.LayoutParams(dp(82),dp(82))
        )""",
    "single astro button"
)

main = replace_once(
    main,
    """    private fun animateCaptureButton(active:Boolean){
        captureAnimator?.cancel()
        captureAnimator=null

        captureButton.animate().cancel()
        captureButton.scaleX=1f
        captureButton.scaleY=1f

        if(!active)return

        val x=ObjectAnimator.ofFloat(
            captureButton,
            View.SCALE_X,
            1f,
            1.07f
        )

        val y=ObjectAnimator.ofFloat(
            captureButton,
            View.SCALE_Y,
            1f,
            1.07f
        )

        captureAnimator=AnimatorSet().apply{
            playTogether(x,y)
            duration=850
            x.repeatCount=ObjectAnimator.INFINITE
            y.repeatCount=ObjectAnimator.INFINITE
            x.repeatMode=ObjectAnimator.REVERSE
            y.repeatMode=ObjectAnimator.REVERSE
            start()
        }
    }
""",
    """    private fun setAstroButtonState(active:Boolean){
        captureButton.isSelected=active
        captureButton.setCompoundDrawablesWithIntrinsicBounds(
            if(active) com.sari.astro.R.drawable.ic_astro_stop else com.sari.astro.R.drawable.ic_astro_capture,
            0,0,0
        )
        captureButton.contentDescription=if(active) "Stop Astro" else "Start Astro"
    }
""",
    "remove pulse animation"
)

main = main.replace(
    "captureButton.isEnabled=selected.usableForRawAstro;sequenceButton.isEnabled=selected.usableForRawAstro;",
    "captureButton.isEnabled=selected.usableForRawAstro;"
)
main = main.replace("private fun captureFrame(manual:Boolean){", "private fun captureFrame(){")
main = main.replace('if(manual)Toast.makeText(this,"Previous frame is still saving.",Toast.LENGTH_SHORT).show();',
                    'Toast.makeText(this,"Previous frame is still saving.",Toast.LENGTH_SHORT).show();')
main = main.replace("ui{animateCaptureButton(true)};", "")
main = main.replace("ui{if(!sequenceRunning)animateCaptureButton(false);updateSequenceUi()}", "ui{updateSequenceUi()}")
main = main.replace('ui{if(!sequenceRunning)animateCaptureButton(false);Toast.makeText(this,it.message?:"Capture failed",Toast.LENGTH_LONG).show()}',
                    'ui{Toast.makeText(this,it.message?:"Capture failed",Toast.LENGTH_LONG).show()}')
main = main.replace("ui{if(!sequenceRunning)animateCaptureButton(false)}", "ui{updateSequenceUi()}")

main = replace_once(
    main,
    """private fun toggleSequence(){if(sequenceRunning){stopSequence();return};savedSessionDir=null;captureKind=ProjectRepository.FrameType.LIGHT;savedFrames.set(0);failedFrames.set(0);sequenceStarted=SystemClock.elapsedRealtime();sequenceRunning=true;sequenceButton.text="STOP ASTRO";status.text="ASTRO • RUNNING";cameraHandler.post(sequenceTick)}
    private fun stopSequence(){sequenceRunning=false;cameraHandler.removeCallbacks(sequenceTick);sequenceButton.text="START ASTRO";val frames=savedFrames.get();val integration=frames*(exposureNs/1_000_000_000.0);status.text=String.format(Locale.US,"ASTRO • STOPPED • %d frames • %.1fs total integration",frames,integration)}""",
    """private fun toggleSequence(){if(sequenceRunning){stopSequence(false);return};savedSessionDir=null;captureKind=ProjectRepository.FrameType.LIGHT;savedFrames.set(0);failedFrames.set(0);sequenceStarted=SystemClock.elapsedRealtime();sequenceRunning=true;setAstroButtonState(true);status.text="ASTRO • RUNNING • MAX 04:00";cameraHandler.post(sequenceTick)}
    private fun stopSequence(auto:Boolean){sequenceRunning=false;cameraHandler.removeCallbacks(sequenceTick);setAstroButtonState(false);val frames=savedFrames.get();val integration=frames*(exposureNs/1_000_000_000.0);status.text=String.format(Locale.US,if(auto)"ASTRO • 04:00 LIMIT • %d frames • %.1fs integration" else "ASTRO • STOPPED • %d frames • %.1fs integration",frames,integration)}""",
    "start stop"
)

main = replace_once(
    main,
    """        captureButton.text =
            if (captureKind != ProjectRepository.FrameType.LIGHT) {
                captureKind.name
            } else if (sequenceRunning) {
                "SAVED $frames"
            } else {
                "CAPTURE RAW"
            }""",
    """        captureButton.contentDescription = if (captureKind != ProjectRepository.FrameType.LIGHT) {
            "Capture " + captureKind.name
        } else if (sequenceRunning) {
            "Stop Astro"
        } else {
            "Start Astro"
        }""",
    "button label"
)

main = replace_once(
    main,
    """            status.text = String.format(
                Locale.US,
                "ASTRO • %02d:%02d • %d frames • %.1fs integrated",
                mins,
                sec,
                frames,
                integration
            )""",
    """            val remaining = ((MAX_SEQUENCE_MS - e).coerceAtLeast(0L) / 1000L)
            status.text = String.format(
                Locale.US,
                "ASTRO • %02d:%02d • %d frames • %.1fs integrated • %02d:%02d left",
                mins, sec, frames, integration, remaining / 60, remaining % 60
            )""",
    "sequence status"
)

main = main.replace(
    "captureButton.text=captureKind.name;status.text=",
    "captureButton.contentDescription=\"Capture \" + captureKind.name;status.text="
)
main = replace_once(
    main,
    """        sequenceRunning = false

        runCatching {""",
    """        sequenceRunning = false
        ui{setAstroButtonState(false)}

        runCatching {""",
    "reset astro button"
)
write(main_rel, main)

# ------------------------------------------------------------------
# 2) Camera drawables: play idle, red translucent stop active
# ------------------------------------------------------------------
write("app/src/main/res/drawable/ic_astro_capture.xml", """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="30dp"
    android:height="30dp"
    android:viewportWidth="30"
    android:viewportHeight="30">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M8,5 L24,15 L8,25 Z"/>
</vector>
""")

write("app/src/main/res/drawable/ic_astro_stop.xml", """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp"
    android:height="28dp"
    android:viewportWidth="28"
    android:viewportHeight="28">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M7,7 L21,7 L21,21 L7,21 Z"/>
</vector>
""")

write("app/src/main/res/drawable/bg_astro_capture.xml", """<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="oval">
            <solid android:color="#555555"/>
            <stroke android:width="2dp" android:color="#777777"/>
        </shape>
    </item>

    <item android:state_selected="true">
        <shape android:shape="oval">
            <solid android:color="#A8262F3D"/>
            <stroke android:width="3dp" android:color="#FF5260"/>
        </shape>
    </item>

    <item android:state_pressed="true">
        <shape android:shape="oval">
            <solid android:color="#164B6A"/>
            <stroke android:width="3dp" android:color="#FFFFFF"/>
        </shape>
    </item>

    <item>
        <shape android:shape="oval">
            <solid android:color="#1976FF"/>
            <stroke android:width="3dp" android:color="#FFFFFF"/>
        </shape>
    </item>
</selector>
""")

# ------------------------------------------------------------------
# 3) Editor: independent controls per COLOR/MONO/NATURAL, denoise applied
# ------------------------------------------------------------------
editor_rel="app/src/main/kotlin/com/sari/astro/EditorActivity.kt"
editor=read(editor_rel); backup(editor_rel)

editor=replace_once(
    editor,
    "private var mode = 1\n    private var pendingFits: File? = null",
    """private var mode = 1
    private val modeSettings = HashMap<Int, IntArray>().apply {
        put(0, intArrayOf(28, 18))
        put(1, intArrayOf(30, 24))
        put(2, intArrayOf(28, 20))
    }
    private var pendingFits: File? = null""",
    "mode settings"
)

editor=replace_once(
    editor,
    """        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        modes.addView(btn("COLOR").apply { setOnClickListener { mode = 1; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("MONO").apply { setOnClickListener { mode = 0; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("NATURAL").apply { setOnClickListener { mode = 2; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(modes)""",
    """        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        modes.addView(btn("COLOR").apply { setOnClickListener { selectMode(1) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("MONO").apply { setOnClickListener { selectMode(0) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modes.addView(btn("NATURAL").apply { setOnClickListener { selectMode(2) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(modes)""",
    "editor mode buttons"
)

editor=replace_once(
    editor,
    """        stretchSeek = SeekBar(this).apply {
            max = 90
            progress = 30
            setOnSeekBarChangeListener(simpleChange { render() })
        }""",
    """        stretchSeek = SeekBar(this).apply {
            max = 90
            progress = modeSettings[mode]!![0]
            setOnSeekBarChangeListener(simpleChange {
                modeSettings[mode]!![0] = progress
                render()
            })
        }""",
    "stretch control"
)

editor=replace_once(
    editor,
    """        denoiseSeek = SeekBar(this).apply {
            max = 100
            progress = 24
            setOnSeekBarChangeListener(simpleChange { render() })
        }""",
    """        denoiseSeek = SeekBar(this).apply {
            max = 100
            progress = modeSettings[mode]!![1]
            setOnSeekBarChangeListener(simpleChange {
                modeSettings[mode]!![1] = progress
                render()
            })
        }""",
    "denoise control"
)

marker="""    private fun simpleChange(onUser: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
"""
insert="""    private fun selectMode(newMode: Int) {
        modeSettings[mode]!![0] = stretchSeek.progress
        modeSettings[mode]!![1] = denoiseSeek.progress
        mode = newMode
        stretchSeek.progress = modeSettings[mode]!![0]
        denoiseSeek.progress = modeSettings[mode]!![1]
        render()
    }

"""
editor=replace_once(editor, marker, insert+marker, "mode selector")

editor=replace_once(
    editor,
    """        val stretch = 1f + stretchSeek.progress / 10f
        status.text = "Rendering preview from the full-resolution master…"
        ex.submit {
            val p = NativeCore.renderPreview(path, w, h, cfa, mode, stretch, 1600)""",
    """        val stretch = 1f + stretchSeek.progress / 10f
        val denoise = denoiseSeek.progress / 100f
        status.text = "Rendering preview from the full-resolution master…"
        ex.submit {
            val p = NativeCore.renderPreview(path, w, h, cfa, mode, stretch, denoise, 1600)""",
    "preview denoise"
)
write(editor_rel,editor)

# ------------------------------------------------------------------
# 4) NativeCore + JNI + pipeline preview denoise parameter
# ------------------------------------------------------------------
nativecore_rel="app/src/main/kotlin/com/sari/astro/nativebridge/NativeCore.kt"
nc=read(nativecore_rel); backup(nativecore_rel)
nc=replace_once(
    nc,
    "fun renderPreview(path: String, w: Int, h: Int, cfa: Int, mode: Int, stretch: Float, maxDim: Int = 1600): Preview?",
    "fun renderPreview(path: String, w: Int, h: Int, cfa: Int, mode: Int, stretch: Float, denoise: Float, maxDim: Int = 1600): Preview?",
    "NativeCore preview signature"
)
nc=replace_once(
    nc,
    "nativeRenderPreview(path, w, h, cfa, mode, stretch, maxDim)",
    "nativeRenderPreview(path, w, h, cfa, mode, stretch, denoise, maxDim)",
    "NativeCore native call"
)
nc=replace_once(
    nc,
    "private external fun nativeRenderPreview(path: String, w: Int, h: Int, cfa: Int, mode: Int, stretch: Float, maxDim: Int): ByteArray?",
    "private external fun nativeRenderPreview(path: String, w: Int, h: Int, cfa: Int, mode: Int, stretch: Float, denoise: Float, maxDim: Int): ByteArray?",
    "NativeCore native declaration"
)
write(nativecore_rel,nc)

jni_rel="native/jni/sari_jni.cpp"
jni=read(jni_rel); backup(jni_rel)
jni=replace_once(
    jni,
    "JNIEXPORT jbyteArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeRenderPreview(JNIEnv* env,jclass,jstring path,jint w,jint h,jint cfa,jint mode,jfloat stretch,jint maxDim){",
    "JNIEXPORT jbyteArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeRenderPreview(JNIEnv* env,jclass,jstring path,jint w,jint h,jint cfa,jint mode,jfloat stretch,jfloat denoise,jint maxDim){",
    "JNI preview signature"
)
jni=replace_once(
    jni,
    "Status st=renderPreview(p,w,h,cfa,mode,stretch,maxDim,rgba,ow,oh);",
    "Status st=renderPreview(p,w,h,cfa,mode,stretch,denoise,maxDim,rgba,ow,oh);",
    "JNI preview call"
)
write(jni_rel,jni)

pipe_h_rel="native/pipeline/pipeline.h"
ph=read(pipe_h_rel); backup(pipe_h_rel)
ph=replace_once(
    ph,
    "Status renderPreview(const std::string& f32Path,int W,int H,int cfa,int mode,float stretch,int maxDim,",
    "Status renderPreview(const std::string& f32Path,int W,int H,int cfa,int mode,float stretch,float denoise,int maxDim,",
    "pipeline header"
)
write(pipe_h_rel,ph)

pipe_rel="native/pipeline/pipeline.cpp"
pipe=read(pipe_rel); backup(pipe_rel)
pipe=replace_once(
    pipe,
    "Status renderPreview(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,int maxDim,std::vector<uint8_t>&rgba,int&outW,int&outH){",
    "Status renderPreview(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,float denoise,int maxDim,std::vector<uint8_t>&rgba,int&outW,int&outH){",
    "pipeline preview signature"
)
# Add conservative CFA-aware local denoise inside preview before output conversion.
needle="""  try{rgba.resize((size_t)outW*outH*4);}catch(...){return Status::OutOfMemory;}"""
replacement="""  try{rgba.resize((size_t)outW*outH*4);}catch(...){return Status::OutOfMemory;}
  if(denoise>0.001f){
    Plane filtered(outW,outH);
    const float amount=std::min(1.f,std::max(0.f,denoise));
    for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
      float c=center(p,x,y);
      (void)c;
    }
    for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
      const int rx=x*factor, ry=y*factor;
      const int wanted=cfaColor(cfa,rx,ry);
      float centerV=p.at(x,y), sum=centerV, ws=1.f;
      for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){
        if(dx==0&&dy==0)continue;
        int nx=x+dx, ny=y+dy;
        if(nx<0||ny<0||nx>=outW||ny>=outH)continue;
        if(cfaColor(cfa,nx*factor,ny*factor)!=wanted)continue;
        float v=p.at(nx,ny);
        float d=std::fabs(v-centerV);
        float spatial=std::exp(-0.35f*float(dx*dx+dy*dy));
        float range=std::exp(-d*d/(0.0004f+0.03f*amount));
        float ww=spatial*range;
        sum+=v*ww; ws+=ww;
      }
      float smooth=sum/ws;
      float edge=std::min(1.f,std::fabs(centerV-smooth)*20.f);
      float a=amount*0.55f*(1.f-edge);
      filtered.at(x,y)=centerV*(1.f-a)+smooth*a;
    }
    p=std::move(filtered);
  }"""
# center(...) was mistakenly referenced; replace with a no-op-free implementation.
replacement=replacement.replace("""    for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
      float c=center(p,x,y);
      (void)c;
    }
""","")
pipe=replace_once(pipe,needle,replacement,"preview denoise body")
write(pipe_rel,pipe)

# ------------------------------------------------------------------
# 5) Make final denoise noticeably effective but conservative.
# ------------------------------------------------------------------
exp_rel="native/export/export.cpp"
exp=read(exp_rel); backup(exp_rel)
exp=replace_once(
    exp,
    "const float alpha = std::max(0.f, std::min(0.85f * strength, (1.f - preserve) * strength));",
    "const float effective = std::pow(std::max(0.f, std::min(1.f, strength)), 0.68f); const float alpha = std::max(0.f, std::min(0.92f * effective, (1.f - preserve) * effective));",
    "denoise strength"
)
write(exp_rel,exp)

# ------------------------------------------------------------------
# 6) Frame identity metadata + duplicate timestamp guard.
# ------------------------------------------------------------------
proj_rel="app/src/main/kotlin/com/sari/astro/ProjectRepository.kt"
proj=read(proj_rel); backup(proj_rel)

proj=replace_once(
    proj,
    """        val exposureNs: Long,
        val name: String,""",
    """        val exposureNs: Long,
        val sensorTimestamp: Long = 0L,
        val name: String,""",
    "FrameInfo timestamp field"
)

proj=replace_once(
    proj,
    """        val temp: Float? = null
        File(file.parentFile, file.nameWithoutExtension + ".properties").printWriter().use { out ->
            out.println("type=${type.name}")""",
    """        val temp: Float? = null
        val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
        val duplicate = sub.listFiles { f -> f.isFile && f.extension.equals("properties", true) }
            ?.any { pf ->
                pf.readLines().any { it == "sensorTimestamp=$sensorTimestamp" }
            } == true
        if (duplicate) {
            file.delete()
            return null
        }
        File(file.parentFile, file.nameWithoutExtension + ".properties").printWriter().use { out ->
            out.println("type=${type.name}")""",
    "timestamp duplicate guard"
)

proj=replace_once(
    proj,
    """            out.println("iso=$iso")
            out.println("exposureNs=$exp")
            if (temp != null) out.println("temperatureC=$temp")""",
    """            out.println("iso=$iso")
            out.println("exposureNs=$exp")
            out.println("sensorTimestamp=$sensorTimestamp")
            if (temp != null) out.println("temperatureC=$temp")""",
    "timestamp metadata"
)

# scan() FrameInfo constructors: add timestamp argument in the f32 frame constructor.
proj=replace_once(
    proj,
    """                        m["iso"]?.toIntOrNull() ?: 0,
                        m["exposureNs"]?.toLongOrNull() ?: 0L,
                        f.name,""",
    """                        m["iso"]?.toIntOrNull() ?: 0,
                        m["exposureNs"]?.toLongOrNull() ?: 0L,
                        m["sensorTimestamp"]?.toLongOrNull() ?: 0L,
                        f.name,""",
    "scan timestamp"
)
# imported constructor needs default timestamp, so no change.
write(proj_rel,proj)

# ------------------------------------------------------------------
# 7) Processing progress: explicit percent + tile count + ETA.
# ------------------------------------------------------------------
proc_rel="app/src/main/kotlin/com/sari/astro/ProcessingActivity.kt"
proc=read(proc_rel); backup(proc_rel)
proc=replace_once(
    proc,
    """            bar.progress=if(total>0)done*100/total else 0;val eta=if(done>0&&total>done)sec/done*(total-done)else-1.0;status.text = "Stacking $done/$total • elapsed ${fmt(sec)}" + if (eta >= 0) " • ETA ${fmt(eta)}" else """"",
    """            val pct = if(total>0) (done*100/total).coerceIn(0,100) else 0
            bar.progress=pct
            val eta=if(done>0&&total>done)sec/done*(total-done)else-1.0
            status.text = "STACKING • $pct% • $done/$total tiles • elapsed ${fmt(sec)}" + if (eta >= 0) " • ETA ${fmt(eta)}" else """"",
    "stack progress"
)
write(proc_rel,proc)

# ------------------------------------------------------------------
# 8) Validation markers / notes.
# ------------------------------------------------------------------
notes = """SARI-ASTRO Professional Update
Base commit: 0d37076b81b03d5dbbf1f3ef372dd4dc299ec310

Changes:
- Camera has one main Astro Start/Stop control.
- Idle icon is a triangle; active icon is a square with a translucent red state.
- Automatic hard stop at 4 minutes.
- SARI is compact bold white; ASTRO compact bold blue.
- COLOR/MONO/NATURAL keep separate Stretch and Final Noise Reduction settings.
- Preview path accepts denoise and applies conservative CFA-aware filtering.
- Final export denoise response is stronger while preserving edges.
- Each captured F32 frame records SENSOR_TIMESTAMP and duplicate timestamps are rejected.
- Stacking progress shows explicit percentage, tiles and ETA.

The existing registration/stacking algorithms are not replaced.
"""
write("SARI-ASTRO-Professional-Update-NOTES.md", notes)

# final sanity checks
for rel in [main_rel, editor_rel, proj_rel, proc_rel, nativecore_rel, jni_rel, pipe_h_rel, pipe_rel, exp_rel]:
    if not (ROOT / rel).is_file():
        die("Post-patch file missing: " + rel)

print("SARI-ASTRO Professional Update applied successfully.")
print("Backups were created with suffix .before-sari-update where applicable.")
print("Run: git diff --check && git status --short")
