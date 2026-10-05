#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
namespace sari {
enum class Status : int { Ok = 0, InvalidArgument = 1, NoStars = 2, RegistrationFailed = 3, OutOfMemory = 4, IoError = 5, Cancelled = 6 };
const char* statusName(Status s);
struct Plane {
  int w = 0, h = 0; std::vector<float> d;
  Plane() = default;
  Plane(int W, int H, float v = 0.f) : w(W), h(H), d(static_cast<size_t>(W) * H, v) {}
  bool valid() const { return w > 0 && h > 0 && d.size() == static_cast<size_t>(w) * h; }
  float& at(int x, int y) { return d[static_cast<size_t>(y) * w + x]; }
  float at(int x, int y) const { return d[static_cast<size_t>(y) * w + x]; }
};
struct PlaneSource {
  virtual ~PlaneSource() = default;
  virtual int width() const = 0;
  virtual int height() const = 0;
  virtual Status readRegion(int x0, int y0, int w, int h, float* out) = 0;
};
struct MemorySource : PlaneSource {
  const Plane* p; explicit MemorySource(const Plane* pl) : p(pl) {}
  int width() const override { return p->w; } int height() const override { return p->h; }
  Status readRegion(int x0, int y0, int w, int h, float* out) override;
};
struct F32FileSource : PlaneSource {
  F32FileSource(const char* path, int w, int h); ~F32FileSource() override;
  bool ok() const { return f_ != nullptr; }
  int width() const override { return w_; } int height() const override { return h_; }
  Status readRegion(int x0, int y0, int w, int h, float* out) override;
 private: void* f_ = nullptr; int w_, h_;
};
Status writeF32File(const char* path, const Plane& p);
}
