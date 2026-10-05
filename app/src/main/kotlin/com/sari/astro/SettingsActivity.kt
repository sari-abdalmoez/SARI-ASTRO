package com.sari.astro
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.sari.astro.nativebridge.DeviceProfile
import com.sari.astro.nativebridge.NativeCore
class SettingsActivity:Activity(){
 override fun onCreate(s:Bundle?){super.onCreate(s);val d=DeviceProfile.read(this);val mode=NativeCore.recommendMode(d);val t="Device profile\nRAM: ${d.totalRam/1048576} MB total / ${d.availRam/1048576} MB available\nCPU cores: ${d.cores}\nThermal level: ${d.thermal}\nApp memory class: ${d.memoryClassMb} MB\n\nNative: ${NativeCore.version()}\nRecommended processing: ${when(mode){0->"SAFE";1->"BALANCED";2->"PRO";else->"SAFE"}}\n\nThe build does not enable generative detail synthesis. Planetary/drizzle modes are not exposed unless their native algorithms are actually present.";setContentView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(Color.rgb(11,15,26));addView(TextView(this@SettingsActivity).apply{text="SARI Astro • Settings";textSize=22f;setTextColor(Color.WHITE);setTypeface(typeface,1)});addView(TextView(this@SettingsActivity).apply{text=t;setTextColor(Color.LTGRAY);textSize=15f;setPadding(0,18,0,0)})})}
}
