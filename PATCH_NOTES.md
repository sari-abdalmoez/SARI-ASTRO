# SARI Astro Fix Bundle

Target repository: `sari-abdalmoez/SARI-ASTRO`

## Included changes

- Replaced the diagnostic-only `MainActivity` with a real Camera2 preview/capture screen.
- Runtime RAW_SENSOR, manual ISO, manual exposure and manual focus detection.
- Real DNG capture through `DngCreator` into `Pictures/SARI Astro/RAW`.
- Astro sequence start/stop with real frame counter and elapsed capture time.
- Low-memory native planner: Safe mode is now strictly one worker.
- Star detector no longer allocates the full-resolution temporary smoothed `Plane`.
- CI builds only the release APK and uploads exactly one artifact:
  `SARI-Astro-APK` containing `SARI-Astro.apk`.
- CI validates APK size, APK integrity, arm64-v8a native library, and APK signing.

## Important limitation

The Android shell in the original repository did not contain a completed Android gallery/project/stacking controller. The existing C++ stacking, registration, quality, checkpoint/resume and native tests are preserved; this bundle does not claim that the entire desktop-grade processing graph is already exposed through Android UI.

## Applying

Copy the files in this ZIP over the matching paths in the repository. The build workflow is already under `.github/workflows/build.yml` at the repository root.
