#!/usr/bin/env python3
from __future__ import annotations
import re
import sys
from pathlib import Path


def exact(p: Path, old: str, new: str, label: str) -> None:
    s=p.read_text(encoding='utf-8')
    n=s.count(old)
    if n!=1:
        raise RuntimeError(f'{label}: expected 1 exact match in {p}, found {n}')
    p.write_text(s.replace(old,new), encoding='utf-8')


def regex(p: Path, pattern: str, repl: str, label: str) -> None:
    s=p.read_text(encoding='utf-8')
    out,n=re.subn(pattern,lambda _m: repl,s,count=1,flags=re.S)
    if n!=1:
        raise RuntimeError(f'{label}: expected 1 regex match in {p}, found {n}')
    p.write_text(out,encoding='utf-8')


def main(repo: Path) -> None:
    quality_cpp=repo/'native/quality/quality.cpp'
    pipeline_h=repo/'native/pipeline/pipeline.h'
    pipeline_cpp=repo/'native/pipeline/pipeline.cpp'
    export_h=repo/'native/export/export.h'
    export_cpp=repo/'native/export/export.cpp'
    jni_cpp=repo/'native/jni/sari_jni.cpp'
    tests_cpp=repo/'native/tests/test_main.cpp'

    regex(pipeline_h,
          r'struct PipelineFrameReport \{.*?\n\};',
          '''struct PipelineFrameReport {
  int index=0; bool included=false; int stars=0; float fwhm=0,roundness=0,noise=0,snr=0,score=0; int inliers=0; double rms=0; std::string reason;
};''',
          'pipeline report reason')

    regex(quality_cpp,
          r'std::vector<float>scoreFrames\(.*?Normalization estimateNormalization\(const BgStats&r,const BgStats&t\)\{.*?return n;\}',
          '''std::vector<float>scoreFrames(const std::vector<FrameMetrics>&m){
  std::vector<float>sc(m.size(),0); int maxStars=0; float bestFwhm=1e30f,bestNoise=1e30f,bestSnr=0;
  for(const auto&x:m) if(x.valid){maxStars=std::max(maxStars,x.starCount); if(x.fwhm>0) bestFwhm=std::min(bestFwhm,x.fwhm); if(x.noise>0) bestNoise=std::min(bestNoise,x.noise); bestSnr=std::max(bestSnr,x.snr);}
  if(!maxStars) return sc;
  for(size_t i=0;i<m.size();++i) if(m[i].valid){
    const float stars=std::min(1.f,(float)m[i].starCount/std::max(1,maxStars));
    const float fwhm=(bestFwhm<1e29f&&m[i].fwhm>0)?std::min(1.f,bestFwhm/m[i].fwhm):.5f;
    const float round=std::max(0.f,std::min(1.f,m[i].roundness));
    const float snr=bestSnr>0?std::min(1.f,m[i].snr/bestSnr):.5f;
    const float noise=(bestNoise<1e29f&&m[i].noise>0)?std::min(1.f,bestNoise/m[i].noise):.5f;
    const float sat=std::max(0.f,std::min(1.f,1.f-m[i].saturatedFrac*8.f));
    sc[i]=100.f*(.24f*stars+.24f*fwhm+.14f*round+.15f*snr+.13f*noise+.10f*sat);
  }
  return sc;
}
Normalization estimateNormalization(const BgStats&r,const BgStats&t){
  Normalization n;
  // Noise ratio is not a photometric scale; use neutral gain plus background offset as fallback.
  n.gain=1.f; n.offset=r.median-t.median;
  return n;
}''',
          'quality score + photometric fallback')

    regex(pipeline_cpp,
          r'static void writeReport\(const std::string& path,const PipelineReport&r\)\{.*?\n\}',
          r'''static void writeReport(const std::string& path,const PipelineReport&r){
  if(path.empty())return;std::ofstream o(path,std::ios::trunc);if(!o)return;
  o<<"status="<<r.status<<"\n"<<"frames_input="<<r.framesInput<<"\n"<<"frames_included="<<r.framesIncluded<<"\n"<<"tiles_done="<<r.tilesDone<<"\n"<<"tiles_total="<<r.tilesTotal<<"\n";
  for(auto&f:r.frames)o<<"frame="<<f.index<<" included="<<(f.included?1:0)<<" reason="<<f.reason<<" stars="<<f.stars<<" fwhm="<<f.fwhm<<" roundness="<<f.roundness<<" noise="<<f.noise<<" snr="<<f.snr<<" score="<<f.score<<" inliers="<<f.inliers<<" rms="<<f.rms<<"\n";
}''',
          'report rejection reason')

    regex(pipeline_cpp,
          r'static float channelAt\(const Plane&p,int cfa,int x,int y,int want\)\{.*?\n\}',
          '''static float channelAt(const Plane&p,int cfa,int x,int y,int want){
  const int here=cfaColor(cfa,x,y); const float c=rawAt(p,x,y);
  if(here==want)return c;
  auto clamp=[](float v){return std::max(0.f,std::min(1.f,v));};
  if(want==1 && here!=1){
    const float l=rawAt(p,x-1,y),r=rawAt(p,x+1,y),u=rawAt(p,x,y-1),d=rawAt(p,x,y+1);
    const float l2=rawAt(p,x-2,y),r2=rawAt(p,x+2,y),u2=rawAt(p,x,y-2),d2=rawAt(p,x,y+2);
    const float gh=.5f*(l+r)+.25f*(2.f*c-l2-r2);
    const float gv=.5f*(u+d)+.25f*(2.f*c-u2-d2);
    const float gradH=std::fabs(l-r)+.5f*std::fabs(l2-r2);
    const float gradV=std::fabs(u-d)+.5f*std::fabs(u2-d2);
    return clamp(gradH<=gradV?gh:gv);
  }
  if(here==1){
    bool horizontal=cfaColor(cfa,x-1,y)==want || cfaColor(cfa,x+1,y)==want;
    const float a=horizontal?0.5f*(rawAt(p,x-1,y)+rawAt(p,x+1,y)):0.5f*(rawAt(p,x,y-1)+rawAt(p,x,y+1));
    return clamp(a);
  }
  return clamp(.25f*(rawAt(p,x-1,y-1)+rawAt(p,x+1,y-1)+rawAt(p,x-1,y+1)+rawAt(p,x+1,y+1)));
}''',
          'pipeline edge-aware demosaic')

    exact(pipeline_cpp,
          'RegParams rp;rp.maxStars=40;rp.minInliers=8;rp.tol=2.5;rp.scaleTol=0.025;rp.minSep=12.0;',
          'RegParams rp;rp.maxStars=48;rp.minInliers=8;rp.tol=2.0;rp.scaleTol=0.02;rp.minSep=14.0;',
          'registration thresholds')

    regex(pipeline_cpp,
          r'  for\(size_t i=0;i<bases\.size\(\);\+\+i\)\{FrameInput f;.*?    inputs\.push_back\(f\);\n  \}',
          '''  for(size_t i=0;i<bases.size();++i){FrameInput f;f.src=(dark||flat||bias)?static_cast<PlaneSource*>(calSources[i].get()):static_cast<PlaneSource*>(bases[i].get());f.include=false;f.t=Transform();f.gain=1.f;f.offset=0.f;f.weight=std::max(.05f,std::pow(std::max(0.f,scores[i])/100.f,1.7f));
    if(i==ref && metrics[i].valid&&!stars[i].empty()){f.include=true;frReport[i].included=true;frReport[i].reason="reference";f.weight=1.f;inputs.push_back(f);out.framesIncluded++;continue;}
    if(stars[i].size()<3){frReport[i].reason="not-enough-stars";inputs.push_back(f);continue;}
    if(!metrics[i].valid||scores[i]<25.f){frReport[i].reason="low-quality";inputs.push_back(f);continue;}
    std::vector<Star> rs=stars[ref], ts=stars[i];for(auto&x:rs){x.x*=factor;x.y*=factor;}for(auto&x:ts){x.x*=factor;x.y*=factor;}
    RegResult rr;Status rsx=registerStars(rs,ts,rp,rr);
    if(rsx!=Status::Ok){frReport[i].reason="registration-failed";inputs.push_back(f);continue;}
    frReport[i].inliers=rr.inliers;frReport[i].rms=rr.rms;
    if(rr.confidence<0.35 || rr.rms>1.75){frReport[i].reason=rr.rms>1.75?"registration-rms-too-high":"registration-confidence-low";inputs.push_back(f);continue;}
    f.t=rr.t;Normalization n=estimatePhotometricNormalization(bgs[ref],bgs[i],rs,ts,rr.t);f.gain=n.gain;f.offset=n.offset;f.include=true;frReport[i].included=true;frReport[i].reason="ok";out.framesIncluded++;
    inputs.push_back(f);
  }''',
          'registration rejection reasons')

    exact(export_h,
          'Status exportFullPng(const std::string& f32Path, int W, int H, int cfa, int mode,\n                     float stretch, float denoiseStrength, const std::string& outputPath);\n',
          '''Status exportFullPng(const std::string& f32Path, int W, int H, int cfa, int mode,
                     float stretch, float denoiseStrength, const std::string& outputPath);

// Export the stacked linear Bayer master as a 32-bit floating-point FITS image.
Status exportLinearFits(const std::string& f32Path, int W, int H, int cfa,
                        const std::string& outputPath);
''',
          'FITS declaration')

    exact(export_cpp,
          '#include <fstream>\n#include <limits>\n#include <vector>\n',
          '#include <fstream>\n#include <limits>\n#include <vector>\n#include <cstring>\n',
          'FITS includes')

    fits_helpers='''
static void fitsCard(std::ostream&out,const std::string&key,const std::string&value){
    if(key=="END"){std::string s="END";s.append(77,' ');out.write(s.data(),80);return;}
    std::string s=key;s.resize(8,' ');s+="= ";s+=value;if(s.size()<80)s.append(80-s.size(),' ');else if(s.size()>80)s.resize(80);out.write(s.data(),80);
}
static void fitsComment(std::ostream&out,const std::string&text){
    std::string s="COMMENT ";s+=text;if(s.size()<80)s.append(80-s.size(),' ');else if(s.size()>80)s.resize(80);out.write(s.data(),80);
}
static void fitsPad(std::ostream&out,std::streamoff bytes,char fill){const std::streamoff rem=bytes%2880;if(rem){std::vector<char>z(static_cast<size_t>(2880-rem),fill);out.write(z.data(),z.size());}}
static std::string cfaName(int cfa){switch(cfa&3){case 0:return "RGGB";case 1:return "GRBG";case 2:return "GBRG";case 3:return "BGGR";default:return "UNKNOWN";}}
static void beFloat(std::ostream&out,float v){uint32_t u=0;std::memcpy(&u,&v,4);const uint8_t b[4]={uint8_t(u>>24),uint8_t(u>>16),uint8_t(u>>8),uint8_t(u)};out.write(reinterpret_cast<const char*>(b),4);}
'''
    regex(export_cpp,r'\} // namespace\n\nStatus exportFullPng',fits_helpers+'\n} // namespace\n\nStatus exportFullPng','FITS helpers')

    fits_func=r'''
Status exportLinearFits(const std::string&f32Path,int W,int H,int cfa,const std::string&outputPath){
    if(f32Path.empty()||outputPath.empty()||W<=0||H<=0)return Status::InvalidArgument;
    F32FileSource src(f32Path.c_str(),W,H);if(!src.ok())return Status::IoError;
    std::ofstream out(outputPath,std::ios::binary|std::ios::trunc);if(!out)return Status::IoError;
    fitsCard(out,"SIMPLE","  T");fitsCard(out,"BITPIX","  -32");fitsCard(out,"NAXIS","  2");
    fitsCard(out,"NAXIS1",std::string(" ")+std::to_string(W));fitsCard(out,"NAXIS2",std::string(" ")+std::to_string(H));
    fitsCard(out,"BSCALE","  1.0");fitsCard(out,"BZERO","  0.0");fitsCard(out,"CFA","'"+cfaName(cfa)+"'");fitsCard(out,"ORIGIN","'SARI ASTRO'");
    fitsComment(out,"Linear stacked Bayer master; no demosaic or stretch applied.");fitsCard(out,"END","");fitsPad(out,out.tellp(),' ');
    std::vector<float>row(static_cast<size_t>(W));
    for(int y=0;y<H;++y){if(src.readRegion(0,y,W,1,row.data())!=Status::Ok)return Status::IoError;for(int x=0;x<W;++x)beFloat(out,row[x]);}
    fitsPad(out,out.tellp(),'\0');return out.good()?Status::Ok:Status::IoError;
}
'''
    # Insert the function immediately before the final namespace close.
    regex(export_cpp,r'(\n\} // namespace sari\s*)$', '\n' + fits_func + '\n} // namespace sari\n','FITS function')

    jni_insert='''JNIEXPORT jint JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeExportLinearFits(JNIEnv* env,jclass,jstring path,jint w,jint h,jint cfa,jstring output){
  try{std::string p,o;if(!getString(env,path,p,false)||!getString(env,output,o,false))return (jint)Status::InvalidArgument;return (jint)exportLinearFits(p,w,h,cfa,o);}catch(...){return (jint)Status::IoError;}
}
'''
    regex(jni_cpp,r'(JNIEXPORT jintArray JNICALL Java_com_sari_astro_nativebridge_NativeCore_nativeStackProject\()',jni_insert+r'\n\1','JNI FITS bridge')

    exact(tests_cpp,
          'Normalization n = estimateNormalization({0.05f, 0.01f}, {0.3f, 0.02f}); CHECK(std::fabs(n.gain - 0.5f) < 1e-6f && std::fabs(0.3f * n.gain + n.offset - 0.05f) < 1e-6f, "norm");',
          'Normalization n = estimateNormalization({0.05f, 0.01f}, {0.3f, 0.02f}); CHECK(std::fabs(n.gain - 1.0f) < 1e-6f && std::fabs(0.3f * n.gain + n.offset - 0.05f) < 1e-6f, "photometric fallback");',
          'quality normalization test')

    exact(tests_cpp,
          '  const char* png = "/tmp/sari_export_test.png";\n',
          '  const char* png = "/tmp/sari_export_test.png";\n  const char* fits = "/tmp/sari_export_test.fits";\n',
          'FITS test path')
    exact(tests_cpp,
          '  CHECK(exportFullPng(f32, p.w, p.h, 0, 1, 3.f, .25f, png) == Status::Ok, "full png");\n',
          '  CHECK(exportFullPng(f32, p.w, p.h, 0, 1, 3.f, .25f, png) == Status::Ok, "full png");\n  CHECK(exportLinearFits(f32, p.w, p.h, 0, fits) == Status::Ok, "linear fits");\n',
          'FITS test call')
    exact(tests_cpp,
          '  std::remove(f32); std::remove(png);\n',
          '  FILE* ff = std::fopen(fits, "rb"); CHECK(ff != nullptr, "fits exists"); if (ff) { char hdr[81] = {}; CHECK(std::fread(hdr,1,80,ff) == 80, "fits header read"); CHECK(std::string(hdr,8) == "SIMPLE  ", "fits SIMPLE card"); std::fseek(ff,0,SEEK_END); long fs = std::ftell(ff); CHECK(fs % 2880 == 0, "fits 2880-byte blocks"); std::fclose(ff); }\n  std::remove(f32); std::remove(png); std::remove(fits);\n',
          'FITS test validation')


if __name__=='__main__':
    if len(sys.argv)!=2:
        print('usage: native_patch.py /path/to/SARI-ASTRO',file=sys.stderr);sys.exit(2)
    main(Path(sys.argv[1]).expanduser().resolve())
