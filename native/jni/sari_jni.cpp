#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <exception>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "../memory/budget.h"
#include "../stars/stars.h"
#include "../pipeline/pipeline.h"
#include "../export/export.h"
#define TAG "SariNative"
using namespace sari;
namespace { std::atomic<bool> g_cancel{false}; }

static bool getString(JNIEnv* env,jstring s,std::string& out,bool allowEmpty=true){
  if(!s){out.clear();return allowEmpty;}
  const char* p=env->GetStringUTFChars(s,nullptr);if(!p)return false;out.assign(p);env->ReleaseStringUTFChars(s,p);return true;
}
static bool getStringArray(JNIEnv* env,jobjectArray a,std::vector<std::string>&out){
  if(!a)return false;jsize n=env->GetArrayLength(a);if(n<=0)return false;out.clear();out.reserve(n);for(jsize i=0;i<n;++i){jstring s=(jstring)env->GetObjectArrayElement(a,i);std::string v;bool ok=getString(env,s,v,false);env->DeleteLocalRef(s);if(!ok||v.empty())return false;out.push_back(std::move(v));}return true;
}
static jintArray makeInts(JNIEnv* env,const std::vector<jint>&v){jintArray a=env->NewIntArray((jsize)v.size());if(!a)return nullptr;if(!v.empty())env->SetIntArrayRegion(a,0,(jsize)v.size(),v.data());return a;}

extern "C" {
JNIEXPORT jstring JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeVersion(JNIEnv* env,jclass){return env->NewStringUTF("sari-core 1.0.0");}
JNIEXPORT jintArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativePlanStack(JNIEnv* env,jclass,jint w,jint h,jint frames,jlong totalRam,jlong availRam,jint cores,jint thermal,jint mode){
  try{if(w<=0||h<=0||frames<=0||mode<0||mode>2)return nullptr;DeviceInfo d{(uint64_t)std::max<jlong>(0,totalRam),(uint64_t)std::max<jlong>(0,availRam),cores,thermal};StackPlan p=planStack(w,h,frames,d,(PerfMode)mode);return makeInts(env,{p.tile,p.workers,p.feasible?1:0,(jint)(p.peakBytes>>20),(jint)(p.budgetBytes>>20)});}catch(...){__android_log_print(ANDROID_LOG_ERROR,TAG,"planStack threw");return nullptr;}
}
JNIEXPORT jint JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeRecommendMode(JNIEnv*,jclass,jlong totalRam,jlong availRam,jint cores,jint thermal){try{return (jint)recommendMode(DeviceInfo{(uint64_t)std::max<jlong>(0,totalRam),(uint64_t)std::max<jlong>(0,availRam),cores,thermal});}catch(...){return 0;}}
JNIEXPORT jfloatArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeDetectStars(JNIEnv* env,jclass,jfloatArray data,jint w,jint h){
  try{if(!data||w<=0||h<=0)return nullptr;if((jlong)env->GetArrayLength(data)!=(jlong)w*h)return nullptr;Plane p(w,h);env->GetFloatArrayRegion(data,0,w*h,p.d.data());if(env->ExceptionCheck())return nullptr;std::vector<Star>s;Status st=detectStars(p,StarParams(),s);if(st!=Status::Ok&&st!=Status::NoStars)return nullptr;std::vector<float>f;f.reserve(s.size()*4);for(auto&x:s){f.push_back(x.x);f.push_back(x.y);f.push_back(x.flux);f.push_back(x.fwhm);}jfloatArray out=env->NewFloatArray((jsize)f.size());if(!out)return nullptr;if(!f.empty())env->SetFloatArrayRegion(out,0,(jsize)f.size(),f.data());return out;}catch(...){return nullptr;}
}
JNIEXPORT void JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeCancelStack(JNIEnv*,jclass){g_cancel.store(true,std::memory_order_release);}
JNIEXPORT jint JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeExportFullPng(JNIEnv* env,jclass,jstring path,jint w,jint h,jint cfa,jint mode,jfloat stretch,jfloat denoise,jstring output){
  try{std::string p,o;if(!getString(env,path,p,false)||!getString(env,output,o,false))return (jint)Status::InvalidArgument;return (jint)exportFullPng(p,w,h,cfa,mode,stretch,denoise,o);}catch(...){return (jint)Status::IoError;}
}

JNIEXPORT jintArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeStackProject(JNIEnv* env,jclass,jobjectArray lights,jint w,jint h,jstring dark,jstring flat,jstring bias,jstring output,jstring progress,jstring report,jint tile,jint workers,jint startTile){
  g_cancel.store(false,std::memory_order_release);try{
    std::vector<std::string>lp;std::string dp,fp,bp,op,pp,rp;if(!getStringArray(env,lights,lp)||!getString(env,dark,dp)||!getString(env,flat,fp)||!getString(env,bias,bp)||!getString(env,output,op,false)||!getString(env,progress,pp)||!getString(env,report,rp))return nullptr;
    PipelineReport pr;Status st=stackProject(lp,w,h,dp,fp,bp,op,pp,rp,tile,workers,startTile,[&](int,int){return !g_cancel.load(std::memory_order_acquire);},pr);g_cancel.store(false,std::memory_order_release);
    return makeInts(env,{(jint)st,(jint)pr.framesInput,(jint)pr.framesIncluded,(jint)pr.tilesDone,(jint)pr.tilesTotal});
  }catch(const std::exception&e){g_cancel.store(false,std::memory_order_release);__android_log_print(ANDROID_LOG_ERROR,TAG,"stackProject: %s",e.what());return makeInts(env,{(jint)Status::IoError,0,0,0,0});}catch(...){g_cancel.store(false,std::memory_order_release);return makeInts(env,{(jint)Status::IoError,0,0,0,0});}
}
JNIEXPORT jbyteArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeRenderPreview(JNIEnv* env,jclass,jstring path,jint w,jint h,jint cfa,jint mode,jfloat stretch,jint maxDim){
  try{std::string p;if(!getString(env,path,p,false))return nullptr;std::vector<uint8_t>rgba;int ow=0,oh=0;Status st=renderPreview(p,w,h,cfa,mode,stretch,maxDim,rgba,ow,oh);if(st!=Status::Ok)return nullptr;std::vector<uint8_t>payload(8+rgba.size());std::memcpy(payload.data(),&ow,4);std::memcpy(payload.data()+4,&oh,4);if(!rgba.empty())std::memcpy(payload.data()+8,rgba.data(),rgba.size());jbyteArray a=env->NewByteArray((jsize)payload.size());if(!a)return nullptr;env->SetByteArrayRegion(a,0,(jsize)payload.size(),reinterpret_cast<const jbyte*>(payload.data()));return a;}catch(...){return nullptr;}
}
}
