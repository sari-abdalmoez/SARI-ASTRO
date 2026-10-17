#pragma once
#include <algorithm>
#include <cmath>

namespace sari {

// Post-stretch display controls shared by preview and final export.
// brightness: additive offset in [-0.3, 0.3]
// saturation: 1 = neutral, 0 = monochrome, up to 2
// nebula:     0..1, lifts faint signal with a gamma below 1 (more visible nebulosity)
struct Tone {
  float brightness = 0.f;
  float saturation = 1.f;
  float nebula = 0.f;
};

inline void applyTone(float& r, float& g, float& b, const Tone& t) {
  const float sat = std::max(0.f, std::min(2.f, t.saturation));
  if (sat != 1.f) {
    const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    r = lum + (r - lum) * sat;
    g = lum + (g - lum) * sat;
    b = lum + (b - lum) * sat;
  }
  const float exponent = 1.f - 0.5f * std::max(0.f, std::min(1.f, t.nebula));
  const float bright = std::max(-0.3f, std::min(0.3f, t.brightness));
  auto shape = [&](float v) {
    v = std::max(0.f, std::min(1.f, v));
    v = std::pow(v, exponent) + bright;
    return std::max(0.f, std::min(1.f, v));
  };
  r = shape(r);
  g = shape(g);
  b = shape(b);
}

}  // namespace sari
