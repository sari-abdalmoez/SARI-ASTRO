#pragma once
#include "../stars/stars.h"
namespace sari {
// Similarity transform mapping REFERENCE coords -> TARGET coords:
//   x' = a*x - b*y + tx ;  y' = b*x + a*y + ty     (scale = hypot(a,b), rotation = atan2(b,a))
struct Transform {
  double a = 1, b = 0, tx = 0, ty = 0;
  void apply(double x, double y, double& ox, double& oy) const { ox = a * x - b * y + tx; oy = b * x + a * y + ty; }
};
struct RegParams { int maxStars = 30; double tol = 2.0; int minInliers = 6; double scaleTol = 0.03; double minSep = 10.0; };
struct RegResult { Transform t; int inliers = 0; double rms = 0, confidence = 0; };
// Star-pair hypothesis voting (RANSAC-like) + least-squares refinement. Robust to false matches.
Status registerStars(const std::vector<Star>& ref, const std::vector<Star>& tgt, const RegParams& p, RegResult& out);

// Bilinear-resample a tile of the reference grid from `src` through `t`, applying v*gain+offset.
// Pixels falling outside the source become NaN (ignored by the integrator).
Status warpTile(PlaneSource& src, const Transform& t, float gain, float offset, int x0, int y0, int tw, int th, float* out);
}  // namespace sari
