# VEdit

Editor video Android native (Kotlin, Jetpack Compose, AndroidX Media3). Offline-first, tanpa login/iklan/watermark.

**Status: Phase 0.** Aplikasi baru berisi layar diagnostik perangkat (encoder/decoder nyata). Fitur editor belum tersedia.

Dokumen: [GDD](docs/GDD.md) | [TDD](docs/TDD.md) | [Development](docs/DEVELOPMENT.md) | [Laporan Phase 0](docs/PHASE0_REPORT.md) | [Lisensi dependency](docs/DEPENDENCIES.md)

## Build
CI (`.github/workflows/android-ci.yml`) menjalankan unit test, lint, dan `assembleDebug`, lalu mengunggah APK debug sebagai artifact `vedit-debug-apk`.
Lokal: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
