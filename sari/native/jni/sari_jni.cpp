// Minimal JNI boundary. Every entry point validates arguments and never lets a C++ exception escape.
#include <jni.h>
#include <android/log.h>
#include <vector>
#include "../memory/budget.h"
#include "../stars/stars.h"
#define TAG "SariNative"
using namespace sari;
extern "C" {
JNIEXPORT jstring JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeVersion(JNIEnv* env, jclass) {
  return env->NewStringUTF("sari-core 0.1.0");
}
// returns [tile, workers, feasible(0/1), peakMB, budgetMB]; null on bad args
JNIEXPORT jintArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativePlanStack(
    JNIEnv* env, jclass, jint w, jint h, jint frames, jlong totalRam, jlong availRam, jint cores, jint thermal, jint mode) {
  try {
    if (w <= 0 || h <= 0 || frames <= 0 || mode < 0 || mode > 2) return nullptr;
    DeviceInfo d{static_cast<uint64_t>(totalRam < 0 ? 0 : totalRam), static_cast<uint64_t>(availRam < 0 ? 0 : availRam), cores, thermal};
    StackPlan p = planStack(w, h, frames, d, static_cast<PerfMode>(mode));
    jint v[5] = {p.tile, p.workers, p.feasible ? 1 : 0, static_cast<jint>(p.peakBytes >> 20), static_cast<jint>(p.budgetBytes >> 20)};
    jintArray a = env->NewIntArray(5); if (!a) return nullptr;
    env->SetIntArrayRegion(a, 0, 5, v); return a;
  } catch (...) { __android_log_print(ANDROID_LOG_ERROR, TAG, "planStack threw"); return nullptr; }
}
JNIEXPORT jint JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeRecommendMode(
    JNIEnv*, jclass, jlong totalRam, jlong availRam, jint cores, jint thermal) {
  try { return static_cast<jint>(recommendMode(DeviceInfo{static_cast<uint64_t>(totalRam < 0 ? 0 : totalRam), static_cast<uint64_t>(availRam < 0 ? 0 : availRam), cores, thermal})); }
  catch (...) { return 0; }
}
// Star detection on a float plane (row-major). Returns flat [x,y,flux,fwhm]*N, empty on no stars, null on error.
JNIEXPORT jfloatArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeDetectStars(
    JNIEnv* env, jclass, jfloatArray data, jint w, jint h) {
  try {
    if (!data || w <= 0 || h <= 0) return nullptr;
    if (static_cast<jlong>(env->GetArrayLength(data)) != static_cast<jlong>(w) * h) return nullptr;
    Plane p(w, h);
    env->GetFloatArrayRegion(data, 0, w * h, p.d.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<Star> s; Status st = detectStars(p, StarParams(), s);
    if (st != Status::Ok && st != Status::NoStars) return nullptr;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(s.size() * 4)); if (!out) return nullptr;
    std::vector<float> f; for (auto& x : s) { f.push_back(x.x); f.push_back(x.y); f.push_back(x.flux); f.push_back(x.fwhm); }
    if (!f.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(f.size()), f.data());
    return out;
  } catch (...) { __android_log_print(ANDROID_LOG_ERROR, TAG, "detectStars threw"); return nullptr; }
}
}
