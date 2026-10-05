package com.sari.astro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.sari.astro.ProjectRepository.FrameType

class GalleryActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private var frames = emptyList<ProjectRepository.FrameInfo>()
    private val checks = mutableListOf<CheckBox>()

    override fun onCreate(state: Bundle?) { super.onCreate(state); buildUi(); load() }
    private fun dp(v:Int)= (v*resources.displayMetrics.density+.5f).toInt()
    private fun button(t:String)=Button(this).apply{text=t;isAllCaps=false;minHeight=0;minimumHeight=0}
    private fun buildUi() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));setBackgroundColor(Color.rgb(11,15,26))}
        val title=TextView(this).apply{text="Astro Gallery";textSize=22f;setTextColor(Color.WHITE);setTypeface(typeface,1)}
        root.addView(title,LinearLayout.LayoutParams(-1,-2))
        val nav=LinearLayout(this).apply{gravity=Gravity.CENTER}
        nav.addView(button("Camera").apply{setOnClickListener{finish()}},LinearLayout.LayoutParams(0,dp(46),1f))
        nav.addView(button("Projects").apply{setOnClickListener{startActivity(Intent(this@GalleryActivity,ProjectsActivity::class.java))}},LinearLayout.LayoutParams(0,dp(46),1f))
        nav.addView(button("Settings").apply{setOnClickListener{startActivity(Intent(this@GalleryActivity,SettingsActivity::class.java))}},LinearLayout.LayoutParams(0,dp(46),1f))
        root.addView(nav,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(this).apply{addView(listBox)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8)})
        val actions=LinearLayout(this).apply{gravity=Gravity.CENTER}
        actions.addView(button("Refresh").apply{setOnClickListener{load()}},LinearLayout.LayoutParams(0,dp(52),1f))
        actions.addView(button("Select All Lights").apply{setOnClickListener{checks.forEach{it.isChecked=true}}},LinearLayout.LayoutParams(0,dp(52),1f))
        actions.addView(button("STACK SELECTED").apply{setOnClickListener{startStack()}},LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        setContentView(root)
    }
    private fun load() {
        frames=ProjectRepository.listFrames(this).filter{it.type==FrameType.LIGHT}.sortedBy{it.name}
        checks.clear();listBox.removeAllViews()
        if(frames.isEmpty()){listBox.addView(TextView(this).apply{text="No LIGHT RAW frames yet. Return to Camera and capture a sequence.";textSize=16f;setTextColor(Color.LTGRAY);setPadding(8,16,8,16)});return}
        val sorted=frames
        sorted.forEachIndexed { idx,f ->
            val cb=CheckBox(this).apply{isChecked=true;text="${f.name}  • ISO ${f.iso}  • ${f.width}×${f.height}  • ${formatExp(f.exposureNs)}";setTextColor(Color.WHITE);textSize=13f}
            checks+=cb; listBox.addView(cb,LinearLayout.LayoutParams(-1,-2))
        }
        addCalibrationChoices()
    }
    private var darkPath:String?=null; private var flatPath:String?=null; private var biasPath:String?=null
    private fun addCalibrationChoices(){
        fun addChoice(label:String,type:FrameType,apply:(String?)->Unit){
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};row.addView(TextView(this).apply{text=label;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,-2,1f))
            val s=Spinner(this);val opts=mutableListOf("None");ProjectRepository.listFrames(this).filter{it.type==type}.sortedByDescending{it.name}.forEach{opts+=it.name}
            s.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,opts);s.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){}override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){val path=if(pos==0)null else ProjectRepository.listFrames(this@GalleryActivity).firstOrNull{it.type==type&&it.name==opts[pos]}?.path;apply(path)}}
            row.addView(s,LinearLayout.LayoutParams(0,dp(46),1f));listBox.addView(row)
        }
        addChoice("Dark master",FrameType.DARK){darkPath=it};addChoice("Flat master",FrameType.FLAT){flatPath=it};addChoice("Bias master",FrameType.BIAS){biasPath=it}
    }
    private fun startStack(){
        val selected=frames.filterIndexed{idx,_ -> idx<checks.size&&checks[idx].isChecked}
        if(selected.size<2){Toast.makeText(this,"Select at least two LIGHT frames.",Toast.LENGTH_LONG).show();return}
        val first=selected.first();if(selected.any{it.width!=first.width||it.height!=first.height||it.cfa!=first.cfa}){Toast.makeText(this,"Selected frames must have matching sensor dimensions/CFA.",Toast.LENGTH_LONG).show();return}
        val i=Intent(this,ProcessingActivity::class.java).apply{putStringArrayListExtra("lights",ArrayList(selected.map{it.path}));putExtra("w",first.width);putExtra("h",first.height);putExtra("cfa",first.cfa);putExtra("dark",darkPath ?: "");putExtra("flat",flatPath ?: "");putExtra("bias",biasPath ?: "")};startActivity(i)
    }
    private fun formatExp(ns:Long)=if(ns>=1_000_000_000L)String.format("%.1fs",ns/1e9) else String.format("%.0fms",ns/1e6)
}
