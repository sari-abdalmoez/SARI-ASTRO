# Build validation

The source package was reconstructed from the complete readable `sari` Android project in the SARI-ASTRO repository and preserves the existing C++ astrophotography engine.

Local validation performed before packaging:
- native C++17 engine compiled with warnings and sanitizers
- native registration/stacking/budget/quality smoke tests executed
- ZIP structure and file contents checked

Android APK compilation is intentionally left to the pinned GitHub Actions workflow because this execution environment does not have the repository's Android SDK/NDK cache. The workflow builds exactly one release APK (`SARI-Astro.apk`) and validates the arm64-v8a JNI library and APK signature.
