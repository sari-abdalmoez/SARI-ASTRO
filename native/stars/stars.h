#pragma once
#include "../core/image.h"
namespace sari {
struct Star { float x, y, flux, fwhm, roundness, peak; };
struct StarParams { float sigma = 5.f; int maxStars = 200; float satLevel = 0.98f; int bgBlock = 64; };
struct BgStats { float median = 0, noise = 0; };
BgStats estimateBackground(const Plane& p);
Status detectStars(const Plane& p, const StarParams& prm, std::vector<Star>& out);
}
