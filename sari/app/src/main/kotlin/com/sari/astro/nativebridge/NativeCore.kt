package com.sari.astro.nativebridge

/** Thin JNI facade. If the native library fails to load, [available] is false and callers degrade gracefully. */
object NativeCore {
    val available: Boolean = try { System.loadLibrary("sari_jni"); true } catch (t: Throwable) { false }

    data class Plan(val tile: Int, val workers: Int, val feasible: Boolean, val peakMb: Int, val budgetMb: Int)

    fun version(): String = if (available) runCatching { nativeVersion() }.getOrDefault("native error") else "native unavailable"

    fun planStack(w: Int, h: Int, frames: Int, d: DeviceProfile, mode: Int): Plan? {
        if (!available) return null
        val a = runCatching { nativePlanStack(w, h, frames, d.totalRam, d.availRam, d.cores, d.thermal, mode) }.getOrNull() ?: return null
        return Plan(a[0], a[1], a[2] == 1, a[3], a[4])
    }
    fun recommendMode(d: DeviceProfile): Int =
        if (!available) 0 else runCatching { nativeRecommendMode(d.totalRam, d.availRam, d.cores, d.thermal) }.getOrDefault(0)

    // @JvmStatic => static JNI methods, matching the (JNIEnv*, jclass) signatures in sari_jni.cpp
    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun nativePlanStack(w: Int, h: Int, frames: Int, total: Long, avail: Long, cores: Int, thermal: Int, mode: Int): IntArray?
    @JvmStatic private external fun nativeRecommendMode(total: Long, avail: Long, cores: Int, thermal: Int): Int
    @JvmStatic external fun nativeDetectStars(data: FloatArray, w: Int, h: Int): FloatArray?
}
