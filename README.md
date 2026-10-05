# SARI Astro

Mobile astrophotography platform built on the existing Kotlin/Camera2 shell and C++17 native engine.

The fixed source keeps the original star detection, registration, quality metrics, bounded-RAM tiled stacker,
memory planner and native tests, then adds the missing Android project/processing bridge: RAW capture -> F32 project
sidecar -> frame selection -> quality/registration -> calibration -> tiled stacking -> resumable progress -> preview editor.

Toolchain: JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 26, NDK 26.1.10909125,
CMake 3.22.1, ABI arm64-v8a.

Important: the app never invents astronomical detail. The native color preview is a conservative demosaic/stretch of
sensor data. The full-resolution scientific stack is retained as linear F32 project data; the original DNG is also saved.
