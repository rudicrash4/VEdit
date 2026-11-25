# VEdit

Editor video Android native (Kotlin, Jetpack Compose, AndroidX Media3). Offline-first, tanpa login/iklan/watermark.

**Status: Phase 1, sub-tahap 1.3 (preview) — versi 0.3.0.** Lolos unit test, lint, dan build di CI. **Preview video belum diverifikasi di perangkat fisik** (hanya terbukti kompilasi dan unit test logika).

## Fitur
Tersedia (kode ada; yang diuji perangkat: impor, timeline, layar editor v0.2.0):
- Proyek: buat, simpan otomatis (JSON berversi, tulis atomik), buka kembali.
- Impor video/gambar/audio lewat SAF; timeline video + audio dengan zoom, seleksi, playhead.
- Edit: split, trim, delete (ripple), duplicate, reorder, rotasi, crop (nilai disimpan di model), undo/redo.
- Preview (BARU di 0.3.0, belum diuji perangkat): `CompositionPlayer` Media3 dengan fallback ExoPlayer; putar/jeda, seek, playhead sinkron, gambar, audio tambahan, volume.

Dalam pengembangan / belum ada: thumbnail dan waveform; crop/rotasi pada export (preview menerapkannya lewat efek komposisi); export final; efek, transisi, filter; signing rilis permanen (ditunda).

## Build dan unduh
- CI (`.github/workflows/android-ci.yml`): unit test, lint, `assembleDebug`, APK debug (kunci debug sementara), GitHub Release `build-<run>` berisi APK langsung `VEdit-Phase1-v0.3.0.apk`; artifact Actions tetap berupa ZIP.
- Unduh terbaru: https://github.com/rudicrash4/VEdit/releases/latest/download/VEdit-Phase1-v0.3.0.apk
- Lokal: `./gradlew testDebugUnitTest lintDebug assembleDebug`.

## Dokumen
[GDD](docs/GDD.md) | [TDD](docs/TDD.md) | [Development](docs/DEVELOPMENT.md) | [Phase 0](docs/PHASE0_REPORT.md) | [Phase 0B](docs/PHASE0B_REPORT.md) | [Phase 1](docs/PHASE1_REPORT.md) | [Signing](docs/SIGNING.md) | [Dependency](docs/DEPENDENCIES.md) | [Changelog](CHANGELOG.md)
