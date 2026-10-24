#pragma once
#include <algorithm>
#include <cmath>
#include <vector>
#include "image.h"
namespace sari {

// Colour of the CFA sample at (x,y): 0=R 1=G 2=B. cfa < 0 means a mono frame (single channel).
// Codes follow Android SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (RGGB=0, GRBG=1, GBRG=2, BGGR=3).
inline int cfaChannel(int cfa, int x, int y) {
  if (cfa < 0) return 1;
  const int px = x & 1, py = y & 1;
  switch (cfa & 3) {
    case 0: return (py == 0 && px == 0) ? 0 : ((py == 1 && px == 1) ? 2 : 1);
    case 1: return (py == 0 && px == 1) ? 0 : ((py == 1 && px == 0) ? 2 : 1);
    case 2: return (py == 0 && px == 0) ? 1 : ((py == 1 && px == 1) ? 1 : ((py == 0) ? 2 : 0));
    case 3: return (py == 0 && px == 0) ? 2 : ((py == 1 && px == 1) ? 0 : 1);
    default: return 1;
  }
}

// Background level and white-balance gain per channel, measured on co-located Bayer quads so the
// three channels describe the same sky position. Rendering uses  c' = bg[G] + (c - bg[c]) * gain[c]:
// the sky background becomes neutral (no green/magenta cast) and the signal is balanced by gain.
struct ChannelBalance { float bg[3] = {0, 0, 0}; float gain[3] = {1, 1, 1}; bool measured = false; };

inline ChannelBalance computeChannelBalance(PlaneSource& src, int W, int H, int cfa) {
  ChannelBalance out; if (cfa < 0 || W < 4 || H < 4) return out;
  const int qw = W / 2, qh = H / 2;
  const int S = std::max(1, (int)std::sqrt((double)qw * qh / 65536.0));
  std::vector<float> R, G, B; R.reserve(70000); G.reserve(70000); B.reserve(70000);
  std::vector<float> row((size_t)W * 2);
  for (int qy = 0; qy < qh; qy += S) {
    if (src.readRegion(0, qy * 2, W, 2, row.data()) != Status::Ok) return out;
    for (int qx = 0; qx < qw; qx += S) {
      float v[3] = {0, 0, 0}; int n[3] = {0, 0, 0}; bool ok = true;
      for (int j = 0; j < 2 && ok; ++j) for (int i = 0; i < 2; ++i) {
        float s = row[(size_t)j * W + qx * 2 + i]; if (!std::isfinite(s)) { ok = false; break; }
        int c = cfaChannel(cfa, qx * 2 + i, qy * 2 + j); v[c] += s; ++n[c];
      }
      if (!ok || !n[0] || !n[1] || !n[2]) continue;
      R.push_back(v[0] / n[0]); G.push_back(v[1] / n[1]); B.push_back(v[2] / n[2]);
    }
  }
  if (R.size() < 256) return out;
  auto med = [](std::vector<float> a) { size_t k = a.size() / 2; std::nth_element(a.begin(), a.begin() + k, a.end()); return a[k]; };
  out.bg[0] = med(R); out.bg[1] = med(G); out.bg[2] = med(B);
  std::vector<float> dev(G.size()); for (size_t i = 0; i < G.size(); ++i) dev[i] = std::fabs(G[i] - out.bg[1]);
  const float noiseG = 1.4826f * med(dev);
  if (!(noiseG > 0)) { out.measured = true; return out; }
  double sr = 0, sg = 0, sb = 0; size_t cnt = 0;
  for (size_t i = 0; i < G.size(); ++i) if (G[i] - out.bg[1] > 3.f * noiseG && G[i] < 0.9f) {
    sr += R[i] - out.bg[0]; sg += G[i] - out.bg[1]; sb += B[i] - out.bg[2]; ++cnt; }
  if (cnt >= 48 && sg > 0) {
    if (sr > 0) out.gain[0] = (float)std::max(0.6, std::min(1.7, sg / sr));
    if (sb > 0) out.gain[2] = (float)std::max(0.6, std::min(1.7, sg / sb));
  }
  out.measured = true; return out;
}
}
