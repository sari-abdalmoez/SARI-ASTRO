#include <cmath>
#include <cstdio>
#include <random>
#include "../memory/budget.h"
#include "../memory/progress.h"
#include "../quality/quality.h"
#include "../stacking/stacking.h"
using namespace sari;
static int g_fail = 0;
#define CHECK(c, ...) do { if (!(c)) { ++g_fail; std::printf("  FAIL %s:%d %s  ", __FILE__, __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct SStar { double x, y, amp; };
static std::vector<SStar> makeSky(int W, int H, int n, unsigned seed) {
  std::mt19937 g(seed); std::uniform_real_distribution<double> ux(20, W - 20), uy(20, H - 20), ua(0.25, 0.8);
  std::vector<SStar> s;
  while (static_cast<int>(s.size()) < n) {
    SStar c{ux(g), uy(g), ua(g)}; bool ok = true;
    for (auto& o : s) if (std::hypot(o.x - c.x, o.y - c.y) < 14) ok = false;
    if (ok) s.push_back(c);
  }
  return s;
}
static Plane render(int W, int H, const std::vector<SStar>& sky, const Transform& t, float noise, unsigned seed) {
  Plane p(W, H, 0.05f); std::mt19937 g(seed); std::normal_distribution<float> nd(0.f, noise); const double sg = 1.6;
  for (auto& s : sky) {
    double px, py; t.apply(s.x, s.y, px, py);
    for (int y = static_cast<int>(py) - 6; y <= static_cast<int>(py) + 6; ++y)
      for (int x = static_cast<int>(px) - 6; x <= static_cast<int>(px) + 6; ++x)
        if (x >= 0 && y >= 0 && x < W && y < H)
          p.at(x, y) += static_cast<float>(s.amp * std::exp(-((x + .5 - px) * (x + .5 - px) + (y + .5 - py) * (y + .5 - py)) / (2 * sg * sg)));
  }
  for (auto& v : p.d) v += nd(g); return p;
}
static Transform rot(double deg, double tx, double ty) { double r = deg * M_PI / 180; return {std::cos(r), std::sin(r), tx, ty}; }

static void testStarsAndRegistration() {
  std::printf("[registration]\n");
  const int W = 512, H = 384; auto sky = makeSky(W, H, 70, 1); Plane ref = render(W, H, sky, Transform(), 0.01f, 10); Transform truth = rot(0.7, 13.4, -8.7); Plane tgt = render(W, H, sky, truth, 0.01f, 11);
  std::vector<Star> a, b;
  CHECK(detectStars(ref, StarParams(), a) == Status::Ok && a.size() > 30, "stars=%zu", a.size()); CHECK(detectStars(tgt, StarParams(), b) == Status::Ok, "tgt");
  int matched = 0; double se = 0; for (auto& s : sky) for (auto& d : a) if (std::hypot(d.x - s.x, d.y - s.y) < 2) { se += std::hypot(d.x - s.x, d.y - s.y); ++matched; break; }
  CHECK(matched > 30 && se / matched < 0.25, "matched=%d meanErr=%.3f", matched, matched ? se / matched : -1.0);
  RegResult r; Status s = registerStars(a, b, RegParams(), r); CHECK(s == Status::Ok, "status=%s", statusName(s)); double worst = 0;
  for (double cx : {0.0, 511.0}) for (double cy : {0.0, 383.0}) { double ex, ey, tx, ty; r.t.apply(cx, cy, ex, ey); truth.apply(cx, cy, tx, ty); worst = std::max(worst, std::hypot(ex - tx, ey - ty)); }
  CHECK(worst < 0.3, "corner error %.3f px (inliers=%d rms=%.3f)", worst, r.inliers, r.rms);
  std::vector<Star> noisy = b; for (int i = 0; i < 8; ++i) noisy.insert(noisy.begin() + i, Star{float(30 + 50 * i), float(40 + 30 * i), 9999.f, 3, 1, 0.5f}); RegResult r2; CHECK(registerStars(a, noisy, RegParams(), r2) == Status::Ok, "false stars");
  Plane flat(256, 256, 0.1f); std::vector<Star> none; CHECK(detectStars(flat, StarParams(), none) == Status::NoStars, "flat frame"); CHECK(detectStars(Plane(), StarParams(), none) == Status::InvalidArgument, "empty plane"); RegResult r3; CHECK(registerStars(a, none, RegParams(), r3) == Status::NoStars, "no tgt stars"); std::vector<Star> few(a.begin(), a.begin() + 2); CHECK(registerStars(few, few, RegParams(), r3) == Status::RegistrationFailed, "too few");
}

struct Set { std::vector<Plane> pl; std::vector<MemorySource> src; std::vector<FrameInput> fr; };
static void buildSet(Set& s, int n, int W, int H, float noise, bool rotateShift) {
  auto sky = makeSky(W, H, std::min(60, W * H / 900), 5); s.pl.clear(); s.src.clear(); s.fr.clear(); std::vector<Star> refStars; std::vector<Plane> raw;
  for (int i = 0; i < n; ++i) raw.push_back(render(W, H, sky, i == 0 || !rotateShift ? Transform() : rot(0.3 * (i % 3) - 0.3, 5.0 * (i % 4) - 6, 3.0 * (i % 5) - 6), noise, 100 + i));
  s.pl = std::move(raw); for (auto& p : s.pl) s.src.emplace_back(&p); detectStars(s.pl[0], StarParams(), refStars);
  for (int i = 0; i < n; ++i) { FrameInput f; f.src = &s.src[i]; if (i > 0 && rotateShift) { std::vector<Star> st; detectStars(s.pl[i], StarParams(), st); RegResult r; if (registerStars(refStars, st, RegParams(), r) != Status::Ok) { ++g_fail; } f.t = r.t; } s.fr.push_back(f); }
}
static void testStacking() {
  std::printf("[stacking]\n"); const int W = 256, H = 200, N = 16; Set s; buildSet(s, N, W, H, 0.02f, true); StackParams sp; sp.tile = 64; Plane out(W, H); MemorySink sink(&out); StackStats st;
  CHECK(stackFrames(s.fr, W, H, sp, sink, nullptr, st) == Status::Ok, "stack"); float n1 = estimateBackground(s.pl[0]).noise, nN = estimateBackground(out).noise; CHECK(nN < 0.35f * n1, "noise not reduced enough");
  Set s2; buildSet(s2, N, W, H, 0.02f, true); for (auto& f : s2.fr) f.t = Transform(); Plane un(W, H); MemorySink us(&un); StackStats st2; stackFrames(s2.fr, W, H, sp, us, nullptr, st2); FrameMetrics ma = computeMetrics(out), mu = computeMetrics(un); CHECK(ma.starCount > mu.starCount * 2 || ma.fwhm < mu.fwhm * 0.8, "alignment gave no benefit");
}
static void testRejection() {
  std::printf("[rejection]\n"); const int W = 128, H = 128, N = 12; Set s; buildSet(s, N, W, H, 0.01f, false); StackParams avg; avg.rej = Rejection::None; avg.tile = 64; StackParams rej; rej.rej = Rejection::Winsorized; rej.tile = 64; Plane clean(W, H), a(W, H), b(W, H); MemorySink sc(&clean), sa(&a), sb(&b); StackStats st; stackFrames(s.fr, W, H, rej, sc, nullptr, st);
  for (int i = 0; i < N; ++i) for (int x = 0; x < W; ++x) s.pl[i].at(x, 10 + 8 * i) = 1.0f; stackFrames(s.fr, W, H, avg, sa, nullptr, st); stackFrames(s.fr, W, H, rej, sb, nullptr, st);
  double errAvg = 0, errRej = 0; for (int i = 0; i < N; ++i) for (int x = 0; x < W; ++x) { int y = 10 + 8 * i; errAvg = std::max<double>(errAvg, std::fabs(a.at(x, y) - clean.at(x, y))); errRej = std::max<double>(errRej, std::fabs(b.at(x, y) - clean.at(x, y))); }
  CHECK(errAvg > 0.05, "average should show trail"); CHECK(errRej < 0.02 && errAvg > 4 * errRej, "trail not rejected (%.4f)", errRej); StackParams sg; sg.rej = Rejection::Sigma; float v[8] = {1, 1.01f, .99f, 1, 1.02f, .98f, 9.f, 1}, w[8] = {1, 1, 1, 1, 1, 1, 1, 1}; int rj = 0; float r = integratePixel(v, w, 8, sg, &rj); CHECK(std::fabs(r - 1.f) < 0.02f && rj == 1, "r=%f rj=%d", r, rj); float nv[3] = {NAN, NAN, NAN}, nw[3] = {1, 1, 1}; CHECK(integratePixel(nv, nw, 3, sg, nullptr) == 0.f, "all-NaN");
}
static void testTilingResumeCancel() {
  std::printf("[tiling/resume/cancel]\n"); const int W = 200, H = 150, N = 8; Set s; buildSet(s, N, W, H, 0.02f, true); StackParams full; full.tile = 256; StackParams t = full; t.tile = 64; t.workers = 3; Plane a(W, H), b(W, H); MemorySink sa(&a), sb(&b); StackStats st;
  CHECK(stackFrames(s.fr, W, H, full, sa, nullptr, st) == Status::Ok, "full"); CHECK(stackFrames(s.fr, W, H, t, sb, nullptr, st) == Status::Ok, "tiled"); double md = 0; for (size_t i = 0; i < a.d.size(); ++i) md = std::max<double>(md, std::fabs(a.d[i] - b.d[i])); CHECK(md < 1e-6, "tile mismatch");
  Plane c(W, H, -1.f); MemorySink sc(&c); int done = 0; Status cs = stackFrames(s.fr, W, H, t, sc, [&](int d, int) { done = d; return d < 3; }, st); CHECK(cs == Status::Cancelled && done == 3, "cancel status=%s done=%d", statusName(cs), done); StackParams rs = t; rs.startTile = done; CHECK(stackFrames(s.fr, W, H, rs, sc, nullptr, st) == Status::Ok && st.tilesDone == st.tilesTotal, "resume"); md = 0; for (size_t i = 0; i < b.d.size(); ++i) md = std::max<double>(md, std::fabs(c.d[i] - b.d[i])); CHECK(md == 0.0, "resume differs %.3e", md);
  std::vector<FrameInput> none; CHECK(stackFrames(none, W, H, t, sc, nullptr, st) == Status::InvalidArgument, "no frames"); Plane other(10, 10); MemorySource om(&other); std::vector<FrameInput> bad(1); bad[0].src = &om; CHECK(stackFrames(bad, W, H, t, sc, nullptr, st) == Status::InvalidArgument, "size mismatch");
}
static void testFileSource() {
  std::printf("[streaming io]\n"); Plane p(37, 29); for (size_t i = 0; i < p.d.size(); ++i) p.d[i] = float(i) * 0.5f; const char* path = "/tmp/sari_test.f32"; CHECK(writeF32File(path, p) == Status::Ok, "write"); F32FileSource f(path, 37, 29); CHECK(f.ok(), "open"); std::vector<float> o(10 * 7), m(10 * 7); MemorySource ms(&p); CHECK(f.readRegion(5, 3, 10, 7, o.data()) == Status::Ok && ms.readRegion(5, 3, 10, 7, m.data()) == Status::Ok && o == m, "region"); CHECK(f.readRegion(30, 0, 10, 7, o.data()) == Status::InvalidArgument, "oob region"); F32FileSource wrong(path, 40, 29); CHECK(!wrong.ok(), "size mismatch"); F32FileSource missing("/tmp/does_not_exist.f32", 37, 29); CHECK(!missing.ok(), "missing file"); std::remove(path);
}
static void testBudget() {
  std::printf("[memory budget]\n"); const uint64_t GB = 1ull << 30; DeviceInfo low{2 * GB, 1 * GB, 8, 0}; PerfMode m = recommendMode(low); CHECK(m == PerfMode::Safe, "low-end -> safe"); StackPlan p = planStack(4000, 3000, 100, low, m); CHECK(p.feasible && p.peakBytes <= p.budgetBytes && p.workers <= 2 && p.tile >= 64, "plan"); DeviceInfo hot = low; hot.thermalLevel = 3; CHECK(planStack(4000, 3000, 100, hot, m).workers == 1, "thermal throttle"); DeviceInfo tiny{1 * GB, 100ull << 20, 4, 0}; CHECK(!planStack(8000, 6000, 2000, tiny, PerfMode::Safe).feasible, "infeasible"); DeviceInfo big{12 * GB, 8 * GB, 8, 0}; StackPlan pb = planStack(4000, 3000, 100, big, PerfMode::Pro); CHECK(pb.tile >= p.tile && pb.workers > p.workers, "pro uses more"); ProgressTracker pt(100); pt.start(0); pt.update(10, 1); CHECK(std::fabs(pt.etaSeconds() - 9.0) < 1e-6, "eta1"); pt.update(20, 3); CHECK(pt.etaSeconds() > 9.0 && pt.etaSeconds() < 16.0, "eta adapts"); ProgressTracker z(0); CHECK(z.fraction() == 0, "zero total");
}
static void testQuality() {
  std::printf("[quality]\n"); auto sky = makeSky(256, 256, 50, 7); std::vector<FrameMetrics> m; m.push_back(computeMetrics(render(256, 256, sky, Transform(), 0.01f, 1))); m.push_back(computeMetrics(render(256, 256, sky, Transform(), 0.08f, 2))); m.push_back(computeMetrics(Plane(256, 256, 0.2f))); m.push_back(computeMetrics(Plane())); auto sc = scoreFrames(m); CHECK(sc[0] > sc[1] && sc[2] == 0 && sc[3] == 0 && !m[2].valid, "ranking"); Normalization n = estimateNormalization({0.05f, 0.01f}, {0.3f, 0.02f}); CHECK(std::fabs(n.gain - 0.5f) < 1e-6f && std::fabs(0.3f * n.gain + n.offset - 0.05f) < 1e-6f, "norm");
}
int main() { testStarsAndRegistration(); testStacking(); testRejection(); testTilingResumeCancel(); testFileSource(); testBudget(); testQuality(); std::printf(g_fail ? "\n%d FAILURES\n" : "\nALL TESTS PASSED\n", g_fail); return g_fail ? 1 : 0; }
