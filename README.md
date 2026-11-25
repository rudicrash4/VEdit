# VEdit

Editor video Android native (Kotlin, Jetpack Compose, AndroidX Media3). Offline-first, tanpa login/iklan/watermark.

**Status: Phase 1 + export awal — versi 0.4.0.** Preview video/gambar/audio v0.3.0 dilaporkan berjalan lancar di perangkat (laporan pengguna). **Export MP4 dan tata letak adaptif (v0.4.0) baru lolos CI; belum diuji di perangkat.**

## Fitur
Tersedia (kode ada; yang diuji perangkat: impor, timeline, layar editor v0.2.0):
- Proyek: buat, simpan otomatis (JSON berversi, tulis atomik), buka kembali.
- Impor video/gambar/audio lewat SAF; timeline video + audio dengan zoom, seleksi, playhead.
- Edit: split, trim, delete (ripple), duplicate, reorder, rotasi, crop (nilai disimpan di model), undo/redo.
- Preview (v0.3.0, diuji pengguna: video, gambar, audio lancar): `CompositionPlayer` Media3 dengan fallback ExoPlayer; putar/jeda, seek, playhead sinkron.
- BARU v0.4.0 (belum diuji perangkat): export MP4 (H.264/AAC, 1280x720) dari komposisi yang sama dengan preview, dengan progres/batal/galat; disimpan ke Movies/VEdit (API 29+). UI adaptif: ponsel potret satu kolom; landscape/tablet dua panel; mengikuti system bars (safe drawing).

Dalam pengembangan / belum ada: thumbnail dan waveform; verifikasi hasil export di perangkat; kontrol kecepatan/keyframe di UI; teks; efek, transisi, filter; signing rilis permanen (ditunda).

## Build dan unduh
- CI (`.github/workflows/android-ci.yml`): unit test, lint, `assembleDebug`, APK debug (kunci debug sementara), GitHub Release `build-<run>` berisi APK langsung `VEdit-Phase1-v0.4.0.apk`; artifact Actions tetap berupa ZIP.
- Unduh terbaru: https://github.com/rudicrash4/VEdit/releases/latest/download/VEdit-Phase1-v0.4.0.apk
- Lokal: `./gradlew testDebugUnitTest lintDebug assembleDebug`.

## Dokumen
[GDD](docs/GDD.md) | [TDD](docs/TDD.md) | [Development](docs/DEVELOPMENT.md) | [Phase 0](docs/PHASE0_REPORT.md) | [Phase 0B](docs/PHASE0B_REPORT.md) | [Phase 1](docs/PHASE1_REPORT.md) | [Signing](docs/SIGNING.md) | [Dependency](docs/DEPENDENCIES.md) | [Changelog](CHANGELOG.md)
