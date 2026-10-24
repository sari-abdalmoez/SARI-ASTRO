#include "pipeline.h"
#include "../memory/progress.h"
#include "../quality/quality.h"
#include "../registration/registration.h"
#include "../stacking/stacking.h"
#include "../stars/stars.h"
#include "../core/cfa.h"
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
      out[i]=std::isfinite(v)?v:0.f; // signed: clipping at zero would bias faint-signal statistics
    }
    return Status::Ok;
  }
};


// Raw per-pixel noise (sigma) of a mosaic frame, from differences of same-channel neighbours (x, x+2).
// Differences cancel the smooth sky background; the median-based estimate ignores sparse stars.
static float estimateRawNoise(PlaneSource& src){
  const int W=src.width(),H=src.height(); if(W<8||H<8)return 0.f;
  const int sw=std::min(W,1024),rows=4; std::vector<float> buf((size_t)sw*rows),d; d.reserve(8*rows*sw);
  for(int k=0;k<8;++k){int y0=std::min(H-rows,(int)((long long)(H-rows)*(2*k+1)/16)),x0=std::max(0,(W-sw)/2);
    if(src.readRegion(x0,y0,sw,rows,buf.data())!=Status::Ok)continue;
    for(int r=0;r<rows;++r)for(int i=0;i+2<sw;++i){float a=buf[(size_t)r*sw+i],b=buf[(size_t)r*sw+i+2];if(std::isfinite(a)&&std::isfinite(b))d.push_back(std::fabs(a-b));}}
  if(d.size()<64)return 0.f; size_t m=d.size()/2;std::nth_element(d.begin(),d.begin()+m,d.end());return 1.4826f*d[m]/1.41421356f;
}

// Hot / warm pixel suppression in the spirit of Pixel Night Sight (Google Research, 2019): a pixel is an
// outlier when it exceeds the median of its same-colour neighbours by many sigma. Unlike a plain outlier
// filter this one keeps real star cores: a star also lights its direct neighbours, a hot pixel does not.
// Defective pixels are replaced by the neighbour median; everything else is passed through untouched.
struct HotPixelSource final : PlaneSource {
  PlaneSource* base; float sigma; float k;
  HotPixelSource(PlaneSource* b,float s,float kk=6.f):base(b),sigma(s),k(kk){}
  int width()const override{return base?base->width():0;}
  int height()const override{return base?base->height():0;}
  Status readRegion(int x0,int y0,int w,int h,float*out)override{
    if(!base||!out||w<=0||h<=0)return Status::InvalidArgument;
    const int W=width(),H=height(),m=2;
    const int rx0=std::max(0,x0-m),ry0=std::max(0,y0-m),rx1=std::min(W,x0+w+m),ry1=std::min(H,y0+h+m);
    const int rw=rx1-rx0,rh=ry1-ry0; std::vector<float> b;
    try{b.resize((size_t)rw*rh);}catch(...){return Status::OutOfMemory;}
    Status st=base->readRegion(rx0,ry0,rw,rh,b.data());if(st!=Status::Ok)return st;
    auto at=[&](int x,int y,float&v){if(x<0||y<0||x>=W||y>=H)return false;v=b[(size_t)(y-ry0)*rw+(x-rx0)];return std::isfinite(v);};
    const float thr=k*sigma;
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
      const int X=x0+x,Y=y0+y;float v=0;out[(size_t)y*w+x]=at(X,Y,v)?v:b[(size_t)(Y-ry0)*rw+(X-rx0)];
      if(!(sigma>0)||!std::isfinite(v))continue;
      float nb[8];int n=0;static const int ox[8]={-2,2,0,0,-2,2,-2,2},oy[8]={0,0,-2,2,-2,-2,2,2};
      for(int i=0;i<8;++i){float t;if(at(X+ox[i],Y+oy[i],t))nb[n++]=t;}
      if(n<5)continue;
      std::nth_element(nb,nb+n/2,nb+n);float med=nb[n/2];
      const float ex=v-med;if(ex<=thr)continue;
      float dmax=-1e30f;static const int dx4[4]={-1,1,0,0},dy4[4]={0,0,-1,1};
      for(int i=0;i<4;++i){float t;if(at(X+dx4[i],Y+dy4[i],t))dmax=std::max(dmax,t-med);}
      if(dmax>0.25f*ex+3.f*sigma)continue; // neighbours are lit too: a real star, keep it
      out[(size_t)y*w+x]=med;
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

Status stackProject(const std::vector<std::string>&lightPaths,int W,int H,const std::string&darkPath,const std::string&flatPath,const std::string&biasPath,const std::string&outputPath,const std::string&progressPath,const std::string&reportPath,int tile,int workers,int startTile,const std::function<bool(int,int)>& cancelOrContinue,PipelineReport&out,int cfa){
  out=PipelineReport();out.framesInput=(int)lightPaths.size();if(lightPaths.empty()||W<=0||H<=0||outputPath.empty()||tile<16||workers<1||startTile<0)return Status::InvalidArgument;
  std::vector<std::unique_ptr<F32FileSource>> bases; bases.reserve(lightPaths.size());
  for(auto&p:lightPaths){auto s=std::make_unique<F32FileSource>(p.c_str(),W,H);if(!s->ok())return Status::IoError;bases.push_back(std::move(s));}
  auto openCal=[&](const std::string&path)->std::unique_ptr<F32FileSource>{if(path.empty())return nullptr;auto s=std::make_unique<F32FileSource>(path.c_str(),W,H);if(!s->ok())return nullptr;return s;};
  auto dark=openCal(darkPath); if(!darkPath.empty()&&!dark)return Status::IoError;
  auto flat=openCal(flatPath); if(!flatPath.empty()&&!flat)return Status::IoError;
  auto bias=openCal(biasPath); if(!biasPath.empty()&&!bias)return Status::IoError;
  float flatNorm=1.f;if(flat){flatNorm=medianSample(*flat);if(!(flatNorm>1e-6f))flatNorm=1.f;}

  // Even factors average complete Bayer quads, so no CFA pattern reaches the detector.
  // (The old rule used factor 1 up to 2500 px, which fed the raw mosaic to the detector.)
  const int factor = (std::max(W,H)>5000)?4:((std::max(W,H)>=1000)?2:1);
  std::vector<FrameMetrics> metrics(lightPaths.size());
  std::vector<BgStats> bgs(lightPaths.size());
  std::vector<std::vector<Star>> stars(lightPaths.size());
  std::vector<PipelineFrameReport> frReport(lightPaths.size());
  std::vector<std::unique_ptr<CalibratedSource>> calSources;calSources.reserve(lightPaths.size());
  std::vector<std::unique_ptr<HotPixelSource>> hotSources;hotSources.reserve(lightPaths.size());
  std::vector<PlaneSource*> finalSrc(lightPaths.size(),nullptr);
  for(size_t i=0;i<bases.size();++i){
    PlaneSource* use=bases[i].get();
    if(dark||flat||bias){calSources.push_back(std::make_unique<CalibratedSource>(use,dark.get(),flat.get(),bias.get(),flatNorm));use=calSources.back().get();}
    if(cfa>=0){const float rn=estimateRawNoise(*use);if(rn>0){hotSources.push_back(std::make_unique<HotPixelSource>(use,rn));use=hotSources.back().get();}}
    finalSrc[i]=use;
    Plane proxy;
    Status s=readProxy(*use,factor,proxy);if(s!=Status::Ok)return s;
    StarParams sp;sp.maxStars=160;sp.sigma=4.5f;sp.bgBlock=32;
    metrics[i]=computeMetrics(proxy,sp);bgs[i]=estimateBackground(proxy);
    detectStars(proxy,sp,stars[i]);
    frReport[i].index=(int)i;frReport[i].stars=metrics[i].starCount;frReport[i].fwhm=metrics[i].fwhm;frReport[i].roundness=metrics[i].roundness;frReport[i].noise=metrics[i].noise;frReport[i].snr=metrics[i].snr;
  }
  auto scores=scoreFrames(metrics);for(size_t i=0;i<scores.size();++i)frReport[i].score=scores[i];
  size_t ref=0;float bestRef=-1.f;for(size_t i=0;i<stars.size();++i)if(metrics[i].valid&&!stars[i].empty()&&scores[i]>bestRef){bestRef=scores[i];ref=i;}
  if(bestRef<0){for(auto&fr:frReport)if(fr.reason.empty())fr.reason=(fr.stars==0)?"no-stars-detected":"low-quality";out.status=(int)Status::NoStars;out.frames=frReport;writeReport(reportPath,out);return Status::NoStars;}
  std::vector<FrameInput> inputs;inputs.reserve(bases.size());
  RegParams rp;rp.maxStars=48;rp.minInliers=8;rp.tol=2.0;rp.scaleTol=0.02;rp.minSep=14.0;
  for(size_t i=0;i<bases.size();++i){FrameInput f;f.src=finalSrc[i];f.include=false;f.t=Transform();f.gain=1.f;f.offset=0.f;f.weight=std::max(.05f,std::pow(std::max(0.f,scores[i])/100.f,1.7f));
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
  if(out.framesIncluded==0){out.status=(int)Status::RegistrationFailed;out.frames=frReport;writeReport(reportPath,out);return Status::RegistrationFailed;}
  if(startTile==0){std::ofstream clear(progressPath,std::ios::trunc);}
  F32OutputSink sink(outputPath,W,H,startTile);if(!sink.ok())return Status::IoError;
  StackParams sp;sp.tile=tile;sp.workers=workers;sp.startTile=startTile;sp.rej=Rejection::Winsorized;sp.kLow=3;sp.kHigh=3;sp.maxIter=5;sp.cfa=cfa;
  ProgressTracker tracker(1); const int tx=(W+tile-1)/tile,ty=(H+tile-1)/tile,total=tx*ty;tracker=ProgressTracker(total);tracker.start(0);auto start=Clock::now();
  auto cb=[&](int done,int totalTiles){double elapsed=std::chrono::duration<double>(Clock::now()-start).count();tracker.update(done,elapsed);writeProgress(progressPath,done,totalTiles,elapsed);out.tilesDone=done;out.tilesTotal=totalTiles;return cancelOrContinue?cancelOrContinue(done,totalTiles):true;};
  StackStats st;Status s=stackFrames(inputs,W,H,sp,sink,cb,st);out.status=(int)s;out.tilesDone=st.tilesDone;out.tilesTotal=st.tilesTotal;out.frames=frReport;writeReport(reportPath,out);writeProgress(progressPath,st.tilesDone,st.tilesTotal,std::chrono::duration<double>(Clock::now()-start).count());return s;
}

// Preview renderer. Large frames are reduced by averaging complete factor x factor blocks per CFA
// channel (factor is even for Bayer data), which gives true RGB with 2x..4x lower noise. The old
// code point-sampled one raw pixel per block (always the same CFA position) and then treated the
// decimated plane as a mosaic, which produced aliased, colour-corrupted previews.
static float stretchValue(float v,float lo,float hi,float amount){if(!(hi>lo))return 0.f;float x=std::max(0.f,std::min(1.f,(v-lo)/(hi-lo)));float a=std::max(.1f,amount);return std::asinh(a*x)/std::asinh(a);}
Status renderPreview(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,float denoise,const Tone&tone,int maxDim,std::vector<uint8_t>&rgba,int&outW,int&outH){
  if(cfa<0)mode=0;
  if(W<=0||H<=0||maxDim<=0)return Status::InvalidArgument;
  F32FileSource src(f32Path.c_str(),W,H);if(!src.ok())return Status::IoError;
  int factor=std::max(1,(std::max(W,H)+maxDim-1)/maxDim);
  if(cfa>=0){ if(factor<2)factor=2; if(factor&1)++factor; } // whole Bayer quads
  outW=(W+factor-1)/factor;outH=(H+factor-1)/factor;
  std::vector<Plane> pl; try{ for(int c=0;c<3;++c)pl.emplace_back(outW,outH); rgba.resize((size_t)outW*outH*4);}catch(...){return Status::OutOfMemory;}
  const int chunk=32; std::vector<float> row;
  for(int oy=0;oy<outH;oy+=chunk){
    int hh=std::min(chunk,outH-oy); int sy=oy*factor; int rh=std::min(H-sy,hh*factor);
    try{row.resize((size_t)W*rh);}catch(...){return Status::OutOfMemory;}
    Status s=src.readRegion(0,sy,W,rh,row.data()); if(s!=Status::Ok)return s;
    for(int y=0;y<hh;++y)for(int x=0;x<outW;++x){
      double acc[3]={0,0,0};int n[3]={0,0,0};
      const int x0=x*factor,y0=(oy+y)*factor;
      for(int yy=y0;yy<std::min(H,y0+factor);++yy)for(int xx=x0;xx<std::min(W,x0+factor);++xx){
        float v=row[(size_t)(yy-sy)*W+xx]; if(!std::isfinite(v))continue; int c=cfaChannel(cfa,xx,yy); acc[c]+=v;++n[c]; }
      const float nan=std::numeric_limits<float>::quiet_NaN();
      float v[3]; for(int c=0;c<3;++c)v[c]=n[c]?(float)(acc[c]/n[c]):nan;
      if(cfa<0){ double a=0;int m=0; for(int c=0;c<3;++c)if(n[c]){a+=acc[c];m+=n[c];} float L=m?(float)(a/m):nan; v[0]=v[1]=v[2]=L; }
      for(int c=0;c<3;++c)pl[c].at(x,oy+y)=v[c];
    }
  }
  // Neutral background + white balance on co-located quads (colour modes only).
  ChannelBalance bal; if(mode!=0)bal=computeChannelBalance(src,W,H,cfa);
  for(int i=0;i<outW*outH;++i){
    float r=pl[0].d[i],g=pl[1].d[i],b=pl[2].d[i];
    if(mode==0){ float L=(r+g+b)/3.f; if(cfa>=0){int k=0;float a=0;for(float t:{r,g,b})if(std::isfinite(t)){a+=t;++k;}L=k?a/k:L;} pl[0].d[i]=pl[1].d[i]=pl[2].d[i]=L; }
    else if(bal.measured){ pl[0].d[i]=bal.bg[1]+(r-bal.bg[0])*bal.gain[0]; pl[1].d[i]=g; pl[2].d[i]=bal.bg[1]+(b-bal.bg[2])*bal.gain[2]; }
  }
  if(denoise>0.001f){
    const float amount=std::min(1.f,std::max(0.f,denoise));
    for(int c=0;c<3;++c){
      if(mode==0&&c>0){pl[c]=pl[0];continue;}
      Plane f(outW,outH);
      for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
        float centerV=pl[c].at(x,y);if(!std::isfinite(centerV)){f.at(x,y)=centerV;continue;}
        float sum=centerV,ws=1.f;
        for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){if(!dx&&!dy)continue;int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=outW||ny>=outH)continue;float v=pl[c].at(nx,ny);if(!std::isfinite(v))continue;float d=std::fabs(v-centerV);float ww=std::exp(-0.35f*float(dx*dx+dy*dy))*std::exp(-d*d/(0.0004f+0.03f*amount));sum+=v*ww;ws+=ww;}
        float smooth=sum/ws;float edge=std::min(1.f,std::fabs(centerV-smooth)*20.f);float a=amount*0.55f*(1.f-edge);f.at(x,y)=centerV*(1.f-a)+smooth*a;
      }
      pl[c]=std::move(f);
    }
  }
  std::vector<float> sample;sample.reserve(65536);
  const int sy2=std::max(1,outH/256),sx2=std::max(1,outW/256);
  for(int y=0;y<outH;y+=sy2)for(int x=0;x<outW;x+=sx2){float a=0;int k=0;for(int c=0;c<3;++c){float t=pl[c].at(x,y);if(std::isfinite(t)){a+=t;++k;}}if(k)sample.push_back(a/k);if(sample.size()>=65536)break;}
  if(sample.empty())return Status::InvalidArgument;
  auto q=[&](double qv){std::vector<float>a=sample;size_t k=(size_t)std::min<double>(a.size()-1,qv*(a.size()-1));std::nth_element(a.begin(),a.begin()+k,a.end());return a[k];};
  float lo=q(.005),hi=q(.995);if(!(hi>lo))hi=lo+1e-3f;
  for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
    float r=pl[0].at(x,y),g=pl[1].at(x,y),b=pl[2].at(x,y);
    if(!std::isfinite(r))r=lo;if(!std::isfinite(g))g=lo;if(!std::isfinite(b))b=lo;
    r=stretchValue(r,lo,hi,stretch);g=stretchValue(g,lo,hi,stretch);b=stretchValue(b,lo,hi,stretch);
    if(mode==2){r=std::pow(std::max(0.f,std::min(1.f,r)),.92f);g=std::pow(std::max(0.f,std::min(1.f,g)),.92f);b=std::pow(std::max(0.f,std::min(1.f,b)),.92f);}
    applyTone(r,g,b,tone);
    size_t i=((size_t)y*outW+x)*4;rgba[i]=(uint8_t)(std::max(0.f,std::min(1.f,r))*255);rgba[i+1]=(uint8_t)(std::max(0.f,std::min(1.f,g))*255);rgba[i+2]=(uint8_t)(std::max(0.f,std::min(1.f,b))*255);rgba[i+3]=255;
  }
  return Status::Ok;
}

Status renderPreview(const std::string&f32Path,int W,int H,int cfa,int mode,float stretch,float denoise,int maxDim,std::vector<uint8_t>&rgba,int&outW,int&outH){
  return renderPreview(f32Path,W,H,cfa,mode,stretch,denoise,Tone(),maxDim,rgba,outW,outH);
}

} // namespace sari
