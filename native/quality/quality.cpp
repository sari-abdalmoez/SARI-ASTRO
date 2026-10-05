#include "quality.h"
#include <algorithm>
#include <cmath>
namespace sari {
FrameMetrics computeMetrics(const Plane&p,const StarParams&sp){FrameMetrics m;if(!p.valid())return m;BgStats b=estimateBackground(p);m.bgLevel=b.median;m.noise=b.noise;size_t sat=0;for(float v:p.d)if(v>=sp.satLevel)++sat;m.saturatedFrac=(float)sat/p.d.size();std::vector<Star>s;if(detectStars(p,sp,s)!=Status::Ok||s.empty())return m;std::vector<float>f,r;for(auto&x:s){f.push_back(x.fwhm);r.push_back(x.roundness);}std::nth_element(f.begin(),f.begin()+f.size()/2,f.end());std::nth_element(r.begin(),r.begin()+r.size()/2,r.end());m.starCount=(int)s.size();m.fwhm=f[f.size()/2];m.roundness=r[r.size()/2];double flux=0;for(auto&x:s)flux+=x.flux;m.snr=b.noise>0?(float)(flux/s.size()/b.noise):0;m.valid=true;return m;}
std::vector<float>scoreFrames(const std::vector<FrameMetrics>&m){std::vector<float>sc(m.size(),0);int maxStars=0;float bestFwhm=1e30f,bestNoise=1e30f;for(auto&x:m)if(x.valid){maxStars=std::max(maxStars,x.starCount);bestFwhm=std::min(bestFwhm,x.fwhm);if(x.noise>0)bestNoise=std::min(bestNoise,x.noise);}if(!maxStars)return sc;for(size_t i=0;i<m.size();++i)if(m[i].valid){float a=(float)m[i].starCount/maxStars,b=bestFwhm/std::max(m[i].fwhm,1e-3f),c=m[i].roundness,d=m[i].noise>0?bestNoise/m[i].noise:1;sc[i]=100.f*(.35f*a+.30f*std::min(b,1.f)+.20f*c+.15f*std::min(d,1.f));}return sc;}
Normalization estimateNormalization(const BgStats&r,const BgStats&t){Normalization n;if(t.noise>0&&r.noise>0)n.gain=r.noise/t.noise;n.offset=r.median-t.median*n.gain;return n;}
}
