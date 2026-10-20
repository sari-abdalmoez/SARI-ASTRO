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

## Round 3 – camera screen, gallery ads, name

- **Camera screen** (`MainActivity.kt`, `ui/CameraOverlay.kt`): brand row forced left-to-right so it reads
  "SARI ASTRO" (it previously reversed under RTL layout). Status cards show real EXP / ISO / RAW values.
  Rule-of-thirds grid and centre focus target drawn as a non-interactive overlay. ISO and exposure are now
  sliders (presets from the connected camera) inside a collapsible CONTROLS panel, with FOCUS and CALIBRATE
  there. The shutter is a round button with a camera-shutter icon; the active state shows a stop square.
  Capture, RAW, focus, calibration and gallery logic is unchanged.
- **Gallery ads** (`GalleryActivity.kt`): one AdMob banner spans both columns after every 6 projects.
  No ads are shown on the stacking, editor or camera screens. App ID is in the manifest; the ad unit ID is
  set in `GalleryActivity`. Use test ad units while developing to avoid invalid-traffic issues.
- **Name**: "SARI ASTRO" in the launcher label, gallery header, settings and camera brand.
- Protected code (stacking engine, JNI/C++, gallery storage and database, save/load logic) was not modified.

## Imported image stacking, launcher icon, premium panel, analytics

- Imported JPEG/PNG/WebP images are now converted into mono linear-light frames (CFA = -1, never Bayer) and can be stacked. Mixing them with camera RAW frames is blocked with a clear message.
- Imports detect the real format from file bytes, preserve the original in `originals/`, honour EXIF orientation, and decode in strips so full-resolution bitmaps are never held in RAM.
- Native preview/export force Mono for mono frames.
- Launcher icon: `mipmap-*` legacy icons plus adaptive icon (`mipmap-anydpi-v26`) built from the supplied artwork; manifest now sets `android:icon` and `android:roundIcon`.
- Gallery: UPGRADE panel (presentation only; no purchase backend exists yet).
- Google Analytics for Firebase added. Requires `app/google-services.json`; without it the plugin is skipped and analytics stays off.
