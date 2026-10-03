#include "stacking.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
namespace sari {
Status MemorySink::writeTile(int x0, int y0, int w, int h, const float* d) {
  if (!p || !p->valid() || !d || x0 < 0 || y0 < 0 || x0 + w > p->w || y0 + h > p->h) return Status::InvalidArgument;
  for (int y = 0; y < h; ++y) std::memcpy(&p->d[static_cast<size_t>(y0 + y) * p->w + x0], d + static_cast<size_t>(y) * w, 4 * w);
  return Status::Ok;
}
namespace {
float medianArr(const float* a, int n, float* tmp) {
  std::memmove(tmp, a, sizeof(float) * n);
  std::nth_element(tmp, tmp + n / 2, tmp + n);
  float m = tmp[n / 2];
  if (n % 2 == 0) { float lo = *std::max_element(tmp, tmp + n / 2); m = 0.5f * (m + lo); }
  return m;
}
}  // namespace
float integratePixel(float* v, float* w, int n, const StackParams& p, int* rejected) {
  int m = 0;
  for (int i = 0; i < n; ++i) if (std::isfinite(v[i]) && w[i] > 0) { v[m] = v[i]; w[m] = w[i]; ++m; }
  if (m == 0) return 0.f;
  float tmp[512]; std::vector<float> big;
  float* tb = tmp; if (m > 512) { big.resize(m); tb = big.data(); }
  std::vector<char> keep(m, 1);
  int alive = m;
  if (p.rej == Rejection::Median) return medianArr(v, m, tb);
  if (p.rej != Rejection::None && m >= 5) {  // rejection statistics need enough samples
    const int iters = p.rej == Rejection::Sigma ? 1 : p.maxIter;
    for (int it = 0; it < iters; ++it) {
      int c = 0; for (int i = 0; i < m; ++i) if (keep[i]) tb[c++] = v[i];
      if (c < 5) break;
      float med = medianArr(tb, c, tb);
      for (int i = 0, k = 0; i < m; ++i) if (keep[i]) tb[k++] = std::fabs(v[i] - med);
      double sd = 1.4826 * medianArr(tb, c, tb);
      if (p.rej == Rejection::Winsorized) {  // Huber-style iterative robust sigma
        double s = sd;
        for (int j = 0; j < 5 && s > 0; ++j) {
          double lo = med - 1.5 * s, hi = med + 1.5 * s, acc = 0;
          for (int i = 0; i < m; ++i) if (keep[i]) { double x = std::min(std::max<double>(v[i], lo), hi) - med; acc += x * x; }
          double ns = 1.134 * std::sqrt(acc / c); if (std::fabs(ns - s) < 1e-6 * s) { s = ns; break; } s = ns;
        }
        sd = s;
      }
      // Floor guards against zero-MAD (quantised/flat) data rejecting everything.
      double floorSd = 1e-6 * std::max(1.0, std::fabs(static_cast<double>(med)));
      sd = std::max(sd, floorSd);
      bool changed = false;
      for (int i = 0; i < m; ++i) if (keep[i]) {
        double d = v[i] - med;
        if (d < -p.kLow * sd || d > p.kHigh * sd) { keep[i] = 0; --alive; changed = true; }
      }
      if (!changed || alive < 3) break;
    }
  }
  double sw = 0, sv = 0;
  for (int i = 0; i < m; ++i) if (keep[i]) { sw += w[i]; sv += static_cast<double>(w[i]) * v[i]; }
  if (rejected) *rejected += m - alive;
  return sw > 0 ? static_cast<float>(sv / sw) : 0.f;
}

Status stackFrames(std::vector<FrameInput>& all, int W, int H, const StackParams& p, OutputSink& sink,
                   const TileCallback& cb, StackStats& st) {
  st = StackStats();
  if (W <= 0 || H <= 0 || p.tile < 16 || p.workers < 1) return Status::InvalidArgument;
  std::vector<FrameInput*> fr;
  for (auto& f : all) if (f.include) {
    if (!f.src || f.src->width() != W || f.src->height() != H) return Status::InvalidArgument;
    fr.push_back(&f);
  }
  if (fr.empty()) return Status::InvalidArgument;
  const int n = static_cast<int>(fr.size());
  const int tx = (W + p.tile - 1) / p.tile, ty = (H + p.tile - 1) / p.tile, total = tx * ty;
  st.tilesTotal = total;
  if (p.startTile < 0 || p.startTile > total) return Status::InvalidArgument;
  st.tilesDone = p.startTile;
  std::vector<float> buf, out;
  try { buf.resize(static_cast<size_t>(n) * p.tile * p.tile); out.resize(static_cast<size_t>(p.tile) * p.tile); }
  catch (...) { return Status::OutOfMemory; }
  for (int ti = p.startTile; ti < total; ++ti) {
    int x0 = (ti % tx) * p.tile, y0 = (ti / tx) * p.tile;
    int tw = std::min(p.tile, W - x0), th = std::min(p.tile, H - y0);
    size_t px = static_cast<size_t>(tw) * th;
    for (int f = 0; f < n; ++f) {  // serial: file-backed sources are not thread-safe
      Status s = warpTile(*fr[f]->src, fr[f]->t, fr[f]->gain, fr[f]->offset, x0, y0, tw, th, buf.data() + f * px);
      if (s != Status::Ok) return s;
    }
    std::vector<uint64_t> rej(p.workers, 0);
    auto work = [&](int wi) {
      std::vector<float> v(n), w(n);
      int r0 = th * wi / p.workers, r1 = th * (wi + 1) / p.workers; int rj = 0;
      for (int y = r0; y < r1; ++y) for (int x = 0; x < tw; ++x) {
        size_t i = static_cast<size_t>(y) * tw + x;
        for (int f = 0; f < n; ++f) { v[f] = buf[f * px + i]; w[f] = fr[f]->weight; }
        out[i] = integratePixel(v.data(), w.data(), n, p, &rj);
      }
      rej[wi] = rj;
    };
    if (p.workers == 1) work(0);
    else {
      std::vector<std::thread> th2;
      try { for (int wi = 1; wi < p.workers; ++wi) th2.emplace_back(work, wi); } catch (...) { /* fall back below */ }
      work(0);
      for (auto& t : th2) t.join();
      if (static_cast<int>(th2.size()) != p.workers - 1) return Status::OutOfMemory;  // thread creation failed
    }
    for (auto r : rej) st.rejected += r;
    st.samples += static_cast<uint64_t>(n) * px;
    Status s = sink.writeTile(x0, y0, tw, th, out.data());
    if (s != Status::Ok) return s;
    st.tilesDone = ti + 1;
    if (cb && !cb(st.tilesDone, total)) return Status::Cancelled;
  }
  return Status::Ok;
}
}  // namespace sari
