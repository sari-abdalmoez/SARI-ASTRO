#pragma once
#include "../stars/stars.h"
namespace sari {
struct FrameMetrics { int starCount=0; float fwhm=0,roundness=0,bgLevel=0,noise=0,snr=0,saturatedFrac=0; bool valid=false; };
FrameMetrics computeMetrics(const Plane&p,const StarParams&sp=StarParams());
std::vector<float> scoreFrames(const std::vector<FrameMetrics>&m);
struct Normalization { float gain=1.f,offset=0.f; };
Normalization estimateNormalization(const BgStats&ref,const BgStats&tgt);
}
