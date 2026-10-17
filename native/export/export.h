#pragma once
#include <string>
#include "../core/image.h"
#include "../core/tone.h"

namespace sari {

// Render the full-resolution stacked F32 master to a lossless 16-bit RGB PNG.
// Denoising is conservative and non-generative: it operates in the linear sensor
// domain before demosaic/stretch and uses only neighboring pixels from the master.
Status exportFullPng(const std::string& f32Path, int W, int H, int cfa, int mode,
                     float stretch, float denoiseStrength, const std::string& outputPath);

// Same as above with brightness / saturation / nebula controls applied before quantisation.
Status exportFullPng(const std::string& f32Path, int W, int H, int cfa, int mode,
                     float stretch, float denoiseStrength, const Tone& tone, const std::string& outputPath);

// Export the stacked linear Bayer master as a 32-bit floating-point FITS image.
Status exportLinearFits(const std::string& f32Path, int W, int H, int cfa,
                        const std::string& outputPath);

}
