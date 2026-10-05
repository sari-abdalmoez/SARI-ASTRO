# SARI Astro professional processing fix

## Main fixes

1. **Full-resolution export**
   The old editor saved the 1600px UI preview as JPEG. The new editor keeps the preview for display only and exports the complete W×H master from the F32 stack as a lossless 16-bit RGB PNG.

2. **Better multi-frame alignment**
   The native stack now selects a high-quality reference frame, uses stricter star matching/inlier/RMS gates, and rejects weak registrations instead of letting them contaminate the master.

3. **Photometric normalization**
   Frame scaling is estimated from matched star fluxes plus background normalization instead of using noise ratio alone. Frame weights are quality-aware and robust rejection remains enabled.

4. **Post-stack denoise**
   Denoise is intentionally separate from registration/stacking. It is conservative, Bayer-aware, tile/strip based, and does not generate astronomical detail.

5. **Long-exposure RAW capture**
   The camera UI defaults to 7 s when supported and exposes 5 s/7 s/other long presets. A request is clamped to the sensor's real Camera2 limits. A sequence is still multiple true long-exposure RAW frames; total integration is the sum of those exposures.

6. **Professional in-app gallery**
   Camera -> Gallery -> multi-select light frames -> optional calibration masters -> Smart Stack -> Processing -> Final Master.

7. **Memory safety**
   The native stack remains tiled and disk-backed. The final PNG exporter processes small horizontal strips instead of loading the complete full-resolution RGB image into RAM.

## Validation

- CMake host build: passed.
- Native CTest: 1/1 passed.
- AddressSanitizer + UndefinedBehaviorSanitizer native test run: all tests passed.
- Full-resolution PNG export smoke test: passed, valid 16-bit RGB PNG.
- Android APK build was not executed in this environment because the Android SDK/NDK/Gradle toolchain is not installed here. The repository workflow is pinned to build and validate the single `SARI-Astro.apk` artifact.

## Important limitation

The app preserves the original captured DNG in MediaStore and stores a float32 processing sidecar in the SARI project. The in-app stacker operates on the captured project sidecars; importing arbitrary external DNG files is not added by this patch.
