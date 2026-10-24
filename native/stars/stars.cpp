#include "stars.h"
#include <algorithm>
#include <cmath>
#include <limits>
namespace sari {
static float medianOf(std::vector<float>& v) { if (v.empty()) return 0.f; size_t m=v.size()/2; std::nth_element(v.begin(),v.begin()+m,v.end()); float o=v[m]; if(!(v.size()&1u)){std::nth_element(v.begin(),v.begin()+m-1,v.end());o=0.5f*(o+v[m-1]);} return o; }

// Median is taken over the whole frame. Noise is NOT the MAD around that global median:
// a sky gradient / vignetting / light-pollution ramp widens that distribution and inflates the
// "noise", pushing the detection threshold above faint stars. Instead the noise is measured from
// differences of adjacent pixels, which cancel any smooth background (stars are sparse, so the
// robust MAD of the differences is unaffected by them).
BgStats estimateBackground(const Plane& p) {
  BgStats r; if(!p.valid()) return r;
  size_t n=p.d.size(), step=std::max<size_t>(1,n/65536); std::vector<float> s; s.reserve(n/step+1);
  // Odd stride avoids always sampling the same column parity of a mosaic.
  if(step>1 && !(step&1u)) ++step;
  for(size_t i=0;i<n;i+=step) if(std::isfinite(p.d[i])) s.push_back(p.d[i]);
  if(s.empty()) return r; r.median=medianOf(s);
  std::vector<float> dv; dv.reserve(65536);
  const int sy=std::max(1,(int)std::sqrt((double)n/65536.0));
  for(int y=0;y<p.h;y+=sy) for(int x=0;x+1<p.w;x+=sy){ float a=p.at(x,y),b=p.at(x+1,y); if(std::isfinite(a)&&std::isfinite(b)) dv.push_back(a-b); }
  float noiseDiff=0.f;
  if(dv.size()>16){ for(float& v:dv) v=std::fabs(v); noiseDiff=1.4826f*medianOf(dv)/1.41421356f; }
  for(float& v:s) v=std::fabs(v-r.median); float noiseGlobal=1.4826f*medianOf(s);
  // Use the gradient-free estimate; the global one only as a fallback / upper sanity bound.
  r.noise = noiseDiff>0.f ? std::min(noiseDiff, noiseGlobal>0.f?noiseGlobal:noiseDiff) : noiseGlobal;
  if(!(r.noise>0.f)){
    // Heavily quantised data can give MAD == 0. Fall back to the mean absolute deviation so that
    // a real (but coarsely quantised) sky is not reported as "no stars".
    double acc=0; size_t c=0; for(float v:s){acc+=v;++c;} double mad=c?acc/c:0.0; if(mad>0) r.noise=(float)(1.2533*mad);
  }
  return r;
}
namespace { struct BgGrid { int B,gw,gh; std::vector<float> m; BgGrid(const Plane&p,int block):B(std::max(8,block)){gw=(p.w+B-1)/B;gh=(p.h+B-1)/B;m.assign((size_t)gw*gh,0.f);std::vector<float>s;for(int gy=0;gy<gh;++gy)for(int gx=0;gx<gw;++gx){s.clear();int x1=std::min(p.w,(gx+1)*B),y1=std::min(p.h,(gy+1)*B);for(int y=gy*B;y<y1;y+=2)for(int x=gx*B;x<x1;x+=2)if(std::isfinite(p.at(x,y)))s.push_back(p.at(x,y));m[(size_t)gy*gw+gx]=medianOf(s);}} float at(float x,float y)const{float fx=std::min(std::max(x/B-0.5f,0.f),(float)(gw-1)),fy=std::min(std::max(y/B-0.5f,0.f),(float)(gh-1));int ix=(int)fx,iy=(int)fy,jx=std::min(ix+1,gw-1),jy=std::min(iy+1,gh-1);float ax=fx-ix,ay=fy-iy;auto g=[&](int a,int b){return m[(size_t)b*gw+a];};return(1-ay)*((1-ax)*g(ix,iy)+ax*g(jx,iy))+ay*((1-ax)*g(ix,jy)+ax*g(jx,jy));}}; }

// 3x3 binomial smoothing ([1 2 1]x[1 2 1]/16): a cheap matched filter for a star of FWHM ~1.5-3 px.
// Non-finite samples are skipped; pixels with no finite support stay NaN.
static void smooth3(const Plane& p, Plane& s){
  s=Plane(p.w,p.h);
  static const float k[3]={1.f,2.f,1.f};
  for(int y=0;y<p.h;++y)for(int x=0;x<p.w;++x){
    float acc=0,ws=0;
    for(int j=-1;j<=1;++j){int yy=y+j;if(yy<0||yy>=p.h)continue;for(int i=-1;i<=1;++i){int xx=x+i;if(xx<0||xx>=p.w)continue;float v=p.at(xx,yy);if(!std::isfinite(v))continue;float w=k[i+1]*k[j+1];acc+=w*v;ws+=w;}}
    s.at(x,y)= ws>0? acc/ws : std::numeric_limits<float>::quiet_NaN();
  }
}

Status detectStars(const Plane&p,const StarParams&prm,std::vector<Star>&out){
  out.clear(); if(!p.valid()||p.w<16||p.h<16||prm.maxStars<=0)return Status::InvalidArgument;
  BgStats bs=estimateBackground(p); if(!(bs.noise>0))return Status::NoStars;
  BgGrid bg(p,prm.bgBlock);
  Plane sm; smooth3(p,sm);
  // Noise of the binomially smoothed image for white noise: sqrt(sum w^2) = 0.375 (2-D).
  const float smNoise=0.375f*bs.noise;
  const float thrSmooth=prm.sigma*smNoise;     // detection on the matched-filter image
  const int R=4; std::vector<Star> cand; size_t keep=(size_t)prm.maxStars; cand.reserve(std::min<size_t>(keep*2,1u<<16));
  for(int y=R;y<p.h-R;++y)for(int x=R;x<p.w-R;++x){
    float center=sm.at(x,y); if(!std::isfinite(center))continue;
    bool mx=true; for(int j=-1;j<=1&&mx;++j)for(int i=-1;i<=1;++i){if(!i&&!j)continue;float v=sm.at(x+i,y+j);if(!std::isfinite(v)||v>center){mx=false;break;} if(v==center&&(j<0||(j==0&&i<0))){mx=false;break;}}
    if(!mx)continue;
    float bgc=bg.at(x+.5f,y+.5f); if(center-bgc<thrSmooth)continue;
    // Raw (unsmoothed) support around the candidate.
    float rawC=p.at(x,y); if(!std::isfinite(rawC))continue;
    float peak=rawC; for(int j=-1;j<=1;++j)for(int i=-1;i<=1;++i){float v=p.at(x+i,y+j);if(std::isfinite(v))peak=std::max(peak,v);}
    if(peak>=prm.satLevel)continue;
    // Hot-pixel / cosmic-ray rejection: a real star's PSF leaks into the direct neighbours;
    // a single hot pixel does not. Only applied when the peak is bright enough for the
    // neighbour signal to be measurable above noise.
    float hot=peak-bgc;
    if(hot>8.f*bs.noise){
      float nmax=-1e30f; const int dx4[4]={-1,1,0,0},dy4[4]={0,0,-1,1};
      for(int k4=0;k4<4;++k4){float v=p.at(x+dx4[k4],y+dy4[k4]);if(std::isfinite(v))nmax=std::max(nmax,v-bgc);}
      if(nmax<std::max(0.10f*hot,1.5f*bs.noise))continue;
    }
    // Signed (unbiased) aperture flux in a 5x5 box, and its significance.
    double apFlux=0; int apN=0; for(int j=-2;j<=2;++j)for(int i=-2;i<=2;++i){float v=p.at(x+i,y+j);if(!std::isfinite(v))continue;apFlux+=v-bg.at(x+i+.5f,y+j+.5f);++apN;}
    if(apN<15||apFlux<=0||apFlux<3.0*bs.noise*std::sqrt((double)apN))continue;
    // Centroid / shape from background-subtracted positive weights.
    double sw=0,sx=0,sy=0,mxx=0,myy=0;
    for(int j=-R;j<=R;++j)for(int i=-R;i<=R;++i){float raw=p.at(x+i,y+j);if(!std::isfinite(raw))continue;float w=raw-bg.at(x+i+.5f,y+j+.5f);if(w<=1.0f*bs.noise)continue;sw+=w;sx+=w*i;sy+=w*j;}
    if(sw<=0)continue; double cx=sx/sw,cy=sy/sw;
    for(int j=-R;j<=R;++j)for(int i=-R;i<=R;++i){float raw=p.at(x+i,y+j);if(!std::isfinite(raw))continue;float w=raw-bg.at(x+i+.5f,y+j+.5f);if(w<=1.0f*bs.noise)continue;mxx+=w*(i-cx)*(i-cx);myy+=w*(j-cy)*(j-cy);}
    mxx/=sw;myy/=sw;double sxg=std::sqrt(std::max(mxx,1e-9)),syg=std::sqrt(std::max(myy,1e-9));
    float fwhm=(float)(2.355*std::sqrt((mxx+myy)/2));
    // Binned detection planes undersample small stars, so the lower bound is deliberately low;
    // single-pixel defects are handled by the neighbour-support test above.
    if(fwhm<0.9f||fwhm>20.f)continue;
    cand.push_back({(float)(x+cx+.5),(float)(y+cy+.5),(float)apFlux,fwhm,(float)(std::min(sxg,syg)/std::max(sxg,syg)),peak});
    if(cand.size()>=keep*8+64){std::nth_element(cand.begin(),cand.begin()+keep,cand.end(),[](const Star&a,const Star&b){return a.flux>b.flux;});cand.resize(keep);}
  }
  if(cand.empty())return Status::NoStars;std::sort(cand.begin(),cand.end(),[](const Star&a,const Star&b){return a.flux>b.flux;});if((int)cand.size()>prm.maxStars)cand.resize(prm.maxStars);out=std::move(cand);return Status::Ok;
}
}
