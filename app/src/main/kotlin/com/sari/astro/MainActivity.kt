package com.sari.astro

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import com.sari.astro.ui.CameraOverlay
import com.sari.astro.ui.SariKit
import com.sari.astro.ui.SariPalette
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sari.astro.camera.CameraCaps
import com.sari.astro.camera.CameraProbe
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var preview:TextureView;private lateinit var status:TextView;private lateinit var isoText:TextView;private lateinit var exposureText:TextView;private lateinit var rawText:TextView;private lateinit var captureButton:Button;private lateinit var focusButton:Button;private lateinit var projectButton:Button
    private lateinit var cameraManager:CameraManager;private val cameraThread=HandlerThread("SARI-Astro-Camera");private lateinit var cameraHandler:Handler;private var writer:ExecutorService?=null
    @Volatile private var camera:CameraDevice?=null;@Volatile private var session:CameraCaptureSession?=null;private var rawReader:ImageReader?=null;private var previewSurface:Surface?=null;private var previewSize:Size?=null;@Volatile private var caps:CameraCaps?=null;@Volatile private var cameraChars:CameraCharacteristics?=null;private var opening=false;private var started=false
    private val pendingLock=Any();private val pendingImages=LinkedHashMap<Long,Image>();private val pendingResults=LinkedHashMap<Long,TotalCaptureResult>();private val outstanding=AtomicInteger(0);@Volatile private var lastCaptureAtMs=0L;private val savedFrames=AtomicInteger(0);private val failedFrames=AtomicInteger(0)
    private var iso=1600;private var exposureNs=10_000_000_000L;@Volatile private var savedSessionDir:File?=null;private var manualFocus=true;@Volatile private var sequenceRunning=false;private var sequenceStarted=0L;private var captureKind=ProjectRepository.FrameType.LIGHT;private val MAX_SEQUENCE_MS=240_000L
    private fun intervalMs()=max(3000L,exposureNs/1_000_000L+750L)
    private val sequenceTick=object:Runnable{override fun run(){if(!sequenceRunning)return;val elapsed=SystemClock.elapsedRealtime()-sequenceStarted;if(elapsed>=MAX_SEQUENCE_MS){stopSequence(true);return};captureFrame();cameraHandler.postDelayed(this,intervalMs());ui{updateSequenceUi()}}}
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.CAMERA]==true||hasCameraPermission()){if(started&&preview.isAvailable)openBackCamera()}else Toast.makeText(this,"Camera permission is required.",Toast.LENGTH_LONG).show()}
    override fun onCreate(b:Bundle?){super.onCreate(b);WindowCompat.setDecorFitsSystemWindows(window,false);buildUi();cameraManager=getSystemService(CameraManager::class.java);cameraThread.start();cameraHandler=Handler(cameraThread.looper);preview.surfaceTextureListener=object:TextureView.SurfaceTextureListener{override fun onSurfaceTextureAvailable(s:SurfaceTexture,w:Int,h:Int){if(started&&hasCameraPermission())openBackCamera()}override fun onSurfaceTextureSizeChanged(s:SurfaceTexture,w:Int,h:Int)=applyPreviewTransform();override fun onSurfaceTextureDestroyed(s:SurfaceTexture)=true;override fun onSurfaceTextureUpdated(s:SurfaceTexture)=Unit}}
    override fun onStart(){super.onStart();started=true;if(!hasCameraPermission()){val p=mutableListOf(Manifest.permission.CAMERA);if(Build.VERSION.SDK_INT<=28)p+=Manifest.permission.WRITE_EXTERNAL_STORAGE;permissionLauncher.launch(p.toTypedArray())}else if(preview.isAvailable)openBackCamera()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt();private fun ui(block:()->Unit){if(isFinishing||isDestroyed)return;runOnUiThread{if(!isFinishing&&!isDestroyed)block()}}
    private fun tv(t:String,s:Float,b:Boolean=false)=TextView(this).apply{text=t;textSize=s;setTextColor(Color.WHITE);if(b)setTypeface(typeface,android.graphics.Typeface.BOLD)}
    private fun pill(t:String)=tv(t,12f).apply{gravity=Gravity.CENTER;setPadding(dp(8),0,dp(8),0);background=com.sari.astro.ui.SariKit(context).surface(0xB30B1220.toInt(),10,0x334FD8F2)}
    private fun button(t:String)=Button(this).apply{text=t;textSize=12f;isAllCaps=false;minHeight=0;minimumHeight=0;setTextColor(Color.WHITE);background=com.sari.astro.ui.SariKit(context).surface(0xE6131C2E.toInt(),12,0x335B6B8C)}
    private lateinit var isoSlider:SeekBar;private lateinit var expSlider:SeekBar;private lateinit var isoValueView:TextView;private lateinit var expValueView:TextView;private lateinit var controlsPanel:LinearLayout;private lateinit var panelButton:Button;private var syncingControls=false
    private fun buildUi(){
        val kit=SariKit(this)
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        preview=TextureView(this)
        root.addView(preview,FrameLayout.LayoutParams(-1,-1))
        root.addView(CameraOverlay(this),FrameLayout.LayoutParams(-1,-1))

        // Top: brand (always left-to-right) and live status cards.
        val top=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(8))}
        val brandRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_LTR}
        brandRow.addView(View(this).apply{background=kit.surface(SariPalette.ACCENT,3,null)},LinearLayout.LayoutParams(dp(6),dp(6)).apply{rightMargin=dp(8)})
        brandRow.addView(tv("SARI",15f,true).apply{letterSpacing=.16f})
        brandRow.addView(tv("ASTRO",15f,true).apply{letterSpacing=.16f;setTextColor(SariPalette.ACCENT)},LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(6)})
        top.addView(brandRow)
        status=tv("CAMERA • STARTING",11f).apply{setTextColor(SariPalette.TEXT_MUTED);setPadding(0,dp(2),0,0)}
        top.addView(status)
        val cards=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(10),0,0)}
        rawText=statusCard(kit,"RAW — CHECKING");isoText=statusCard(kit,"ISO —");exposureText=statusCard(kit,"EXP —")
        cards.addView(rawText,LinearLayout.LayoutParams(0,dp(34),1f).apply{rightMargin=dp(4)})
        cards.addView(isoText,LinearLayout.LayoutParams(0,dp(34),1f).apply{leftMargin=dp(4);rightMargin=dp(4)})
        cards.addView(exposureText,LinearLayout.LayoutParams(0,dp(34),1f).apply{leftMargin=dp(4)})
        top.addView(cards)
        root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))

        // Bottom: collapsible control panel plus the main shutter row.
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(12),dp(8),dp(12),dp(14))}
        controlsPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=kit.surface(0xE60B1220.toInt(),18,0x335B6B8C);setPadding(dp(14),dp(12),dp(14),dp(8));visibility=View.GONE}
        val isoPair=sliderBlock(kit,"ISO"){p->isoPresets().getOrNull(p)?.let{iso=it;updateTexts()}}
        isoSlider=isoPair.first;isoValueView=isoPair.second
        val expPair=sliderBlock(kit,"EXPOSURE"){p->exposurePresets().getOrNull(p)?.let{exposureNs=it;updateTexts()}}
        expSlider=expPair.first;expValueView=expPair.second
        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        focusButton=button("∞ FOCUS").apply{setOnClickListener{toggleFocus()}}
        val calBtn=button("CALIBRATE").apply{setOnClickListener{chooseCalibration()}}
        tools.addView(focusButton,LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(5)})
        tools.addView(calBtn,LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(5)})
        controlsPanel.addView(tools,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        controls.addView(controlsPanel,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})

        val main=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL}
        projectButton=button("GALLERY").apply{setOnClickListener{openGallery()}}
        panelButton=button("CONTROLS").apply{setOnClickListener{togglePanel()}}
        captureButton=Button(this).apply{
            text="";isAllCaps=false;minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0
            setPadding(0,0,0,0);gravity=Gravity.CENTER
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(0xFF0C1322.toInt());setStroke(dp(3),SariPalette.ACCENT)}
            contentDescription="Start Astro"
            setOnClickListener{toggleSequence()}
        }
        main.addView(projectButton,LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(10)})
        main.addView(captureButton,LinearLayout.LayoutParams(dp(84),dp(84)))
        main.addView(panelButton,LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(10)})
        controls.addView(main,LinearLayout.LayoutParams(-1,-2))
        setAstroButtonState(false)
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root){_,i->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(dp(16)+bars.left,dp(10)+bars.top,dp(16)+bars.right,dp(8))
            controls.setPadding(dp(12)+bars.left,dp(8),dp(12)+bars.right,dp(14)+bars.bottom)
            i
        }
        syncControls()
        setContentView(root)
    }

    private fun statusCard(kit:SariKit,text:String):TextView=kit.text(text,11f,SariPalette.TEXT,true).apply{
        gravity=Gravity.CENTER
        background=kit.surface(0xCC0C1322.toInt(),10,0x335B6B8C)
    }

    private fun sliderBlock(kit:SariKit,title:String,onPick:(Int)->Unit):Pair<SeekBar,TextView>{
        val block=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(8))}
        val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        head.addView(kit.section(title),LinearLayout.LayoutParams(0,-2,1f))
        val value=kit.text("--",13f,SariPalette.ACCENT,true)
        head.addView(value)
        block.addView(head)
        val bar=SeekBar(this).apply{
            progressTintList=ColorStateList.valueOf(SariPalette.ACCENT)
            thumbTintList=ColorStateList.valueOf(SariPalette.ACCENT)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){if(fromUser&&!syncingControls)onPick(p)}
                override fun onStartTrackingTouch(s:SeekBar?)=Unit
                override fun onStopTrackingTouch(s:SeekBar?)=Unit
            })
        }
        block.addView(bar,LinearLayout.LayoutParams(-1,dp(48)))
        controlsPanel.addView(block)
        return Pair(bar,value)
    }

    private fun togglePanel(){
        val open=controlsPanel.visibility!=View.VISIBLE
        controlsPanel.visibility=if(open)View.VISIBLE else View.GONE
        panelButton.text=if(open)"HIDE" else "CONTROLS"
    }

    /** ISO presets offered by the connected camera (same list the cycle button uses). */
    private fun isoPresets():List<Int>{
        val r=caps?.isoRange?:return emptyList()
        return listOf(100,200,400,800,1600,3200,6400).filter{it in r}
    }

    /** Exposure presets offered by the connected camera (same list the cycle button uses). */
    private fun exposurePresets():List<Long>{
        val r=caps?.exposureNs?:return emptyList()
        val mf=caps?.maxFrameDurationNs
        val presets=mutableListOf(500_000_000L,1_000_000_000L,2_000_000_000L,4_000_000_000L,5_000_000_000L,6_000_000_000L,7_000_000_000L,8_000_000_000L,10_000_000_000L,12_000_000_000L,15_000_000_000L)
        val maxSupported=mf?.let{minOf(it,r.last)}?:r.last
        if(maxSupported>15_000_000_000L)presets+=maxSupported
        return presets.distinct().filter{it in r&&(mf==null||it<=mf)}.sorted()
    }

    /** Keeps slider positions and value labels aligned with the real camera values. */
    private fun syncControls(){
        if(!::isoSlider.isInitialized||!::expSlider.isInitialized)return
        syncingControls=true
        try{
            val isos=isoPresets()
            isoSlider.max=max(0,isos.size-1)
            isoSlider.progress=isos.indices.minByOrNull{kotlin.math.abs(isos[it]-iso)}?:0
            isoSlider.isEnabled=isos.size>1
            isoValueView.text="$iso"
            val exps=exposurePresets()
            expSlider.max=max(0,exps.size-1)
            expSlider.progress=exps.indices.minByOrNull{kotlin.math.abs(exps[it]-exposureNs)}?:0
            expSlider.isEnabled=exps.size>1
            expValueView.text=if(exposureNs>=1_000_000_000L)String.format(Locale.US,"%.1fs",exposureNs/1e9)else String.format(Locale.US,"%.0fms",exposureNs/1e6)
        }finally{syncingControls=false}
    }

    private fun setAstroButtonState(active:Boolean){
        captureButton.isSelected=active
        captureButton.setCompoundDrawablesWithIntrinsicBounds(0,if(active)com.sari.astro.R.drawable.ic_stop_square else com.sari.astro.R.drawable.ic_shutter,0,0)
        (captureButton.background as? GradientDrawable)?.setStroke(dp(3),if(active)SariPalette.VIOLET else SariPalette.ACCENT)
        captureButton.contentDescription=if(active)"Stop Astro" else "Start Astro"
    }

    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun openBackCamera(){if(camera!=null||opening)return;val selected=CameraProbe.probe(this).firstOrNull{it.facingBack}?:CameraProbe.probe(this).firstOrNull()?:run{status.text="No camera";return};caps=selected;cameraChars=runCatching{cameraManager.getCameraCharacteristics(selected.id)}.getOrNull();if(cameraChars==null){status.text="Camera unavailable";return};status.text=if(selected.usableForRawAstro)"ASTRO • ${selected.id}" else "ASTRO LIMITED • ${selected.id}";rawText.text=if(selected.rawSupported)"RAW SENSOR • READY" else "RAW UNSUPPORTED";captureButton.isEnabled=selected.usableForRawAstro;selected.isoRange?.let{iso=iso.coerceIn(it.first,it.last)};selected.exposureNs?.let{r->val u=selected.maxFrameDurationNs?.let{m->minOf(r.last,m)}?:r.last;exposureNs=exposureNs.coerceIn(r.first,max(r.first,u))};updateTexts();writer=Executors.newSingleThreadExecutor{r->Thread(r,"SARI-Writer")};opening=true;runCatching{cameraManager.openCamera(selected.id,stateCallback,cameraHandler)}.onFailure{opening=false;status.text="Camera could not be opened";Toast.makeText(this,it.message?:"Camera error",Toast.LENGTH_LONG).show()}}
    private val stateCallback=object:CameraDevice.StateCallback(){override fun onOpened(c:CameraDevice){opening=false;if(!started){c.close();return};camera=c;createSession()}override fun onDisconnected(c:CameraDevice){opening=false;c.close();camera=null;session=null}override fun onError(c:CameraDevice,e:Int){opening=false;c.close();camera=null;session=null;ui{status.text="Camera error $e"}}}
    private fun choosePreviewSize(map:android.hardware.camera2.params.StreamConfigurationMap,target:Size?):Size{val sizes=map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty();val limit=1280L*960L;val ratio=target?.let{it.width.toDouble()/it.height}?:4.0/3.0;val ok=sizes.filter{it.width.toLong()*it.height<=limit};return ok.minWithOrNull(compareBy<Size>{kotlin.math.abs(it.width.toDouble()/it.height-ratio)}.thenByDescending{it.width.toLong()*it.height})?:sizes.minByOrNull{it.width.toLong()*it.height}?:Size(1280,720)}
    private fun createSession(){val c=camera?:return;val tex=preview.surfaceTexture?:return;val map=cameraChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:return;try{rawReader?.close();val rawSize=if(caps?.rawSupported==true)map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull{it.width.toLong()*it.height}else null;val ps=choosePreviewSize(map,rawSize);previewSize=ps;tex.setDefaultBufferSize(ps.width,ps.height);applyPreviewTransform();previewSurface?.release();previewSurface=Surface(tex);if(rawSize!=null){val r=ImageReader.newInstance(rawSize.width,rawSize.height,ImageFormat.RAW_SENSOR,2);r.setOnImageAvailableListener({ rr -> runCatching { rr.acquireNextImage()?.let(::onRaw) } }, cameraHandler);rawReader=r};val surfaces=mutableListOf(previewSurface!!);rawReader?.let{surfaces+=it.surface};c.createCaptureSession(surfaces,object:CameraCaptureSession.StateCallback(){override fun onConfigured(s:CameraCaptureSession){if(camera==null){s.close();return};session=s;updatePreview()}override fun onConfigureFailed(s:CameraCaptureSession){ui{status.text="Camera session failed"}}},cameraHandler)}catch(t:Throwable){ui{status.text="Camera session failed: ${t.message}"}}}
    private fun applyPreviewTransform(){val ps=previewSize?:return;val vw=preview.width.toFloat();val vh=preview.height.toFloat();if(vw<=0||vh<=0)return;val ba=minOf(ps.width,ps.height).toFloat()/max(ps.width,ps.height);var dw=vw;var dh=vw/ba;if(dh>vh){dh=vh;dw=vh*ba};preview.setTransform(Matrix().apply{setScale(dw/vw,dh/vh,vw/2f,vh/2f)})}
    private fun updatePreview(){val c=camera?:return;val s=session?:return;val surf=previewSurface?:return;runCatching{val q=c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{addTarget(surf);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);set(CaptureRequest.CONTROL_AF_MODE,supportedAfMode());set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)};s.setRepeatingRequest(q.build(),null,cameraHandler)}}
    private fun supportedAfMode():Int{val m=cameraChars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?:intArrayOf();return when{m.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)->CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE;m.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO)->CameraCharacteristics.CONTROL_AF_MODE_AUTO;else->CameraCharacteristics.CONTROL_AF_MODE_OFF}}
    private fun captureFrame(){val c=camera?:return;val s=session?:return;val raw=rawReader?:return;if(outstanding.get()>0){if(SystemClock.elapsedRealtime()-lastCaptureAtMs>exposureNs/1_000_000L+10000){outstanding.set(0);clearPending()}else{Toast.makeText(this,"Previous frame is still saving.",Toast.LENGTH_SHORT).show();return}};clearPending();val af=manualFocus&&caps?.manualFocus==true;try{val req=c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply{addTarget(raw.surface);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);set(CaptureRequest.SENSOR_SENSITIVITY,iso);set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposureNs);set(CaptureRequest.SENSOR_FRAME_DURATION,exposureNs);set(CaptureRequest.CONTROL_AF_MODE,if(af)CameraCharacteristics.CONTROL_AF_MODE_OFF else supportedAfMode());if(af)set(CaptureRequest.LENS_FOCUS_DISTANCE,0f)};outstanding.incrementAndGet();lastCaptureAtMs=SystemClock.elapsedRealtime();s.capture(req.build(),object:CameraCaptureSession.CaptureCallback(){override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,res:TotalCaptureResult)=onResult(res);override fun onCaptureFailed(s:CameraCaptureSession,r:CaptureRequest,f:CaptureFailure){releaseOutstanding();failedFrames.incrementAndGet();ui{updateSequenceUi()}}},cameraHandler)}catch(t:Throwable){releaseOutstanding();ui{if(!sequenceRunning)setAstroButtonState(false);Toast.makeText(this,t.message?:"Capture failed",Toast.LENGTH_LONG).show()}}}
    private fun releaseOutstanding(){outstanding.updateAndGet{if(it>0)it-1 else 0}}
    private fun clearPending(){synchronized(pendingLock){pendingImages.values.forEach{runCatching{it.close()}};pendingImages.clear();pendingResults.clear()}}
    private fun onResult(result:TotalCaptureResult){val ts=result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)?:0L;val img=synchronized(pendingLock){pendingImages.remove(ts)?.also{}?:run{pendingResults[ts]=result;null}};if(img!=null)dispatchWrite(img,result)}
    private fun onRaw(image:Image){val res=synchronized(pendingLock){pendingResults.remove(image.timestamp)?:run{pendingImages[image.timestamp]=image;null}};if(res!=null)dispatchWrite(image,res)}
    private fun exifOrientation()=when(cameraChars?.get(CameraCharacteristics.SENSOR_ORIENTATION)){90->6;180->3;270->8;else->1}
    private fun dispatchWrite(image:Image,result:TotalCaptureResult){
        val ch=cameraChars
        val w=writer
        if(ch==null||w==null){
            image.close()
            releaseOutstanding()
            return
        }

        val kind=captureKind

        try{
            w.execute{
                var ok=false

                try{
                    val cfa=ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                    val neutral=result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                        ?.map{it.toFloat()}
                        ?.toFloatArray()

                    val targetSession=savedSessionDir ?: ProjectRepository.newSession(
                        this,
                        image.width,
                        image.height,
                        cfa,
                        neutral
                    ).dir.also{
                        savedSessionDir=it
                    }

                    val saved=ProjectRepository.saveRawFrame(
                        this,
                        image,
                        result,
                        ch,
                        kind,
                        targetSession
                    )

                    ok=if(kind==ProjectRepository.FrameType.LIGHT){
                        saved!=null&&writeDng(
                            ch,
                            result,
                            image,
                            exifOrientation(),
                            targetSession
                        )
                    }else{
                        saved!=null
                    }
                }finally{
                    image.close()
                    releaseOutstanding()
                }

                if(ok)savedFrames.incrementAndGet()
                else failedFrames.incrementAndGet()

                ui{updateSequenceUi()}
            }
        }catch(_:RejectedExecutionException){
            image.close()
            releaseOutstanding()
            ui{updateSequenceUi()}
        }
    }

    private fun writeDng(ch:CameraCharacteristics,result:TotalCaptureResult,image:Image,orientation:Int,sessionDir:File?):Boolean{val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"SARI_Astro_${System.currentTimeMillis()}.dng");put(MediaStore.Images.Media.MIME_TYPE,"image/x-adobe-dng");if(Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,if(sessionDir!=null)ProjectRepository.mediaStoreRelativePath(sessionDir,"RAW") else "Pictures/SARI Astro/RAW");put(MediaStore.Images.Media.IS_PENDING,1)}};val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return false;return try{val creator=DngCreator(ch,result);try{creator.setOrientation(orientation);contentResolver.openOutputStream(uri)?.use{creator.writeImage(it,image)}?:throw IllegalStateException("DNG stream unavailable")}finally{creator.close()};if(Build.VERSION.SDK_INT>=29)contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);true}catch(t:Throwable){runCatching{contentResolver.delete(uri,null,null)};ui{Toast.makeText(this,"DNG could not be saved: ${t.message}",Toast.LENGTH_LONG).show()};false}}
    private fun cycleIso(){val r=caps?.isoRange?:return;val c=listOf(100,200,400,800,1600,3200,6400).filter{it in r};if(c.isEmpty())return;iso=c[(c.indexOf(iso)+1)%c.size];updateTexts()}
    private fun cycleExposure(){val r=caps?.exposureNs?:return;val mf=caps?.maxFrameDurationNs;val presets=mutableListOf(500_000_000L,1_000_000_000L,2_000_000_000L,4_000_000_000L,5_000_000_000L,6_000_000_000L,7_000_000_000L,8_000_000_000L,10_000_000_000L,12_000_000_000L,15_000_000_000L);val maxSupported=mf?.let{minOf(it,r.last)}?:r.last;if(maxSupported>15_000_000_000L)presets+=maxSupported;val c=presets.distinct().filter{it in r&&(mf==null||it<=mf)}.sorted();if(c.isEmpty())return;val idx=c.indexOf(exposureNs);exposureNs=c[if(idx<0)0 else (idx+1)%c.size];updateTexts()}
    private fun toggleFocus(){
        if (caps?.manualFocus != true) {
            focusButton.text = "AUTO FOCUS"
            return
        }
        manualFocus = !manualFocus
        focusButton.text = if (manualFocus) "∞ FOCUS" else "AUTO FOCUS"
    }
    private fun toggleSequence(){if(sequenceRunning){stopSequence(false);return};savedSessionDir=null;captureKind=ProjectRepository.FrameType.LIGHT;savedFrames.set(0);failedFrames.set(0);sequenceStarted=SystemClock.elapsedRealtime();sequenceRunning=true;setAstroButtonState(true);status.text="ASTRO • RUNNING • MAX 04:00";cameraHandler.post(sequenceTick)}
    private fun stopSequence(auto:Boolean){sequenceRunning=false;cameraHandler.removeCallbacks(sequenceTick);setAstroButtonState(false);val frames=savedFrames.get();val integration=frames*(exposureNs/1_000_000_000.0);status.text=String.format(Locale.US,if(auto)"ASTRO • 04:00 LIMIT • %d frames • %.1fs integration" else "ASTRO • STOPPED • %d frames • %.1fs integration",frames,integration)}
    private fun updateSequenceUi(){
        val e = if (sequenceStarted == 0L) 0 else SystemClock.elapsedRealtime() - sequenceStarted
        val mins = e / 60000
        val sec = (e / 1000) % 60
        val frames = savedFrames.get()
        val integration = frames * (exposureNs / 1_000_000_000.0)

        captureButton.contentDescription = if (captureKind != ProjectRepository.FrameType.LIGHT) {
            "Capture " + captureKind.name
        } else if (sequenceRunning) {
            "Stop Astro"
        } else {
            "Start Astro"
        }

        if (sequenceRunning) {
            val remaining = ((MAX_SEQUENCE_MS - e).coerceAtLeast(0L) / 1000L)
            status.text = String.format(
                Locale.US,
                "ASTRO • %02d:%02d • %d frames • %.1fs integrated • %02d:%02d left",
                mins, sec, frames, integration, remaining / 60, remaining % 60
            )
        }

        updateTexts()
    }
    private fun updateTexts(){syncControls();isoText.text="ISO $iso";exposureText.text="EXP ${if(exposureNs>=1_000_000_000L)String.format(Locale.US,"%.1fs",exposureNs/1e9)else String.format(Locale.US,"%.0fms",exposureNs/1e6)}"}
    private fun chooseCalibration(){if(sequenceRunning){Toast.makeText(this,"Stop the sequence first.",Toast.LENGTH_SHORT).show();return};val names=arrayOf("LIGHT (normal)","DARK","FLAT","BIAS");AlertDialog.Builder(this).setTitle("Capture type").setItems(names){_,which->captureKind=when(which){1->ProjectRepository.FrameType.DARK;2->ProjectRepository.FrameType.FLAT;3->ProjectRepository.FrameType.BIAS;else->ProjectRepository.FrameType.LIGHT};captureButton.contentDescription="Capture " + captureKind.name;status.text=if(captureKind==ProjectRepository.FrameType.LIGHT)"ASTRO • LIGHT FRAME" else "CALIBRATION • ${captureKind.name}"}.show()}
    private fun openGallery(){startActivity(Intent(this,GalleryActivity::class.java))}
    private fun closeCamera() {
        sequenceRunning = false
        ui{setAstroButtonState(false)}

        runCatching {
            cameraHandler.removeCallbacksAndMessages(null)
        }
        runCatching {
            session?.stopRepeating()
        }
        runCatching {
            session?.abortCaptures()
        }

        session?.close()
        session = null

        camera?.close()
        camera = null
        opening = false

        val currentWriter = writer
        writer = null

        if (currentWriter != null) {
            currentWriter.shutdown()
            try {
                if (!currentWriter.awaitTermination(3, TimeUnit.SECONDS)) {
                    currentWriter.shutdownNow()
                }
            } catch (_: InterruptedException) {
                currentWriter.shutdownNow()
            }
        }

        clearPending()
        outstanding.set(0)

        rawReader?.close()
        rawReader = null

        previewSurface?.release()
        previewSurface = null
    }
    override fun onStop(){started=false;closeCamera();super.onStop()};override fun onDestroy(){closeCamera();cameraThread.quitSafely();super.onDestroy()}
}
