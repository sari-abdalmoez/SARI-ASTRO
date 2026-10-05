# SARI Astro

Mobile astrophotography platform built on the existing Kotlin/Camera2 shell and C++17 native engine.

The fixed source keeps the original star detection, registration, quality metrics, bounded-RAM tiled stacker,
memory planner and native tests, then adds the missing Android project/processing bridge: RAW capture -> F32 project
sidecar -> frame selection -> quality/registration -> calibration -> tiled stacking -> resumable progress -> preview editor.

Toolchain: JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 26, NDK 26.1.10909125,
CMake 3.22.1, ABI arm64-v8a.

Important: the app never invents astronomical detail. The native color preview is a conservative demosaic/stretch of
sensor data. The full-resolution scientific stack is retained as linear F32 project data; the original DNG is also saved.


## 1.1 processing improvements
- Full-resolution master remains on disk as F32; the preview is no longer used as the source for final export.
- Final export is a lossless 16-bit RGB PNG rendered from the full-resolution master.
- Final denoise is a separate, conservative, non-generative stage applied only to the master during export.
- Registration/stacking now chooses a high-quality reference frame, uses stricter star-consistency checks, and performs star-flux-based photometric normalization before robust rejection/stacking.
- Long-exposure RAW presets include 5 s and 7 s, and 7 s is the default when the camera supports it.


## 1.1.1 capture and export hardening
- The in-app Gallery is used directly from the camera screen and supports multi-frame RAW selection plus DARK/FLAT/BIAS masters.
- The default long-exposure preset is 7 s when the Camera2 sensor supports it; 5 s and other long-exposure presets are exposed when supported.
- Exposure requests are bounded by the camera's real exposure/frame-duration capabilities rather than pretending unsupported times are available.
- The stack stays linear F32 at full sensor dimensions; the 1600px preview is never used as the master export source.
- Full-resolution output is exported as lossless 16-bit RGB PNG with a separate non-generative edge-preserving denoise stage after stacking.
- Frame reference selection, star-registration thresholds, star-flux normalization, frame weighting and robust rejection were tightened to reduce star trails/ghosting from weak registrations.
- The preview demosaic was also improved so the on-screen result better matches the final export.
