# SARI Astro
Mobile astrophotography platform: Kotlin/Android shell + C++17 native engine (tiled, bounded-RAM).

Toolchain (pinned): JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 26,
NDK 26.1.10909125, CMake 3.22.1, ABI arm64-v8a. CI: `.github/workflows/android.yml`.

Host tests (no Android needed): see the `native-tests` job; run `native/tests/test_main.cpp`.
