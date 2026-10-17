#include "stacking.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>

namespace sari {

Status MemorySink::writeTile(int x0, int y0, int w, int h, const float* d) {
  if (!p || !p->valid() || !d || x0 < 0 || y0 < 0 || x0 + w > p->w || y0 + h > p->h) return Status::InvalidArgument;
  for (int y = 0; y < h; ++y)
    std::memcpy(&p->d[static_cast<size_t>(y0 + y) * p->w + x0], d + static_cast<size_t>(y) * w, sizeof(float) * w);
  return Status::Ok;
}

namespace {

// Median of a[0..n). Reorders a; the caller owns the scratch.
float medianInPlace(float* a, int n) {
  const int m = n / 2;
  std::nth_element(a, a + m, a + n);
  float med = a[m];
  if (n % 2 == 0) {
    const float lo = *std::max_element(a, a + m);
    med = 0.5f * (med + lo);
  }
  return med;
}

// Per-thread scratch. The previous implementation allocated a keep-mask and a
// large fallback vector for every single pixel, which dominated stacking time
// and fragmented the heap on low-memory devices.
struct PixelScratch {
  std::vector<float> tmp;
  std::vector<char> keep;
};
thread_local PixelScratch g_scratch;

}  // namespace

float integratePixel(float* v, float* w, int n, const StackParams& p, int* rejected) {
  int m = 0;
  for (int i = 0; i < n; ++i)
    if (std::isfinite(v[i]) && w[i] > 0.f) { v[m] = v[i]; w[m] = w[i]; ++m; }
  if (m == 0) return 0.f;

  PixelScratch& s = g_scratch;
  if (s.tmp.size() < static_cast<size_t>(m)) s.tmp.resize(static_cast<size_t>(m));
  float* tb = s.tmp.data();

  if (p.rej == Rejection::Median) {
    std::memcpy(tb, v, sizeof(float) * m);
    return medianInPlace(tb, m);
  }

  s.keep.assign(static_cast<size_t>(m), 1);
  char* keep = s.keep.data();
  int alive = m;

  if (p.rej != Rejection::None && m >= 5) {
    const int iters = p.rej == Rejection::Sigma ? 1 : p.maxIter;
    for (int it = 0; it < iters; ++it) {
      int c = 0;
      for (int i = 0; i < m; ++i) if (keep[i]) tb[c++] = v[i];
      if (c < 5) break;
      const float med = medianInPlace(tb, c);

      c = 0;
      for (int i = 0; i < m; ++i) if (keep[i]) tb[c++] = std::fabs(v[i] - med);
      double sd = 1.4826 * medianInPlace(tb, c);

      if (p.rej == Rejection::Winsorized) {
        // Iteratively winsorized sigma (Huber-style): converges to a robust
        // standard deviation that is not inflated by the outliers themselves.
        double s2 = sd;
        for (int j = 0; j < 5 && s2 > 0; ++j) {
          const double lo = med - 1.5 * s2, hi = med + 1.5 * s2;
          double acc = 0;
          for (int i = 0; i < m; ++i) if (keep[i]) {
            const double x = std::min(std::max<double>(v[i], lo), hi) - med;
            acc += x * x;
          }
          const double ns = 1.134 * std::sqrt(acc / c);
          const bool converged = std::fabs(ns - s2) < 1e-6 * s2;
          s2 = ns;
          if (converged) break;
        }
        sd = s2;
      }

      const double floorSd = 1e-6 * std::max(1.0, std::fabs(static_cast<double>(med)));
      sd = std::max(sd, floorSd);

      // Count first, then apply. Never let a rejection step leave fewer than
      // three samples: with a handful of frames that would discard the signal.
      int toReject = 0;
      for (int i = 0; i < m; ++i) if (keep[i]) {
        const double d = v[i] - med;
        if (d < -p.kLow * sd || d > p.kHigh * sd) ++toReject;
      }
      if (toReject == 0 || alive - toReject < 3) break;
      for (int i = 0; i < m; ++i) if (keep[i]) {
        const double d = v[i] - med;
        if (d < -p.kLow * sd || d > p.kHigh * sd) { keep[i] = 0; --alive; }
      }
    }
  }

  double sw = 0, sv = 0;
  for (int i = 0; i < m; ++i) if (keep[i]) {
    sw += w[i];
    sv += static_cast<double>(w[i]) * v[i];
  }
  if (rejected) *rejected += static_cast<int>(m - alive);
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

  const size_t tileArea = static_cast<size_t>(p.tile) * static_cast<size_t>(p.tile);
  const int workers = std::max(1, std::min(p.workers, p.tile));

  std::vector<float> buf, out;
  std::vector<std::vector<float>> vs(static_cast<size_t>(workers)), wsc(static_cast<size_t>(workers));
  try {
    buf.resize(static_cast<size_t>(n) * tileArea);
    out.resize(tileArea);
    for (int wi = 0; wi < workers; ++wi) {
      vs[wi].resize(static_cast<size_t>(n));
      wsc[wi].resize(static_cast<size_t>(n));
    }
  } catch (...) {
    return Status::OutOfMemory;
  }

  for (int ti = p.startTile; ti < total; ++ti) {
    const int x0 = (ti % tx) * p.tile, y0 = (ti / tx) * p.tile;
    const int tw = std::min(p.tile, W - x0), th = std::min(p.tile, H - y0);
    const size_t px = static_cast<size_t>(tw) * static_cast<size_t>(th);

    for (int f = 0; f < n; ++f) {
      Status s = warpTile(*fr[f]->src, fr[f]->t, fr[f]->gain, fr[f]->offset, x0, y0, tw, th,
                          buf.data() + static_cast<size_t>(f) * px);
      if (s != Status::Ok) return s;
    }

    std::vector<uint64_t> rej(static_cast<size_t>(workers), 0);
    auto band = [&](int wi) {
      std::vector<float>& v = vs[wi];
      std::vector<float>& w = wsc[wi];
      const int r0 = th * wi / workers, r1 = th * (wi + 1) / workers;
      uint64_t rj = 0;
      for (int y = r0; y < r1; ++y)
        for (int x = 0; x < tw; ++x) {
          const size_t i = static_cast<size_t>(y) * tw + x;
          for (int f = 0; f < n; ++f) {
            v[f] = buf[static_cast<size_t>(f) * px + i];
            w[f] = fr[f]->weight;
          }
          int pixelRejected = 0;
          out[i] = integratePixel(v.data(), w.data(), n, p, &pixelRejected);
          rj += static_cast<uint64_t>(pixelRejected);
        }
      rej[wi] = rj;
    };

    // Worker bands run on threads. If a thread cannot be created (low memory or
    // thread limits), that band is executed on the calling thread instead of
    // failing the whole stack.
    std::vector<std::thread> pool;
    std::vector<int> deferred;
    pool.reserve(static_cast<size_t>(workers > 1 ? workers - 1 : 0));
    for (int wi = 1; wi < workers; ++wi) {
      try { pool.emplace_back(band, wi); }
      catch (...) { deferred.push_back(wi); }
    }
    band(0);
    for (int wi : deferred) band(wi);
    for (auto& t : pool) t.join();

    for (auto r : rej) st.rejected += r;
    st.samples += static_cast<uint64_t>(n) * px;

    Status wr = sink.writeTile(x0, y0, tw, th, out.data());
    if (wr != Status::Ok) return wr;
    st.tilesDone = ti + 1;
    if (cb && !cb(st.tilesDone, total)) return Status::Cancelled;
  }
  return Status::Ok;
}

}  // namespace sari
