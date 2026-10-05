package com.sari.astro.nativebridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeCore {
    val available: Boolean = try { System.loadLibrary("sari_jni"); true } catch (_: Throwable) { false }

    data class Plan(val tile: Int, val workers: Int, val feasible: Boolean, val peakMb: Int, val budgetMb: Int)
    data class StackResult(val status: Int, val inputFrames: Int, val includedFrames: Int, val tilesDone: Int, val tilesTotal: Int) {
        val completed get() = status == 0
        val cancelled get() = status == 6
    }
    data class Preview(val width: Int, val height: Int, val rgba: ByteArray)

    fun version(): String = if (available) runCatching { nativeVersion() }.getOrDefault("native error") else "native unavailable"
    fun planStack(w: Int, h: Int, frames: Int, d: DeviceProfile, mode: Int): Plan? {
        if (!available) return null
        val a = runCatching { nativePlanStack(w,h,frames,d.totalRam,d.availRam,d.cores,d.thermal,mode) }.getOrNull() ?: return null
        if (a.size < 5) return null
        return Plan(a[0],a[1],a[2] == 1,a[3],a[4])
    }
    fun recommendMode(d: DeviceProfile): Int = if (!available) 0 else runCatching { nativeRecommendMode(d.totalRam,d.availRam,d.cores,d.thermal) }.getOrDefault(0)
    fun stackProject(lights: Array<String>, w:Int,h:Int,dark:String?,flat:String?,bias:String?,output:String,progress:String,report:String,tile:Int,workers:Int,startTile:Int): StackResult? {
        if (!available) return null
        val a = runCatching { nativeStackProject(lights,w,h,dark,flat,bias,output,progress,report,tile,workers,startTile) }.getOrNull() ?: return null
        if (a.size < 5) return null
        return StackResult(a[0],a[1],a[2],a[3],a[4])
    }
    fun cancelStack() { if (available) runCatching { nativeCancelStack() } }
    fun renderPreview(path:String,w:Int,h:Int,cfa:Int,mode:Int,stretch:Float,maxDim:Int=1600): Preview? {
        if (!available) return null
        val a=runCatching { nativeRenderPreview(path,w,h,cfa,mode,stretch,maxDim) }.getOrNull() ?: return null
        if(a.size<8)return null
        val b=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val ow=b.int;val oh=b.int;val rgba=ByteArray(a.size-8);b.get(rgba);return Preview(ow,oh,rgba)
    }
    @JvmStatic external fun nativeDetectStars(data: FloatArray,w:Int,h:Int):FloatArray?
    @JvmStatic private external fun nativeVersion():String
    @JvmStatic private external fun nativePlanStack(w:Int,h:Int,frames:Int,total:Long,avail:Long,cores:Int,thermal:Int,mode:Int):IntArray?
    @JvmStatic private external fun nativeRecommendMode(total:Long,avail:Long,cores:Int,thermal:Int):Int
    @JvmStatic private external fun nativeStackProject(lights:Array<String>,w:Int,h:Int,dark:String?,flat:String?,bias:String?,output:String,progress:String,report:String,tile:Int,workers:Int,startTile:Int):IntArray?
    @JvmStatic private external fun nativeCancelStack()
    @JvmStatic private external fun nativeRenderPreview(path:String,w:Int,h:Int,cfa:Int,mode:Int,stretch:Float,maxDim:Int):ByteArray?
}
