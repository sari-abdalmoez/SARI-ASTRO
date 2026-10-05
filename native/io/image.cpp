#include "../core/image.h"
#include <cstdio>
#include <cstring>
#include <sys/types.h>
namespace sari {
const char* statusName(Status s) {
  switch (s) { case Status::Ok: return "Ok"; case Status::InvalidArgument: return "InvalidArgument";
    case Status::NoStars: return "NoStars"; case Status::RegistrationFailed: return "RegistrationFailed";
    case Status::OutOfMemory: return "OutOfMemory"; case Status::IoError: return "IoError";
    case Status::Cancelled: return "Cancelled"; }
  return "Unknown";
}
static bool regionOk(int W, int H, int x0, int y0, int w, int h, const float* out) {
  return out && w > 0 && h > 0 && x0 >= 0 && y0 >= 0 && x0 + w <= W && y0 + h <= H;
}
Status MemorySource::readRegion(int x0, int y0, int w, int h, float* out) {
  if (!p || !p->valid() || !regionOk(p->w, p->h, x0, y0, w, h, out)) return Status::InvalidArgument;
  for (int y = 0; y < h; ++y)
    std::memcpy(out + static_cast<size_t>(y) * w, &p->d[static_cast<size_t>(y0 + y) * p->w + x0], sizeof(float) * w);
  return Status::Ok;
}
F32FileSource::F32FileSource(const char* path, int w, int h) : w_(w), h_(h) {
  if (path && w > 0 && h > 0) f_ = std::fopen(path, "rb");
  if (f_) {
    FILE* f = static_cast<FILE*>(f_); fseeko(f, 0, SEEK_END); off_t sz = ftello(f);
    if (sz != static_cast<off_t>(w) * h * 4) { std::fclose(f); f_ = nullptr; }
  }
}
F32FileSource::~F32FileSource() { if (f_) std::fclose(static_cast<FILE*>(f_)); }
Status F32FileSource::readRegion(int x0, int y0, int w, int h, float* out) {
  if (!f_ || !regionOk(w_, h_, x0, y0, w, h, out)) return Status::InvalidArgument;
  FILE* f = static_cast<FILE*>(f_);
  for (int y = 0; y < h; ++y) {
    off_t off = (static_cast<off_t>(y0 + y) * w_ + x0) * 4;
    if (fseeko(f, off, SEEK_SET) != 0) return Status::IoError;
    if (std::fread(out + static_cast<size_t>(y) * w, 4, w, f) != static_cast<size_t>(w)) return Status::IoError;
  }
  return Status::Ok;
}
Status writeF32File(const char* path, const Plane& p) {
  if (!path || !p.valid()) return Status::InvalidArgument;
  FILE* f = std::fopen(path, "wb"); if (!f) return Status::IoError;
  size_t n = std::fwrite(p.d.data(), 4, p.d.size(), f);
  bool ok = (std::fclose(f) == 0) && n == p.d.size(); return ok ? Status::Ok : Status::IoError;
}
}
