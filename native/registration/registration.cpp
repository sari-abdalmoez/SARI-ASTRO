#include "registration.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace sari {
namespace {

struct P { double x, y; };

// Pair of target stars, pre-sorted by separation so each reference pair only
// visits the candidates whose length matches within scaleTol.
struct TargetPair { double len, ang; int k, l; };

// Counts reference stars that have a unique target within tol after applying t.
// When limit >= 0 the scan stops early once the candidate can no longer exceed
// limit; the returned value is then only guaranteed to be <= limit.
int collect(const std::vector<P>& R, const std::vector<P>& T, const Transform& t, double tol,
            std::vector<int>* ri, std::vector<int>* ti, int limit = -1) {
  int n = 0;
  const double tol2 = tol * tol;
  if (ri) { ri->clear(); ti->clear(); }
  std::vector<char> used(T.size(), 0);
  for (size_t i = 0; i < R.size(); ++i) {
    if (limit >= 0 && n + static_cast<int>(R.size() - i) <= limit) return n;
    double px, py;
    t.apply(R[i].x, R[i].y, px, py);
    double best = tol2;
    int bj = -1;
    for (size_t j = 0; j < T.size(); ++j) {
      if (used[j]) continue;
      const double d = (T[j].x - px) * (T[j].x - px) + (T[j].y - py) * (T[j].y - py);
      if (d < best) { best = d; bj = static_cast<int>(j); }
    }
    if (bj >= 0) {
      used[bj] = 1;
      ++n;
      if (ri) { ri->push_back(static_cast<int>(i)); ti->push_back(bj); }
    }
  }
  return n;
}

bool fit(const std::vector<P>& R, const std::vector<P>& T, const std::vector<int>& ri,
         const std::vector<int>& ti, Transform& o) {
  const size_t n = ri.size();
  if (n < 2) return false;
  double crx = 0, cry = 0, ctx = 0, cty = 0;
  for (size_t k = 0; k < n; ++k) {
    crx += R[ri[k]].x; cry += R[ri[k]].y;
    ctx += T[ti[k]].x; cty += T[ti[k]].y;
  }
  crx /= n; cry /= n; ctx /= n; cty /= n;
  double na = 0, nb = 0, den = 0;
  for (size_t k = 0; k < n; ++k) {
    const double rx = R[ri[k]].x - crx, ry = R[ri[k]].y - cry;
    const double tx = T[ti[k]].x - ctx, ty = T[ti[k]].y - cty;
    na += rx * tx + ry * ty;
    nb += rx * ty - ry * tx;
    den += rx * rx + ry * ry;
  }
  if (den < 1e-9) return false;
  o.a = na / den;
  o.b = nb / den;
  o.tx = ctx - (o.a * crx - o.b * cry);
  o.ty = cty - (o.b * crx + o.a * cry);
  return true;
}

}  // namespace

Status registerStars(const std::vector<Star>& ref, const std::vector<Star>& tgt, const RegParams& prm, RegResult& out) {
  out = RegResult();
  if (ref.empty() || tgt.empty()) return Status::NoStars;

  std::vector<P> R, T;
  for (size_t i = 0; i < ref.size() && static_cast<int>(i) < prm.maxStars; ++i) R.push_back({ref[i].x, ref[i].y});
  for (size_t i = 0; i < tgt.size() && static_cast<int>(i) < prm.maxStars; ++i) T.push_back({tgt[i].x, tgt[i].y});
  if (R.size() < 3 || T.size() < 3) return Status::RegistrationFailed;
  const int need = std::max(3, std::min(prm.minInliers, static_cast<int>(std::min(R.size(), T.size()))));

  // Target pair table, sorted by length. Previously every reference pair was
  // tested against every ordered target pair (O(R^2 * T^2) candidate
  // transforms per frame, each scored in O(R*T)); a window search on the
  // sorted lengths visits only plausible similarity transforms.
  std::vector<TargetPair> tp;
  tp.reserve(T.size() * (T.size() - 1));
  for (size_t k = 0; k < T.size(); ++k)
    for (size_t l = 0; l < T.size(); ++l) {
      if (k == l) continue;
      const double ex = T[l].x - T[k].x, ey = T[l].y - T[k].y;
      tp.push_back({std::hypot(ex, ey), std::atan2(ey, ex), static_cast<int>(k), static_cast<int>(l)});
    }
  std::sort(tp.begin(), tp.end(), [](const TargetPair& a, const TargetPair& b) { return a.len < b.len; });

  Transform best;
  int bestN = 0;
  for (size_t i = 0; i < R.size(); ++i)
    for (size_t j = i + 1; j < R.size(); ++j) {
      const double dx = R[j].x - R[i].x, dy = R[j].y - R[i].y, lr = std::hypot(dx, dy);
      if (lr < prm.minSep) continue;
      const double ar = std::atan2(dy, dx);
      const double lo = lr * (1.0 - prm.scaleTol), hi = lr * (1.0 + prm.scaleTol);
      auto it = std::lower_bound(tp.begin(), tp.end(), lo,
                                 [](const TargetPair& a, double v) { return a.len < v; });
      for (; it != tp.end() && it->len <= hi; ++it) {
        const double s = it->len / lr;
        const double ang = it->ang - ar;
        const double a = s * std::cos(ang), b = s * std::sin(ang);
        const int k = it->k;
        Transform t{a, b, T[k].x - (a * R[i].x - b * R[i].y), T[k].y - (b * R[i].x + a * R[i].y)};
        const int n = collect(R, T, t, prm.tol, nullptr, nullptr, bestN);
        if (n > bestN) { bestN = n; best = t; }
      }
    }
  if (bestN < need) return Status::RegistrationFailed;

  Transform cur = best;
  std::vector<int> ri, ti;
  double tol = prm.tol;
  for (int it = 0; it < 4; ++it) {
    if (collect(R, T, cur, tol, &ri, &ti) < need) break;
    Transform nt;
    if (!fit(R, T, ri, ti, nt)) break;
    cur = nt;
    tol = std::max(0.75, tol * 0.6);
  }
  const int n = collect(R, T, cur, std::max(1.0, tol), &ri, &ti);
  if (n < need) return Status::RegistrationFailed;

  double se = 0;
  for (int k = 0; k < n; ++k) {
    double px, py;
    cur.apply(R[ri[k]].x, R[ri[k]].y, px, py);
    se += (px - T[ti[k]].x) * (px - T[ti[k]].x) + (py - T[ti[k]].y) * (py - T[ti[k]].y);
  }
  out.t = cur;
  out.inliers = n;
  out.rms = std::sqrt(se / n);
  out.confidence = static_cast<double>(n) / std::min(R.size(), T.size());
  return Status::Ok;
}

Status warpTile(PlaneSource& src, const Transform& t, float gain, float offset,
                int x0, int y0, int tw, int th, float* out) {
  if (!out || tw <= 0 || th <= 0) return Status::InvalidArgument;
  const int W = src.width(), H = src.height();
  if (W <= 0 || H <= 0) return Status::InvalidArgument;

  const double cx[4] = {double(x0), double(x0 + tw - 1), double(x0), double(x0 + tw - 1)};
  const double cy[4] = {double(y0), double(y0), double(y0 + th - 1), double(y0 + th - 1)};
  double mnx = 1e30, mny = 1e30, mxx = -1e30, mxy = -1e30;
  for (int i = 0; i < 4; ++i) {
    double px, py;
    t.apply(cx[i], cy[i], px, py);
    mnx = std::min(mnx, px); mxx = std::max(mxx, px);
    mny = std::min(mny, py); mxy = std::max(mxy, py);
  }
  const float nan = std::numeric_limits<float>::quiet_NaN();
  if (mxx < 0 || mxy < 0 || mnx > W - 1 || mny > H - 1) {
    std::fill(out, out + static_cast<size_t>(tw) * th, nan);
    return Status::Ok;
  }

  const int bx0 = std::max(0, static_cast<int>(std::floor(mnx)) - 1);
  const int by0 = std::max(0, static_cast<int>(std::floor(mny)) - 1);
  const int bx1 = std::min(W - 1, static_cast<int>(std::ceil(mxx)) + 1);
  const int by1 = std::min(H - 1, static_cast<int>(std::ceil(mxy)) + 1);
  const int rw = bx1 - bx0 + 1, rh = by1 - by0 + 1;

  // Reused across calls (warping runs sequentially on the calling thread), so a
  // full stack does not churn the allocator once per frame per tile.
  thread_local std::vector<float> reg;
  try {
    reg.resize(static_cast<size_t>(rw) * rh);
  } catch (...) {
    return Status::OutOfMemory;
  }
  Status s = src.readRegion(bx0, by0, rw, rh, reg.data());
  if (s != Status::Ok) return s;

  for (int y = 0; y < th; ++y)
    for (int x = 0; x < tw; ++x) {
      double sx, sy;
      t.apply(x0 + x, y0 + y, sx, sy);
      float v = nan;
      if (sx >= 0 && sy >= 0 && sx <= W - 1 && sy <= H - 1) {
        const int ix = static_cast<int>(sx), iy = static_cast<int>(sy);
        const float fx = static_cast<float>(sx - ix), fy = static_cast<float>(sy - iy);
        const int jx = std::min(ix + 1, W - 1), jy = std::min(iy + 1, H - 1);
        auto g = [&](int a, int b) { return reg[static_cast<size_t>(b - by0) * rw + (a - bx0)]; };
        const float val = (1 - fy) * ((1 - fx) * g(ix, iy) + fx * g(jx, iy)) +
                          fy * ((1 - fx) * g(ix, jy) + fx * g(jx, jy));
        v = std::max(0.f, val * gain + offset);
      }
      out[static_cast<size_t>(y) * tw + x] = v;
    }
  return Status::Ok;
}

}  // namespace sari
