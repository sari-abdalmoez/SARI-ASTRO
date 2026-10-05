package com.sari.astro
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.File

class ProjectsActivity:Activity(){
 override fun onCreate(s:Bundle?){super.onCreate(s);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(Color.rgb(11,15,26))};root.addView(TextView(this).apply{text="SARI Astro • Projects";textSize=22f;setTextColor(Color.WHITE);setTypeface(typeface,1)});val scroll=ScrollView(this);val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val dirs=File(filesDir,"projects").listFiles{f->f.isDirectory&&f.name.startsWith("session-")}?.sortedByDescending{it.lastModified()}.orEmpty();if(dirs.isEmpty())box.addView(TextView(this).apply{text="No projects yet.";setTextColor(Color.LTGRAY);textSize=16f}) else dirs.forEach{d->val f=ProjectRepository.scan(d);val lights=f.count{it.type==ProjectRepository.FrameType.LIGHT};val cals=f.count{it.type!=ProjectRepository.FrameType.LIGHT};box.addView(TextView(this).apply{text="${d.name}\n$lights light • $cals calibration";setTextColor(Color.WHITE);textSize=15f;setPadding(8,12,8,12)})};scroll.addView(box);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));val row=LinearLayout(this).apply{gravity=Gravity.CENTER};row.addView(Button(this).apply{text="OPEN GALLERY";setOnClickListener{startActivity(Intent(this@ProjectsActivity,GalleryActivity::class.java))},LinearLayout.LayoutParams(0,52,1f));row.addView(Button(this).apply{text="BACK";setOnClickListener{finish()}},LinearLayout.LayoutParams(0,52,1f));root.addView(row);setContentView(root)}
}
