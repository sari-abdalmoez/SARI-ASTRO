#pragma once
#include <functional>
#include "../registration/registration.h"
namespace sari {
enum class Rejection : int { None = 0, Sigma = 1, IterSigma = 2, Winsorized = 3, Median = 4 };
struct FrameInput { PlaneSource* src = nullptr; Transform t; float gain = 1.f, offset = 0.f, weight = 1.f; bool include = true; };
struct StackParams {
  Rejection rej = Rejection::Winsorized; float kLow = 3.f, kHigh = 3.f; int maxIter = 6;
  int tile = 512; int workers = 1; int startTile = 0;  // startTile enables resume after cancel/crash
};
struct OutputSink { virtual ~OutputSink() = default;
  virtual Status writeTile(int x0, int y0, int w, int h, const float* data) = 0; };
struct MemorySink : OutputSink { Plane* p; explicit MemorySink(Plane* pl) : p(pl) {}
  Status writeTile(int x0, int y0, int w, int h, const float* d) override; };
struct StackStats { int tilesDone = 0, tilesTotal = 0; uint64_t rejected = 0, samples = 0; };
using TileCallback = std::function<bool(int done, int total)>;  // return false => cancel (tile already committed)

// Integrate one pixel stack (exposed for unit tests). Returns 0 when no valid samples.
float integratePixel(float* v, float* w, int n, const StackParams& p, int* rejected);
// Tile-streamed integration: peak RAM ~ nframes*tile^2*4 bytes, independent of image size.
Status stackFrames(std::vector<FrameInput>& frames, int W, int H, const StackParams& p,
                   OutputSink& sink, const TileCallback& cb, StackStats& stats);
}  // namespace sari
