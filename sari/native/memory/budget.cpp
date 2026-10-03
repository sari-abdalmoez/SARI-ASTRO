#include "budget.h"
#include <algorithm>
namespace sari {
PerfMode recommendMode(const DeviceInfo& d) {
  const uint64_t GB = 1ull << 30;
  if (d.totalRamBytes <= 3 * GB || d.cores <= 4 || d.thermalLevel >= 2) return PerfMode::Safe;
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
  if (m == PerfMode::Safe) wk = std::min(2, std::max(1, cores / 2));
  else if (m == PerfMode::Balanced) wk = std::min(4, std::max(2, cores / 2));
  else wk = std::min(6, std::max(2, cores - 2));
  wk = std::max(1, wk - std::max(0, d.thermalLevel));
  if (d.thermalLevel >= 3) wk = 1;
  p.workers = std::min(wk, cores);
  const int sizes[] = {2048, 1024, 512, 256, 128, 64};
  int maxDim = std::max(W, H);
  for (int s : sizes) {
    int side = std::min(s, maxDim);
    // frames*tile^2*4 (tile stack) + warp scratch/output (~3 tiles) + 8 MB fixed overhead
    uint64_t need = static_cast<uint64_t>(frames) * side * side * 4 + 3ull * side * side * 4 + 8 * MB;
    if (need <= p.budgetBytes) { p.tile = side; p.peakBytes = need; p.feasible = true; break; }
  }
  p.diskBacked = true;  // frames are always streamed from disk; never resident
  return p;
}
}  // namespace sari
