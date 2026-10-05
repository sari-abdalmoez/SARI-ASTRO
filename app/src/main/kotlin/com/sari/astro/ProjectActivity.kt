package com.sari.astro

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sari.astro.nativebridge.NativeCore
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

class ProjectActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var grid: GridLayout
    private lateinit var stack: Button
    private lateinit var empty: TextView
    private var projectDir: File? = null
    private var frames: List<ProjectRepository.FrameInfo> = emptyList()
    private val checked = LinkedHashSet<String>()
    private val work = Executors.newFixedThreadPool(2)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) importIntoCurrent(uris)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        projectDir = intent.getStringExtra("projectDir")?.let(::File)
        buildUi(); load()
    }

    override fun onResume() { super.onResume(); if (::grid.isInitialized) load() }
    override fun onDestroy() { work.shutdownNow(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun bg(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.rgb(41,54,71)) }
    private fun btn(t: String, primary: Boolean = false) = Button(this).apply { text=t;isAllCaps=false;minHeight=0;minimumHeight=0;setTextColor(Color.WHITE);background=bg(if(primary)Color.rgb(30,94,120)else Color.rgb(21,28,39)) }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(12));setBackgroundColor(Color.rgb(7,11,18)) }
        val header = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text="‹";textSize=28f;minWidth=dp(50);minHeight=0;setOnClickListener{finish()} }, LinearLayout.LayoutParams(dp(52),dp(48)))
        header.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(TextView(this@ProjectActivity).also{title=it;it.textSize=21f;it.setTextColor(Color.WHITE);it.setTypeface(it.typeface,1)});addView(TextView(this@ProjectActivity).also{summary=it;it.textSize=11f;it.setTextColor(Color.rgb(146,161,180))}) }, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(btn("RENAME").apply{setOnClickListener{rename()}},LinearLayout.LayoutParams(dp(88),dp(44)))
        root.addView(header)

        val actions = LinearLayout(this).apply { gravity=Gravity.CENTER }
        actions.addView(btn("IMPORT").apply{setOnClickListener{startImport()}},LinearLayout.LayoutParams(0,dp(46),1f))
        actions.addView(btn("MASTER").apply{setOnClickListener{openMaster()}},LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(5)})
        actions.addView(btn("SELECT ALL").apply{setOnClickListener{checked.clear();frames.filter{it.stackable}.forEach{checked+=it.path};load()}},LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(5)})
        actions.addView(btn("CLEAR").apply{setOnClickListener{checked.clear();load()}},LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(5)})
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})

        empty=TextView(this).apply{text="This project has no images yet.";textSize=14f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER;visibility=View.GONE}
        root.addView(empty,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        val scroll=ScrollView(this).apply{isFillViewport=true}
        grid=GridLayout(this).apply{columnCount=3;alignmentMode=GridLayout.ALIGN_BOUNDS}
        scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(7)})
        stack=btn("STACK SELECTED",true).apply{isEnabled=false;setOnClickListener{startStack()}}
        root.addView(stack,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(8)})
        setContentView(root)
    }

    private fun load() {
        val dir=projectDir ?: return
        if(!dir.isDirectory){Toast.makeText(this,"Project not found",Toast.LENGTH_LONG).show();finish();return}
        frames=ProjectRepository.scan(dir)
        checked.retainAll(frames.filter{it.stackable}.map{it.path}.toSet())
        val session=ProjectRepository.sessionForDir(dir)
        title.text=session.title
        val lights=frames.count{it.type==ProjectRepository.FrameType.LIGHT&&it.stackable}
        val imports=frames.count{it.type==ProjectRepository.FrameType.IMPORTED}
        val cals=frames.count{it.type==ProjectRepository.FrameType.DARK||it.type==ProjectRepository.FrameType.FLAT||it.type==ProjectRepository.FrameType.BIAS}
        summary.text="$lights RAW • $imports imports • $cals calibration" + if(session.width>0) " • ${session.width}×${session.height}" else ""
        grid.removeAllViews();empty.visibility=if(frames.isEmpty())View.VISIBLE else View.GONE
        for((index,f) in frames.withIndex()) addFrameCard(f,index)
        val count=checked.size
        stack.isEnabled=count>=2;stack.alpha=if(count>=2) 1f else .45f;stack.text=if(count>=2)"SMART STACK • $count" else "SELECT 2+ RAW FRAMES"
    }

    private fun addFrameCard(f: ProjectRepository.FrameInfo,index:Int){
        val wrap=FrameLayout(this).apply{background=bg(Color.rgb(15,21,30),12);setOnClickListener{if(f.stackable)toggle(f.path)}}
        val img=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;background=bg(Color.rgb(8,11,16),10)}
        wrap.addView(img,FrameLayout.LayoutParams(-1,dp(112)))
        val shade=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.BOTTOM;setPadding(dp(7),dp(4),dp(7),dp(6));setBackgroundColor(0xAA000000.toInt())}
        val name=TextView(this).apply{text=f.name;textSize=10f;setTextColor(Color.WHITE);maxLines=1}
        val meta=TextView(this).apply{text=metaText(f);textSize=9f;setTextColor(Color.rgb(192,204,219));maxLines=1}
        shade.addView(name);shade.addView(meta)
        wrap.addView(shade,FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM))
        val mark=TextView(this).apply{text="✓";textSize=17f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=bg(Color.rgb(30,94,120),20);visibility=if(checked.contains(f.path))View.VISIBLE else View.GONE}
        wrap.addView(mark,FrameLayout.LayoutParams(dp(30),dp(30),Gravity.TOP or Gravity.END).apply{setMargins(0,dp(6),dp(6),0)})
        val lp=GridLayout.LayoutParams().apply{width=0;height=dp(140);columnSpec=GridLayout.spec(index%3,1f);rowSpec=GridLayout.spec(index/3);setMargins(dp(3),dp(3),dp(3),dp(3))}
        grid.addView(wrap,lp)
        work.execute{
            val b=loadThumb(f)
            runOnUiThread{if(!isFinishing&&!isDestroyed&&b!=null)img.setImageBitmap(b)}
        }
    }

    private fun metaText(f: ProjectRepository.FrameInfo):String=when(f.type){
        ProjectRepository.FrameType.LIGHT -> "ISO ${f.iso} • ${exp(f.exposureNs)}" + (f.temperatureC?.let{" • %.1f°C".format(Locale.US,it)}?:"")
        ProjectRepository.FrameType.IMPORTED -> "Imported • ${f.width}×${f.height}"
        else -> f.type.name
    }
    private fun exp(ns:Long)=if(ns>=1_000_000_000L)String.format(Locale.US,"%.1fs",ns/1e9)else String.format(Locale.US,"%.0fms",ns/1e6)

    private fun loadThumb(f: ProjectRepository.FrameInfo):Bitmap?{
        if(f.type==ProjectRepository.FrameType.IMPORTED) return decodeThumb(File(f.path), 360)
        return runCatching{
            NativeCore.renderPreview(f.path,f.width,f.height,f.cfa,1,2.0f,280)?.let{p->
                val pixels=IntArray(p.width*p.height);val b=ByteBuffer.wrap(p.rgba)
                for(i in pixels.indices){val r=b.get().toInt()and 255;val g=b.get().toInt()and 255;val bl=b.get().toInt()and 255;val a=b.get().toInt()and 255;pixels[i]=(a shl 24)or(r shl 16)or(g shl 8)or bl}
                Bitmap.createBitmap(pixels,p.width,p.height,Bitmap.Config.ARGB_8888)
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

    private fun toggle(path:String){if(!checked.add(path))checked.remove(path);load()}

    private fun startStack(){
        val selected=frames.filter{it.stackable&&checked.contains(it.path)}
        if(selected.size<2)return
        val first=selected.first()
        if(selected.any{it.width!=first.width||it.height!=first.height||it.cfa!=first.cfa}){Toast.makeText(this,"RAW frames must have matching dimensions and CFA",Toast.LENGTH_LONG).show();return}
        val dir=projectDir ?: return
        val dark=ProjectRepository.latestCalibrationInProject(dir,ProjectRepository.FrameType.DARK)?.path ?: ""
        val flat=ProjectRepository.latestCalibrationInProject(dir,ProjectRepository.FrameType.FLAT)?.path ?: ""
        val bias=ProjectRepository.latestCalibrationInProject(dir,ProjectRepository.FrameType.BIAS)?.path ?: ""
        startActivity(Intent(this,ProcessingActivity::class.java).apply{putStringArrayListExtra("lights",ArrayList(selected.map{it.path}));putExtra("w",first.width);putExtra("h",first.height);putExtra("cfa",first.cfa);putExtra("dark",dark);putExtra("flat",flat);putExtra("bias",bias);putExtra("projectDir",dir.absolutePath)})
    }

    private fun openMaster(){
        val dir=projectDir ?: return
        val result=dir.resolve("results").listFiles{f->f.isFile&&f.extension.equals("f32",true)}?.maxByOrNull{it.lastModified()}
        val session=ProjectRepository.sessionForDir(dir)
        if(result==null || session.width<=0 || session.height<=0){Toast.makeText(this,"No completed master yet",Toast.LENGTH_SHORT).show();return}
        startActivity(Intent(this,EditorActivity::class.java).apply{putExtra("path",result.absolutePath);putExtra("w",session.width);putExtra("h",session.height);putExtra("cfa",session.cfa)})
    }

    private fun rename(){
        val input=EditText(this).apply{setSingleLine(true);setText(title.text);selectAll()}
        AlertDialog.Builder(this).setTitle("Rename project").setView(input).setNegativeButton("CANCEL",null).setPositiveButton("SAVE"){_,_->
            val ok=projectDir?.let{ProjectRepository.renameProject(it,input.text.toString())}==true
            Toast.makeText(this, if (ok) "Project renamed" else "Rename failed", Toast.LENGTH_SHORT).show();if(ok)load()
        }.show()
    }

    private fun startImport(){importLauncher.launch(arrayOf("image/*","image/x-adobe-dng","image/tiff"))}
    private fun importIntoCurrent(uris:List<android.net.Uri>){val dir=projectDir?:return;Toast.makeText(this,"Importing…",Toast.LENGTH_SHORT).show();work.execute{val r=ProjectRepository.importUris(this,dir,uris);runOnUiThread{load();Toast.makeText(this,"Imported ${r.imported} • failed ${r.errors.size}",Toast.LENGTH_LONG).show()}}}
}
