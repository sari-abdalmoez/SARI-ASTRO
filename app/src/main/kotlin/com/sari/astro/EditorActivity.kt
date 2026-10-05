package com.sari.astro

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import com.sari.astro.nativebridge.NativeCore
import java.io.File
import java.nio.ByteBuffer

class EditorActivity:Activity(){
    private lateinit var image:ImageView;private lateinit var seek:SeekBar;private var path="";private var w=0;private var h=0;private var cfa=0;private var mode=1
    private val ex=java.util.concurrent.Executors.newSingleThreadExecutor()
    override fun onCreate(s:Bundle?){super.onCreate(s);path=intent.getStringExtra("path")? :"";w=intent.getIntExtra("w",0);h=intent.getIntExtra("h",0);cfa=intent.getIntExtra("cfa",0);buildUi();render()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun btn(t:String)=Button(this).apply{text=t;isAllCaps=false;minHeight=0;minimumHeight=0}
    private fun buildUi(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);setPadding(dp(8),dp(8),dp(8),dp(8))};image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER};root.addView(image,LinearLayout.LayoutParams(-1,0,1f));val modes=LinearLayout(this).apply{gravity=Gravity.CENTER};modes.addView(btn("COLOR").apply{setOnClickListener{mode=1;render()}},LinearLayout.LayoutParams(0,dp(46),1f));modes.addView(btn("MONO").apply{setOnClickListener{mode=0;render()}},LinearLayout.LayoutParams(0,dp(46),1f));modes.addView(btn("CONSERVATIVE").apply{setOnClickListener{mode=2;render()}},LinearLayout.LayoutParams(0,dp(46),1f));root.addView(modes);seek=SeekBar(this).apply{max=90;progress=30;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)render()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?) {}})};root.addView(seek,LinearLayout.LayoutParams(-1,dp(48)));val actions=LinearLayout(this).apply{gravity=Gravity.CENTER};actions.addView(btn("SAVE JPEG").apply{setOnClickListener{saveJpeg()}},LinearLayout.LayoutParams(0,dp(52),1f));actions.addView(btn("EXPORT F32").apply{setOnClickListener{exportF32()}},LinearLayout.LayoutParams(0,dp(52),1f));root.addView(actions);setContentView(root)}
    private fun render(){if(path.isBlank())return;val stretch=1f+seek.progress/10f;ex.submit{val p=NativeCore.renderPreview(path,w,h,cfa,mode,stretch,1600);runOnUiThread{if(p==null)return@runOnUiThread;val pixels=IntArray(p.width*p.height);val b=ByteBuffer.wrap(p.rgba);for(i in pixels.indices){val r=b.get().toInt() and 255;val g=b.get().toInt() and 255;val bl=b.get().toInt() and 255;val a=b.get().toInt() and 255;pixels[i]=(a shl 24) or (r shl 16) or (g shl 8) or bl};image.setImageBitmap(Bitmap.createBitmap(pixels,p.width,p.height,Bitmap.Config.ARGB_8888))}}}
    private fun saveJpeg(){val b=image.drawable?:return;val bmp=(b as? android.graphics.drawable.BitmapDrawable)?.bitmap?:return;val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"SARI_Astro_result_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(android.os.Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SARI Astro/Results");put(MediaStore.Images.Media.IS_PENDING,1)}};val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return;runCatching{contentResolver.openOutputStream(uri)?.use{bmp.compress(Bitmap.CompressFormat.JPEG,95,it)};if(android.os.Build.VERSION.SDK_INT>=29)contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show()}.onFailure{contentResolver.delete(uri,null,null)}}
    private fun exportF32(){val intent=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply{type="application/octet-stream";putExtra(android.content.Intent.EXTRA_TITLE,File(path).name)};startActivityForResult(intent,31)}
    override fun onActivityResult(r:Int,c:Int,d:android.content.Intent?){super.onActivityResult(r,c,d);if(r==31&&c==RESULT_OK&&d?.data!=null){val ok=ProjectRepository.copyFileToUri(this,File(path),d.data!!);Toast.makeText(this,if(ok)"Exported"else"Export failed",Toast.LENGTH_SHORT).show()}}
    override fun onDestroy(){ex.shutdownNow();super.onDestroy()}
}
