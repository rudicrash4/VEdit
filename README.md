# VEdit

Editor video Android native (Kotlin, Jetpack Compose, AndroidX Media3). Offline-first, tanpa login/iklan/watermark.

**Status: Phase 1 — versi 0.5.1 (UI/UX refresh).** Uji perangkat nyata v0.4.0 (OPPO A18 CPH2591, Android 15/API 35, laporan pengguna): impor, timeline, edit, rotasi, crop, preview (video/gambar/audio) dan export MP4 berhasil; preview terasa agak lag. **v0.5.0 (optimasi preview + rasio kanvas) lolos CI tetapi belum diuji di perangkat.**

## Fitur
Tersedia (kode ada; yang diuji perangkat: impor, timeline, layar editor v0.2.0):
- Proyek: buat, simpan otomatis (JSON berversi, tulis atomik), buka kembali.
- Impor video/gambar/audio lewat SAF; timeline video + audio dengan zoom, seleksi, playhead.
- Edit: split, trim, delete (ripple), duplicate, reorder, rotasi, crop (nilai disimpan di model), undo/redo.
- Preview (v0.3.0, diuji pengguna: video, gambar, audio lancar): `CompositionPlayer` Media3 dengan fallback ExoPlayer; putar/jeda, seek, playhead sinkron.
- Export MP4 (v0.4.0, berhasil di OPPO A18 menurut pengguna) (H.264/AAC, 1280x720) dari komposisi yang sama dengan preview, dengan progres/batal/galat; disimpan ke Movies/VEdit (API 29+). UI adaptif: ponsel potret satu kolom; landscape/tablet dua panel; mengikuti system bars (safe drawing).
- BARU v0.5.0 (belum diuji perangkat): rasio kanvas proyek 16:9 / 9:16 / 1:1 / 4:5 dan mode Fit/Fill, disimpan di proyek (schema 2) dan menentukan ukuran frame export (720p: 1280x720, 720x1280, 720x720, 720x900). Preview dirender pada resolusi lebih rendah (sisi pendek 480) daripada export; tampilan menunjukkan waktu siap pemutar yang terukur. 1080p disiapkan di kode tetapi DINONAKTIFKAN sampai terbukti di perangkat.
- BARU v0.5.1: penyegaran UI dark studio; beranda proyek, tema, preview stage/kontrol playback, dan tata panel editor dirapikan. Tidak mengubah model proyek, mesin timeline, autosave, preview engine, atau export controller.

Dalam pengembangan / belum ada: thumbnail dan waveform; verifikasi hasil export di perangkat; kontrol kecepatan/keyframe di UI; teks; efek, transisi, filter; signing rilis permanen (ditunda).

## Build dan unduh
- CI (`.github/workflows/android-ci.yml`): unit test, lint, `assembleDebug`, verifikasi APK, lalu pada push sukses ke `main` membuat GitHub Release `build-<run>` berisi APK langsung `VEdit-Phase1-v0.5.1.apk`. Artifact Actions diunggah sebagai satu file APK tanpa pembungkus ZIP.
- Unduh terbaru: https://github.com/rudicrash4/VEdit/releases/latest/download/VEdit-Phase1-v0.5.1.apk
- Lokal: `./gradlew testDebugUnitTest lintDebug assembleDebug`.

## Dokumen
[GDD](docs/GDD.md) | [TDD](docs/TDD.md) | [Development](docs/DEVELOPMENT.md) | [Phase 0](docs/PHASE0_REPORT.md) | [Phase 0B](docs/PHASE0B_REPORT.md) | [Phase 1](docs/PHASE1_REPORT.md) | [Signing](docs/SIGNING.md) | [Dependency](docs/DEPENDENCIES.md) | [Changelog](CHANGELOG.md)


## UI/UX refresh (PR #2 — v0.5.1)
- **HomeScreen:** beranda dark studio dengan kartu/daftar proyek dan aksi membuat proyek baru yang lebih menonjol.
- **MainActivity/theme:** tema gelap dan warna dasar aplikasi diselaraskan dengan identitas studio VEdit.
- **PreviewPanel:** preview stage dan kontrol playback ditata ulang agar area pratinjau lebih jelas; tetap memakai PreviewController dan SurfaceView yang ada.
- **EditorScreen:** susunan panel kanvas/preview, timeline, tools, dan media dirapikan; layout tetap adaptif untuk portrait dan layar lebar.
- Perubahan hanya pada presentasi UI Compose (serta import `background` yang diperlukan untuk kompilasi). Model proyek, mesin timeline, autosave, impor media, preview engine/controller, dan export controller dipertahankan.
- CI PR sebelumnya (#33) lulus; versi 0.5.1 menunggu CI ulang setelah version bump. Uji fisik UI versi ini masih diperlukan. Thumbnail dan waveform tetap belum tersedia.
