#include "budget.h"
#include <algorithm>
namespace sari {
PerfMode recommendMode(const DeviceInfo& d) {
  const uint64_t GB = 1ull << 30;
  // A "4 GB" phone reports roughly 3.7 GB of total RAM, so the Safe cut-off sits above 4 GB.
  if (d.totalRamBytes <= 4 * GB + GB / 2 || d.cores <= 4 || d.thermalLevel >= 2) return PerfMode::Safe;
  if (d.totalRamBytes >= 8 * GB && d.cores >= 8 && d.thermalLevel == 0) return PerfMode::Pro;
  return PerfMode::Balanced;
}
StackPlan planStack(int W, int H, int frames, const DeviceInfo& d, PerfMode m) {
  StackPlan p;
  const uint64_t MB = 1ull << 20;
  if (W <= 0 || H <= 0 || frames <= 0) return p;
  double frac = m == PerfMode::Safe ? 0.20 : m == PerfMode::Balanced ? 0.30 : 0.45;
  uint64_t cap = m == PerfMode::Safe ? 384 * MB : m == PerfMode::Balanced ? 1024 * MB : 2048 * MB;
  uint64_t avail = d.availRamBytes ? d.availRamBytes : d.totalRamBytes / 2;
  p.budgetBytes = std::min<uint64_t>(static_cast<uint64_t>(avail * frac), cap);
  int cores = std::max(1, d.cores), wk;
  if (m == PerfMode::Safe) wk = 1;
  else if (m == PerfMode::Balanced) wk = std::min(2, std::max(1, cores / 4));
  else wk = std::min(4, std::max(2, cores - 4));
  wk = std::max(1, wk - std::max(0, d.thermalLevel));
  if (d.thermalLevel >= 3) wk = 1;
  p.workers = std::min(wk, cores);
  const int sizes[] = {2048, 1024, 512, 256, 128, 64};
  const int maxDim = std::max(W, H);
  for (int s : sizes) {
    int side = std::min(s, maxDim);
    // Real buffers per tile: frames x tile (warp output), output tile, calibration
    // scratch (4 planes) and warp region, plus a fixed allocator margin.
    const uint64_t planeBytes = static_cast<uint64_t>(side) * side * 4;
    uint64_t need = (static_cast<uint64_t>(frames) + 8) * planeBytes + 16 * MB;
    if (need <= p.budgetBytes) { p.tile = side; p.peakBytes = need; p.feasible = true; break; }
  }
  p.diskBacked = true;
  return p;
}
}
