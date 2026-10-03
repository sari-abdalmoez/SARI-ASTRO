#include "stars.h"
#include <algorithm>
#include <cmath>
namespace sari {
static float medianOf(std::vector<float>& v) {
  if (v.empty()) return 0.f;
  size_t m = v.size() / 2;
  std::nth_element(v.begin(), v.begin() + m, v.end());
  return v[m];
}
BgStats estimateBackground(const Plane& p) {
  BgStats r;
  if (!p.valid()) return r;
  size_t n = p.d.size(), step = std::max<size_t>(1, n / 65536);
  std::vector<float> s; s.reserve(n / step + 1);
  for (size_t i = 0; i < n; i += step) if (std::isfinite(p.d[i])) s.push_back(p.d[i]);
  if (s.empty()) return r;
  r.median = medianOf(s);
  for (auto& v : s) v = std::fabs(v - r.median);
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
      for (int y = gy * B; y < y1; y += 2) for (int x = gx * B; x < x1; x += 2) s.push_back(p.at(x, y));
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
}  // namespace
Status detectStars(const Plane& p, const StarParams& prm, std::vector<Star>& out) {
  out.clear();
  if (!p.valid() || p.w < 16 || p.h < 16 || prm.maxStars <= 0) return Status::InvalidArgument;
  BgStats bs = estimateBackground(p);
  if (!(bs.noise > 0.f)) return Status::NoStars;  // flat/empty frame
  BgGrid bg(p, prm.bgBlock);
  const int R = 4;
  Plane sm(p.w, p.h, 0.f);
  for (int y = 1; y < p.h - 1; ++y) for (int x = 1; x < p.w - 1; ++x) {
    float s = 0; for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i) s += p.at(x + i, y + j);
    sm.at(x, y) = s / 9.f;
  }
  const float thr = prm.sigma * bs.noise / 3.f;
  std::vector<Star> cand;
  for (int y = R; y < p.h - R; ++y) for (int x = R; x < p.w - R; ++x) {
    float v = sm.at(x, y);
    if (!std::isfinite(v) || v - bg.at(x + .5f, y + .5f) < thr) continue;
    bool mx = true;
    for (int j = -1; j <= 1 && mx; ++j) for (int i = -1; i <= 1; ++i) {
      if (!i && !j) continue;
      float u = sm.at(x + i, y + j);
      if ((j < 0 || (j == 0 && i < 0)) ? u >= v : u > v) { mx = false; break; }
    }
    if (!mx) continue;
    double sw = 0, sx = 0, sy = 0; float peak = -1e30f;
    for (int j = -R; j <= R; ++j) for (int i = -R; i <= R; ++i) {
      float raw = p.at(x + i, y + j); peak = std::max(peak, raw);
      float w = raw - bg.at(x + i + .5f, y + j + .5f); if (w <= 0) continue;
      sw += w; sx += w * i; sy += w * j;
    }
    if (sw <= 0 || peak >= prm.satLevel) continue;
    double cx = sx / sw, cy = sy / sw, mxx = 0, myy = 0;
    for (int j = -R; j <= R; ++j) for (int i = -R; i <= R; ++i) {
      float w = p.at(x + i, y + j) - bg.at(x + i + .5f, y + j + .5f); if (w <= 0) continue;
      mxx += w * (i - cx) * (i - cx); myy += w * (j - cy) * (j - cy);
    }
    mxx /= sw; myy /= sw;
    double sgx = std::sqrt(std::max(mxx, 1e-9)), sgy = std::sqrt(std::max(myy, 1e-9));
    float fwhm = static_cast<float>(2.355 * std::sqrt((mxx + myy) / 2));
    if (fwhm < 1.2f || fwhm > 20.f) continue;  // hot pixels / extended junk
    cand.push_back({static_cast<float>(x + cx + .5), static_cast<float>(y + cy + .5), static_cast<float>(sw), fwhm,
                    static_cast<float>(std::min(sgx, sgy) / std::max(sgx, sgy)), peak});
  }
  if (cand.empty()) return Status::NoStars;
  std::sort(cand.begin(), cand.end(), [](const Star& a, const Star& b) { return a.flux > b.flux; });
  if (static_cast<int>(cand.size()) > prm.maxStars) cand.resize(prm.maxStars);
  out = std::move(cand);
  return Status::Ok;
}
}  // namespace sari
