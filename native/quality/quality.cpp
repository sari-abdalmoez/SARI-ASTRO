#include "quality.h"
#include <algorithm>
#include <cmath>
namespace sari {
FrameMetrics computeMetrics(const Plane&p,const StarParams&sp){FrameMetrics m;if(!p.valid())return m;BgStats b=estimateBackground(p);m.bgLevel=b.median;m.noise=b.noise;size_t sat=0;for(float v:p.d)if(v>=sp.satLevel)++sat;m.saturatedFrac=(float)sat/p.d.size();std::vector<Star>s;if(detectStars(p,sp,s)!=Status::Ok||s.empty())return m;std::vector<float>f,r;for(auto&x:s){f.push_back(x.fwhm);r.push_back(x.roundness);}std::nth_element(f.begin(),f.begin()+f.size()/2,f.end());std::nth_element(r.begin(),r.begin()+r.size()/2,r.end());m.starCount=(int)s.size();m.fwhm=f[f.size()/2];m.roundness=r[r.size()/2];double flux=0;for(auto&x:s)flux+=x.flux;m.snr=b.noise>0?(float)(flux/s.size()/b.noise):0;m.valid=true;return m;}
std::vector<float>scoreFrames(const std::vector<FrameMetrics>&m){
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
}
}
