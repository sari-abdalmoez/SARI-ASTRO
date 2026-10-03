package com.sari.astro.nativebridge

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

data class DeviceProfile(val totalRam: Long, val availRam: Long, val cores: Int, val thermal: Int, val memoryClassMb: Int) {
    companion object {
        fun read(ctx: Context): DeviceProfile {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            var thermal = 0
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                thermal = when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> 0
                    PowerManager.THERMAL_STATUS_LIGHT -> 1
                    PowerManager.THERMAL_STATUS_MODERATE -> 2
                    else -> 3
                }
            }
            return DeviceProfile(mi.totalMem, mi.availMem, Runtime.getRuntime().availableProcessors(), thermal, am.memoryClass)
        }
    }
}
