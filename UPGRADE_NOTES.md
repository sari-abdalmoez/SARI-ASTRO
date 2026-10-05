# SARI Astro Professional Upgrade

This source is based on the previous `SARI-Astro-Professional-Fixed.zip` and keeps the existing Kotlin/Camera2 + C++17 architecture.

## App/UI
- Internal SARI Astro Gallery replaces the old system-gallery flow.
- Two-column project album grid with real sampled thumbnails, project names, RAW/import/calibration counts and project size.
- Project detail screen with three-column thumbnail grid, multi-select RAW frames, Select All/Clear, rename, Master and Import.
- New Project flow and import-to-existing-project flow.
- Thumbnail decoding is sampled; full-resolution RAW masters are never decoded just to render a card.

## Import
- Multi-file import from Android's document picker.
- DNG import preserves the original file under the project and creates a normalized F32 sidecar for processing.
- JPEG/PNG/WEBP imports are retained as view-only project media.
- The DNG parser is conservative: unsupported packed/compressed TIFF/DNG layouts are rejected rather than silently mis-decoded.

## Capture/stack processing
- Existing Camera2 long-exposure capture is retained, including real device capability limits.
- Project F32 light/calibration frames remain the processing representation.
- Frame scoring now considers star count, FWHM, roundness, SNR, background noise and saturation.
- Registration thresholds are tighter and each rejected frame gets a report reason.
- Photometric fallback no longer uses noise ratio as a brightness gain; it uses neutral gain plus background offset.
- Preview/export Bayer demosaic is deterministic and edge-aware.
- Full-resolution linear F32 remains the master; 1600px rendering is preview-only.
- Robust Winsorized/sigma rejection and weighted stacking remain tiled/bounded-RAM.
- Separate conservative non-generative export denoise is preserved.
- Processing keeps elapsed/ETA, pause/resume/cancel and checkpoint files.

## Export
- Full-resolution lossless 16-bit RGB PNG for sharing/display.
- Linear 32-bit floating-point FITS export with CFA metadata, no stretch and no invented detail.
- Raw F32 export remains available.

## Validation
- Native CMake/CTest: PASS (1/1).
- Native ASAN/UBSAN test executable: PASS (`ALL TESTS PASSED`).
- Android APK build was not run in this container because no Android SDK/Gradle installation is available here.
