#pragma once
#include <cstdint>
#include <string>
namespace sari {
enum class PerfMode : int { Safe = 0, Balanced = 1, Pro = 2 };
struct DeviceInfo { uint64_t totalRamBytes = 0, availRamBytes = 0; int cores = 1; int thermalLevel = 0; /*0 none..3 severe*/ };
struct StackPlan { int tile = 0, workers = 1; uint64_t peakBytes = 0, budgetBytes = 0; bool feasible = false, diskBacked = true; };
// Pick a tile size and worker count so predicted peak RAM stays inside a mode-dependent budget.
StackPlan planStack(int W, int H, int frames, const DeviceInfo& d, PerfMode m);
PerfMode recommendMode(const DeviceInfo& d);
}  // namespace sari
