#include "stars.h"
#include <algorithm>
#include <cmath>
namespace sari {
static float medianOf(std::vector<float>& v) {
  if (v.empty()) return 0.f;
  const size_t m = v.size() / 2;
  std::nth_element(v.begin(), v.begin() + m, v.end());
  float out = v[m];
  if ((v.size() & 1u) == 0u) {
    std::nth_element(v.begin(), v.begin() + m - 1, v.end());
    out = 0.5f * (out + v[m - 1]);
  }
  return out;
}
BgStats estimateBackground(const Plane& p) {
  BgStats r;
  if (!p.valid()) return r;
  const size_t n = p.d.size(), step = std::max<size_t>(1, n / 65536);
  std::vector<float> s; s.reserve(n / step + 1);
  for (size_t i = 0; i < n; i += step) if (std::isfinite(p.d[i])) s.push_back(p.d[i]);
  if (s.empty()) return r;
  r.median = medianOf(s);
  for (float& v : s) v = std::fabs(v - r.median);
  r.noise = 1.4826f * medianOf(s);
  return r;
}
namespace {
struct BgGrid {
  int B, gw, gh; std::vector<float> m;
  BgGrid(const Plane& p, int block) : B(std::max(8, block)) {
    gw = (p.w + B - 1) / B; gh = (p.h + B - 1) / B; m.assign(static_cast<size_t>(gw) * gh, 0.f);
    std::vector<float> s;
    for (int gy = 0; gy < gh; ++gy) for (int gx = 0; gx < gw; ++gx) {
      s.clear();
      int x1 = std::min(p.w, (gx + 1) * B), y1 = std::min(p.h, (gy + 1) * B);
      for (int y = gy * B; y < y1; y += 2) for (int x = gx * B; x < x1; x += 2)
        if (std::isfinite(p.at(x, y))) s.push_back(p.at(x, y));
      m[static_cast<size_t>(gy) * gw + gx] = medianOf(s);
    }
  }
  float at(float x, float y) const {
    float fx = std::min(std::max(x / B - 0.5f, 0.f), static_cast<float>(gw - 1));
    float fy = std::min(std::max(y / B - 0.5f, 0.f), static_cast<float>(gh - 1));
    int ix = static_cast<int>(fx), iy = static_cast<int>(fy);
    int jx = std::min(ix + 1, gw - 1), jy = std::min(iy + 1, gh - 1);
    float ax = fx - ix, ay = fy - iy;
    auto g = [&](int a, int b) { return m[static_cast<size_t>(b) * gw + a]; };
    return (1 - ay) * ((1 - ax) * g(ix, iy) + ax * g(jx, iy)) + ay * ((1 - ax) * g(ix, jy) + ax * g(jx, jy));
  }
};
}
Status detectStars(const Plane& p, const StarParams& prm, std::vector<Star>& out) {
  out.clear();
  if (!p.valid() || p.w < 16 || p.h < 16 || prm.maxStars <= 0) return Status::InvalidArgument;
  BgStats bs = estimateBackground(p);
  if (!(bs.noise > 0.f)) return Status::NoStars;
  BgGrid bg(p, prm.bgBlock);
  const int R = 4; const float thr = prm.sigma * bs.noise;
  std::vector<Star> cand;
  cand.reserve(prm.maxStars * 2);
  // Memory-safe local-maximum pass: no full-frame smoothed Plane is allocated.
  for (int y = R; y < p.h - R; ++y) for (int x = R; x < p.w - R; ++x) {
    float center = p.at(x, y); if (!std::isfinite(center)) continue;
    if (center - bg.at(x + .5f, y + .5f) < thr) continue;
    bool mx = true;
    for (int j = -1; j <= 1 && mx; ++j) for (int i = -1; i <= 1; ++i) {
      if (!i && !j) continue;
      float v = p.at(x + i, y + j);
      if (!std::isfinite(v) || v >= center) { mx = false; break; }
    }
    if (!mx) continue;
    double sw = 0, sx = 0, sy = 0, mxx = 0, myy = 0; float peak = center;
    for (int j = -R; j <= R; ++j) for (int i = -R; i <= R; ++i) {
      float raw = p.at(x + i, y + j); if (!std::isfinite(raw)) continue;
      peak = std::max(peak, raw);
      float w = raw - bg.at(x + i + .5f, y + j + .5f); if (w <= 0) continue;
      sw += w; sx += w * i; sy += w * j;
    }
    if (sw <= 0 || peak >= prm.satLevel) continue;
    double cx = sx / sw, cy = sy / sw;
    for (int j = -R; j <= R; ++j) for (int i = -R; i <= R; ++i) {
      float raw = p.at(x + i, y + j); if (!std::isfinite(raw)) continue;
      float w = raw - bg.at(x + i + .5f, y + j + .5f); if (w <= 0) continue;
      mxx += w * (i - cx) * (i - cx); myy += w * (j - cy) * (j - cy);
    }
    mxx /= sw; myy /= sw;
    double sxg = std::sqrt(std::max(mxx, 1e-9)), syg = std::sqrt(std::max(myy, 1e-9));
    float fwhm = static_cast<float>(2.355 * std::sqrt((mxx + myy) / 2));
    if (fwhm < 1.2f || fwhm > 20.f) continue;
    cand.push_back({static_cast<float>(x + cx + .5), static_cast<float>(y + cy + .5), static_cast<float>(sw), fwhm,
                    static_cast<float>(std::min(sxg, syg) / std::max(sxg, syg)), peak});
  }
  if (cand.empty()) return Status::NoStars;
  std::sort(cand.begin(), cand.end(), [](const Star& a, const Star& b) { return a.flux > b.flux; });
  if (static_cast<int>(cand.size()) > prm.maxStars) cand.resize(prm.maxStars);
  out = std::move(cand); return Status::Ok;
}
} // namespace sari
