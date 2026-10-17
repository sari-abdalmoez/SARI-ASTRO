# SARI Astro – stability, stacking and UI update

## Native engine (C++)

- **Registration speed** (`native/registration/registration.cpp`): the star-pair search tested every
  reference pair against every target pair (O(R²·T²) candidates per frame). Target pairs are now
  sorted by length and each reference pair only visits candidates inside the `scaleTol` window, with an
  early exit once a candidate cannot beat the current best. Measured on 48 stars: **314 ms → 3.5 ms per frame**,
  same result (48/48 inliers, rms 0.0000 on the synthetic benchmark).
- **Worker fallback** (`native/stacking/stacking.cpp`): if a worker thread cannot be created, its row band now
  runs on the calling thread instead of failing the whole stack with OutOfMemory.
- **Rejection safety**: a rejection pass never leaves fewer than three samples per pixel, so a few frames
  cannot strip a faint signal down to nothing. Per-pixel heap allocation was replaced with per-thread scratch.
- **Memory planner** (`native/memory/budget.cpp`): the budget now counts the real buffers (frame stack,
  output tile, calibration scratch, warp region, allocator margin). The Safe-mode RAM cut-off was raised to
  4.5 GB so "4 GB" phones (reported ≈3.7 GB) get Safe mode with one worker.
- **Calibration / warp buffers** are reused between reads instead of being reallocated for every tile.
- **Registration tolerance** scales with the proxy downsampling factor, so large sensors are not rejected
  for sub-pixel error that the lower-resolution star centroids cannot avoid.
- **Tone controls** (`native/core/tone.h`): brightness, colour saturation and nebula lift, applied in the
  same place for the live preview and the full-resolution 16-bit PNG export. Existing signatures are kept.

## Android app (Kotlin)

- **Stacking screen** (`ProcessingActivity.kt`): tile size is fixed for the whole job, so pause/resume
  uses a valid tile index (previously a re-plan could change the tile size mid-job). Device checks during the
  run drop to one worker when the phone is hot or below 160 MB available RAM, without changing the tile
  size. An out-of-memory result restarts with smaller tiles (down to 128 px) instead of failing. The result
  shows accepted and rejected frame counts with the reason for each rejection.
- **Result editor** (`EditorActivity.kt`): clear preview, five adjustments (Detail, Nebula visibility,
  Brightness, Colour, Noise reduction), a press-and-hold comparison with the default result for the current
  mode, a per-mode reset, and export to PNG, FITS and F32. The stacked master is never modified. Preview renders
  are latest-wins so slider drags do not queue work.
- **Design system** (`ui/SariKit.kt`): dark navy background, cyan accent, violet labels, rounded cards,
  segmented controls. The camera screen and theme use the same palette.

## Verification

- Native unit tests (`native/tests/test_main.cpp`) pass under AddressSanitizer and UBSan, including a new
  tone test.
- End-to-end pipeline test (10 synthetic frames with dark/bias calibration and hot pixels): all frames
  registered, cancel after 4 tiles, resume produces output bit-identical to an uninterrupted run, full-resolution
  PNG export succeeds.
- JNI bridge syntax-checked against a minimal JNI header stub (no JDK headers were available).

## Not verified here

- The Kotlin code and the Android build were not compiled: this environment has no Android SDK or Kotlin
  compiler. The GitHub Actions workflow is the first real compile.
- No device or real RAW benchmark was run. Speed figures above come from the synthetic native benchmarks only.
- The stacking kernel itself (iterative sigma rejection) was not made faster; the per-pixel allocation fix
  is measurably neutral on desktop. Large stacks remain compute-bound.
- Gallery and project screens were not redesigned in this pass.
