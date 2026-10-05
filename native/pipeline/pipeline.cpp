#include "pipeline.h"
#include "../memory/progress.h"
#include "../quality/quality.h"
#include "../registration/registration.h"
#include "../stacking/stacking.h"
#include "../stars/stars.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>

namespace sari {
namespace {
using Clock = std::chrono::steady_clock;

static bool finitePositive(float v) { return std::isfinite(v) && v > 0.f; }

static Status readProxy(PlaneSource& src, int factor, Plane& out) {
  factor = std::max(1, factor);
  const int W = src.width(), H = src.height();
  if (W <= 0 || H <= 0) return Status::InvalidArgument;
  const int pw = (W + factor - 1) / factor, ph = (H + factor - 1) / factor;
  try { out = Plane(pw, ph); } catch (...) { return Status::OutOfMemory; }
  const int block = 256;
  std::vector<float> raw;
  for (int py = 0; py < ph; py += block / factor + 1) {
    int pny = std::min(ph, py + block / factor + 1);
    int y0 = py * factor;
    int y1 = std::min(H, pny * factor);
    int rw = W;
    int rh = y1 - y0;
    try { raw.resize(static_cast<size_t>(rw) * rh); } catch (...) { return Status::OutOfMemory; }
    Status s = src.readRegion(0, y0, rw, rh, raw.data());
    if (s != Status::Ok) return s;
    for (int pyy = py; pyy < pny; ++pyy) for (int pxx = 0; pxx < pw; ++pxx) {
      const int sx0 = pxx * factor;
      const int sy0 = (pyy) * factor;
      double acc = 0; int n = 0;
      for (int yy = sy0; yy < std::min(H, sy0 + factor); ++yy) for (int xx = sx0; xx < std::min(W, sx0 + factor); ++xx) {
        float v = raw[static_cast<size_t>(yy - y0) * rw + xx];
        if (std::isfinite(v)) { acc += v; ++n; }
      }
      out.at(pxx, pyy) = n ? static_cast<float>(acc / n) : std::numeric_limits<float>::quiet_NaN();
    }
  }
  return Status::Ok;
}

struct CalibratedSource final : PlaneSource {
  PlaneSource* base; PlaneSource* dark; PlaneSource* flat; PlaneSource* bias; float flatNorm;
  CalibratedSource(PlaneSource* b, PlaneSource* d, PlaneSource* f, PlaneSource* bi, float fn)
      : base(b),dark(d),flat(f),bias(bi),flatNorm(fn) {}
  int width() const override { return base ? base->width() : 0; }
  int height() const override { return base ? base->height() : 0; }
  Status readRegion(int x0,int y0,int w,int h,float*out) override {
    if (!base || !out || w<=0 || h<=0) return Status::InvalidArgument;
    const size_t n = static_cast<size_t>(w)*h;
    std::vector<float> b,d,f,bi;
    try {
      b.resize(n);
      if (dark) d.resize(n);
      if (flat) f.resize(n);
      if (bias) bi.resize(n);
    } catch (...) { return Status::OutOfMemory; }
    Status s = base->readRegion(x0,y0,w,h,b.data()); if (s!=Status::Ok) return s;
    if (dark) { s=dark->readRegion(x0,y0,w,h,d.data()); if(s!=Status::Ok)return s; }
    if (flat) { s=flat->readRegion(x0,y0,w,h,f.data()); if(s!=Status::Ok)return s; }
    if (bias) { s=bias->readRegion(x0,y0,w,h,bi.data()); if(s!=Status::Ok)return s; }
    const float eps=1e-6f;
    for(size_t i=0;i<n;++i){
      float v=b[i];
      if (bias) v -= bi[i];
      if (dark) v -= d[i];
      if (flat) { float fv=f[i]; if(bias) fv-=bi[i]; fv/=std::max(flatNorm,eps); if(finitePositive(fv)) v/=fv; else v=0.f; }
      out[i]=std::max(0.f,std::isfinite(v)?v:0.f);
    }
    return Status::Ok;
  }
};

struct F32OutputSink final : OutputSink {
  std::fstream f; int W=0,H=0;
  F32OutputSink(const std::string& path,int w,int h,int startTile):W(w),H(h){
    if (startTile==0) {
      f.open(path,std::ios::in|std::ios::out|std::ios::binary|std::ios::trunc);
      if(f){
        const std::streamoff bytes=static_cast<std::streamoff>(W)*H*4;
        if(bytes>0){f.seekp(bytes-1); char z=0; f.write(&z,1);}
      }
    } else {
      f.open(path,std::ios::in|std::ios::out|std::ios::binary);
    }
  }
  bool ok() const { return f.is_open() && static_cast<bool>(f); }
  Status writeTile(int x0,int y0,int w,int h,const float*d) override {
    if(!ok()||!d||x0<0||y0<0||x0+w>W||y0+h>H)return Status::InvalidArgument;
    for(int y=0;y<h;++y){std::streamoff off=(static_cast<std::streamoff>(y0+y)*W+x0)*4;f.seekp(off);if(!f) return Status::IoError;f.write(reinterpret_cast<const char*>(d+(size_t)y*w),static_cast<std::streamsize>(w*4));if(!f)return Status::IoError;}
    f.flush(); return Status::Ok;
  }
};

static float medianSample(PlaneSource& src) {
  const int W=src.width(),H=src.height(); if(W<=0||H<=0)return 0.f;
  const int step=std::max(1,(int)std::sqrt(std::max(1,(W*H)/65536)));
  const int rw=std::min(W,step*256), rh=std::min(H,step*256);
  std::vector<float> vals; vals.reserve(65536);
  std::vector<float> row((size_t)rw*rh);
  for(int y=0;y<H;y+=rh){int hh=std::min(rh,H-y);for(int x=0;x<W;x+=rw){int ww=std::min(rw,W-x);if(src.readRegion(x,y,ww,hh,row.data())!=Status::Ok)continue;for(int yy=0;yy<hh;yy+=step)for(int xx=0;xx<ww;xx+=step){float v=row[(size_t)yy*ww+xx];if(std::isfinite(v))vals.push_back(v);if(vals.size()>=65536)break;}if(vals.size()>=65536)break;}if(vals.size()>=65536)break;}
  if(vals.empty())return 0.f;size_t m=vals.size()/2;std::nth_element(vals.begin(),vals.begin()+m,vals.end());return vals[m];
}

static void writeProgress(const std::string& path,int done,int total,double elapsed){
  if(path.empty())return;std::ofstream o(path,std::ios::trunc);if(o)o<<done<<","<<total<<","<<elapsed<<"\n";
}
static void writeReport(const std::string& path,const PipelineReport&r){
  if(path.empty())return;std::ofstream o(path,std::ios::trunc);if(!o)return;
  o<<"status="<<r.status<<"\n"<<"frames_input="<<r.framesInput<<"\n"<<"frames_included="<<r.framesIncluded<<"\n"<<"tiles_done="<<r.tilesDone<<"\n"<<"tiles_total="<<r.tilesTotal<<"\n";
  for(auto&f:r.frames)o<<"frame="<<f.index<<" included="<<(f.included?1:0)<<" reason="<<f.reason<<" stars="<<f.stars<<" fwhm="<<f.fwhm<<" roundness="<<f.roundness<<" noise="<<f.noise<<" snr="<<f.snr<<" score="<<f.score<<" inliers="<<f.inliers<<" rms="<<f.rms<<"\n";
}
static Normalization estimatePhotometricNormalization(const BgStats& refBg,const BgStats& tgtBg,
                                                        const std::vector<Star>& refStars,
                                                        const std::vector<Star>& tgtStars,
                                                        const Transform& refToTarget) {
  Normalization n=estimateNormalization(refBg,tgtBg);
  std::vector<float> ratios;
  ratios.reserve(std::min<size_t>(refStars.size(),64));
  const float minFlux=std::max(0.001f,refBg.noise*4.f);
  for(const auto& r:refStars){
    if(!(r.flux>minFlux))continue;
    double tx,ty;refToTarget.apply(r.x,r.y,tx,ty);
    float best=std::numeric_limits<float>::max();const Star* match=nullptr;
    for(const auto& t:tgtStars){double dx=t.x-tx,dy=t.y-ty;float d=(float)std::hypot(dx,dy);if(d<best){best=d;match=&t;}}
    if(match&&best<=3.5f&&match->flux>std::max(0.001f,tgtBg.noise*4.f)){
      float q=r.flux/match->flux;
      if(std::isfinite(q)&&q>0.25f&&q<4.0f)ratios.push_back(q);
    }
  }
  if(!ratios.empty()){
    const size_t m=ratios.size()/2;std::nth_element(ratios.begin(),ratios.begin()+m,ratios.end());float gain=ratios[m];
    if(!(gain>0)&&n.gain>0)gain=n.gain;
    n.gain=std::max(0.5f,std::min(2.0f,gain));
    n.offset=refBg.median-tgtBg.median*n.gain;
  }
  if(!std::isfinite(n.gain)||n.gain<=0)n.gain=1.f;
  if(!std::isfinite(n.offset))n.offset=0.f;
  return n;
}


} // namespace anonymous

Status stackProject(const std::vector<std::string>&lightPaths,int W,int H,const std::string&darkPath,const std::string&flatPath,const std::string&biasPath,const std::string&outputPath,const std::string&progressPath,const std::string&reportPath,int tile,int workers,int startTile,const std::function<bool(int,int)>& cancelOrContinue,PipelineReport&out){
  out=PipelineReport();out.framesInput=(int)lightPaths.size();if(lightPaths.empty()||W<=0||H<=0||outputPath.empty()||tile<16||workers<1||startTile<0)return Status::InvalidArgument;
  std::vector<std::unique_ptr<F32FileSource>> bases; bases.reserve(lightPaths.size());
  for(auto&p:lightPaths){auto s=std::make_unique<F32FileSource>(p.c_str(),W,H);if(!s->ok())return Status::IoError;bases.push_back(std::move(s));}
  auto openCal=[&](const std::string&path)->std::unique_ptr<F32FileSource>{if(path.empty())return nullptr;auto s=std::make_unique<F32FileSource>(path.c_str(),W,H);if(!s->ok())return nullptr;return s;};
  auto dark=openCal(darkPath); if(!darkPath.empty()&&!dark)return Status::IoError;
  auto flat=openCal(flatPath); if(!flatPath.empty()&&!flat)return Status::IoError;
  auto bias=openCal(biasPath); if(!biasPath.empty()&&!bias)return Status::IoError;
  float flatNorm=1.f;if(flat){flatNorm=medianSample(*flat);if(!(flatNorm>1e-6f))flatNorm=1.f;}

  const int factor = (std::max(W,H)>5000)?4:((std::max(W,H)>2500)?2:1);
  std::vector<FrameMetrics> metrics(lightPaths.size());
  std::vector<BgStats> bgs(lightPaths.size());
  std::vector<std::vector<Star>> stars(lightPaths.size());
  std::vector<PipelineFrameReport> frReport(lightPaths.size());
  std::vector<std::unique_ptr<CalibratedSource>> calSources;calSources.reserve(lightPaths.size());
  for(size_t i=0;i<bases.size();++i){
    PlaneSource* use=bases[i].get();
    if(dark||flat||bias){calSources.push_back(std::make_unique<CalibratedSource>(use,dark.get(),flat.get(),bias.get(),flatNorm));use=calSources.back().get();}
    Plane proxy;
    Status s=readProxy(*use,factor,proxy);if(s!=Status::Ok)return s;
    StarParams sp;sp.maxStars=160;sp.sigma=4.5f;sp.bgBlock=32;
    metrics[i]=computeMetrics(proxy,sp);bgs[i]=estimateBackground(proxy);
    detectStars(proxy,sp,stars[i]);
    frReport[i].index=(int)i;frReport[i].stars=metrics[i].starCount;frReport[i].fwhm=metrics[i].fwhm;frReport[i].roundness=metrics[i].roundness;frReport[i].noise=metrics[i].noise;frReport[i].snr=metrics[i].snr;
  }
  auto scores=scoreFrames(metrics);for(size_t i=0;i<scores.size();++i)frReport[i].score=scores[i];
  size_t ref=0;float bestRef=-1.f;for(size_t i=0;i<stars.size();++i)if(metrics[i].valid&&!stars[i].empty()&&scores[i]>bestRef){bestRef=scores[i];ref=i;}if(bestRef<0)return Status::NoStars;
  std::vector<FrameInput> inputs;inputs.reserve(bases.size());
  RegParams rp;rp.maxStars=48;rp.minInliers=8;rp.tol=2.0;rp.scaleTol=0.02;rp.minSep=14.0;
  for(size_t i=0;i<bases.size();++i){FrameInput f;f.src=(dark||flat||bias)?static_cast<PlaneSource*>(calSources[i].get()):static_cast<PlaneSource*>(bases[i].get());f.include=false;f.t=Transform();f.gain=1.f;f.offset=0.f;f.weight=std::max(.05f,std::pow(std::max(0.f,scores[i])/100.f,1.7f));
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
  }
  if(out.framesIncluded==0)return Status::RegistrationFailed;
  if(startTile==0){std::ofstream clear(progressPath,std::ios::trunc);}
  F32OutputSink sink(outputPath,W,H,startTile);if(!sink.ok())return Status::IoError;
  StackParams sp;sp.tile=tile;sp.workers=workers;sp.startTile=startTile;sp.rej=Rejection::Winsorized;sp.kLow=3;sp.kHigh=3;sp.maxIter=5;
  ProgressTracker tracker(1); const int tx=(W+tile-1)/tile,ty=(H+tile-1)/tile,total=tx*ty;tracker=ProgressTracker(total);tracker.start(0);auto start=Clock::now();
  auto cb=[&](int done,int totalTiles){double elapsed=std::chrono::duration<double>(Clock::now()-start).count();tracker.update(done,elapsed);writeProgress(progressPath,done,totalTiles,elapsed);out.tilesDone=done;out.tilesTotal=totalTiles;return cancelOrContinue?cancelOrContinue(done,totalTiles):true;};
  StackStats st;Status s=stackFrames(inputs,W,H,sp,sink,cb,st);out.status=(int)s;out.tilesDone=st.tilesDone;out.tilesTotal=st.tilesTotal;out.frames=frReport;writeReport(reportPath,out);writeProgress(progressPath,st.tilesDone,st.tilesTotal,std::chrono::duration<double>(Clock::now()-start).count());return s;
}

static float rawAt(const Plane&p,int x,int y){x=std::max(0,std::min(p.w-1,x));y=std::max(0,std::min(p.h-1,y));return p.at(x,y);}
static int cfaColor(int cfa,int x,int y){int px=x&1,py=y&1;switch(cfa&3){case 0: return (py==0&&px==0)?0:((py==1&&px==1)?2:1); // RGGB
case 1: return (py==0&&px==1)?0:((py==1&&px==0)?2:1); // GRBG
case 2: return (py==0&&px==0)?1:((py==1&&px==1)?1:((py==0)?2:0)); // GBRG
case 3: return (py==0&&px==0)?2:((py==1&&px==1)?0:1); // BGGR
default:return 1;}}
static float channelAt(const Plane&p,int cfa,int x,int y,int want){
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
}
static float stretchValue(float v,float lo,float hi,float amount){if(!(hi>lo))return 0.f;float x=std::max(0.f,std::min(1.f,(v-lo)/(hi-lo)));float a=std::max(.1f,amount);return std::asinh(a*x)/std::asinh(a);}
Status renderPreview(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,int maxDim,std::vector<uint8_t>&rgba,int&outW,int&outH){F32FileSource src(f32Path.c_str(),W,H);if(!src.ok())return Status::IoError;if(W<=0||H<=0)return Status::InvalidArgument;int factor=std::max(1,(std::max(W,H)+maxDim-1)/maxDim);if(factor>1&&factor&1)++factor;outW=(W+factor-1)/factor;outH=(H+factor-1)/factor;Plane p(outW,outH);std::vector<float>row(W*std::min(H,64));for(int oy=0;oy<outH;oy+=64){int hh=std::min(64,outH-oy);int sy=oy*factor;int rh=std::min(H-sy,std::max(1,hh*factor));row.resize((size_t)W*rh);Status s=src.readRegion(0,sy,W,rh,row.data());if(s!=Status::Ok)return s;for(int y=0;y<hh;++y){int rawY=std::min(H-1,(oy+y)*factor);for(int x=0;x<outW;++x){int rawX=std::min(W-1,x*factor);p.at(x,oy+y)=row[(size_t)(rawY-sy)*W+(rawX)];}}}
  std::vector<float> sample;sample.reserve(65536);for(int y=0;y<p.h;y+=std::max(1,p.h/256))for(int x=0;x<p.w;x+=std::max(1,p.w/256)){float v=p.at(x,y);if(std::isfinite(v))sample.push_back(v);if(sample.size()>=65536)break;}if(sample.empty())return Status::InvalidArgument;auto q=[&](double qv){std::vector<float>a=sample;size_t k=(size_t)std::min<double>(a.size()-1,qv*(a.size()-1));std::nth_element(a.begin(),a.begin()+k,a.end());return a[k];};float lo=q(.005),hi=q(.995);if(!(hi>lo))hi=lo+1e-3f;
  float gainR=1,gainG=1,gainB=1;if(mode!=0){double sr=0,sg=0,sb=0;int nr=0,ng=0,nb=0;for(int y=0;y<p.h;y+=3)for(int x=0;x<p.w;x+=3){float v=p.at(x,y);int c=cfaColor(cfa,x*factor,y*factor);if(v>.02f&&v<.8f){if(c==0){sr+=v;++nr;}else if(c==1){sg+=v;++ng;}else if(c==2){sb+=v;++nb;}}}double g=ng?sg/ng:1;if(nr)gainR=(float)std::max(.6,std::min(1.7,g/(sr/nr)));if(nb)gainB=(float)std::max(.6,std::min(1.7,g/(sb/nb)));}
  try{rgba.resize((size_t)outW*outH*4);}catch(...){return Status::OutOfMemory;}for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){float r,g,b;int rawX=std::min(W-1,x*factor),rawY=std::min(H-1,y*factor);float center=p.at(x,y);if(mode==0){r=g=b=stretchValue(center,lo,hi,stretch);}else{r=channelAt(p,cfa,x,y,0)*gainR;g=channelAt(p,cfa,x,y,1)*gainG;b=channelAt(p,cfa,x,y,2)*gainB;r=stretchValue(r,lo,hi,stretch);g=stretchValue(g,lo,hi,stretch);b=stretchValue(b,lo,hi,stretch);if(mode==2){r=std::pow(std::max(0.f,std::min(1.f,r)),.92f);g=std::pow(std::max(0.f,std::min(1.f,g)),.92f);b=std::pow(std::max(0.f,std::min(1.f,b)),.92f);}}size_t i=((size_t)y*outW+x)*4;rgba[i]=(uint8_t)(std::max(0.f,std::min(1.f,r))*255);rgba[i+1]=(uint8_t)(std::max(0.f,std::min(1.f,g))*255);rgba[i+2]=(uint8_t)(std::max(0.f,std::min(1.f,b))*255);rgba[i+3]=255;(void)rawX;(void)rawY;}
  return Status::Ok;
}
}
