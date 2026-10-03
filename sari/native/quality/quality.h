#pragma once
#include "../stars/stars.h"
namespace sari {
struct FrameMetrics { int starCount = 0; float fwhm = 0, roundness = 0, bgLevel = 0, noise = 0, snr = 0, saturatedFrac = 0; bool valid = false; };
// Never throws and never fails hard: unusable frames get valid=false and score 0.
FrameMetrics computeMetrics(const Plane& p, const StarParams& sp = StarParams());
// Quality 0..100 relative to the batch: 35% star count, 30% FWHM, 20% roundness, 15% noise.
std::vector<float> scoreFrames(const std::vector<FrameMetrics>& m);
// v' = v*gain + offset maps a frame's background (median) and noise (MAD) onto the reference's.
struct Normalization { float gain = 1.f, offset = 0.f; };
Normalization estimateNormalization(const BgStats& ref, const BgStats& tgt);
}  // namespace sari
