# Audit Migrasi Hybrid Rust Engine

Tanggal audit: 2026-11-28. Dasar: repository `rudicrash4/VEdit`, branch `main`. Dokumen ini hanya memuat hal yang diperiksa langsung dari kode/riwayat repository; status uji perangkat dikutip dari laporan pengguna, bukan dari pengukuran Claude.

## 1. Keadaan repository saat audit
- Commit awal migrasi: `25c9fcc` "VEdit v0.5.1 — UI/UX refresh" (dibuat oleh pemilik repo di antara v0.5.0 dan migrasi; versi aplikasi ternyata **0.5.1**, bukan 0.5.0 seperti di TDD migrasi). Release terbaru sebelum migrasi: `build-51` (`VEdit-Phase1-v0.5.1.apk`); `build-20` = v0.5.0.
- applicationId `id.vedit.editor` (debug: `.debug`), minSdk 24, target/compile 36, AGP 8.13, Kotlin 2.1.21, Media3 1.11.1, Compose BOM 2025.09.
- Satu modul Gradle `:app`; paket: `domain`, `data`, `ui`, `preview`, `export`, `poc`, `engine` (baru).
- 91 `@Test` (JVM) sebelum migrasi; tidak ada instrumentation test (tidak ada emulator/perangkat di CI).

## 2. Peta arsitektur aktual (sebelum migrasi)
| Paket | Tanggung jawab | Bergantung pada |
|---|---|---|
| `domain` | Model (`Project/Track/Clip/MediaSource/CanvasSettings`), `TimelineEngine` (operasi murni), `EditHistory`, `PreviewPlan`, `Canvas*`, `ExportNaming` | Kotlin murni + kotlinx.serialization |
| `data` | `ProjectCodec` (JSON berversi + migrasi), `ProjectStore` (file atomik + .bak), `Autosaver`, `MediaImporter` (SAF) | domain, Android |
| `preview` | `CompositionFactory` (Project -> Media3 `Composition`), `CompositionEngine` (CompositionPlayer), `ExoEngine` (cadangan), `PreviewController` | domain, Media3 |
| `export` | `ExportController` (Transformer, MediaStore) | preview.CompositionFactory |
| `ui` | `HomeScreen`, `EditorScreen`, `Timeline`, `PreviewPanel` | semuanya |
| `poc` | Eksperimen Phase 0B (SpeedProvider, analisis) | Media3 |

## 3. Daftar fitur dan status (kutipan laporan pengguna: OPPO A18 CPH2591, API 35)
| Fitur | Status uji |
|---|---|
| Impor video/gambar/audio (SAF), timeline, edit (split/trim/hapus/duplikat/geser), rotasi, crop | Diuji pengguna pada v0.4.0: berhasil |
| Preview video/gambar/audio, export MP4 720p | Diuji pengguna pada v0.4.0: berhasil (preview agak lag) |
| Autosave/buka ulang, undo/redo | Dicakup tes JVM; sebagian dilaporkan pengguna berjalan |
| Rasio kanvas 16:9/9:16/1:1/4:5 + Fit/Fill, preview resolusi rendah (v0.5.0) | Lolos CI; hasil uji perangkat TIDAK dilaporkan ke Claude |
| UI/UX refresh (v0.5.1) | Lolos CI; hasil uji perangkat tidak dilaporkan |
| Thumbnail, waveform, kecepatan UI, keyframe, teks, efek, export 1080p | Belum ada |

## 4. Peta dependensi dan batas modul
`domain` tidak bergantung pada Android (kecuali `formatTime`), sehingga cocok dipindahkan. `ui.EditorScreen` memanggil `TimelineEngine` dan `EditHistory` secara langsung (kopling yang diputus oleh `engine.EditorEngine`). `preview.CompositionFactory` mengonsumsi `domain.PreviewPlan` dan `CanvasSpec`.

## 5. Kandidat Rust vs tetap Kotlin
**Dipindah ke Rust (milestone ini):** model proyek + validasi, operasi timeline, riwayat undo/redo, kanvas/ukuran output/rencana export, perencana urutan klip/celah/locate, parsing/serialisasi JSON kontrak bridge.
**Tetap Kotlin:** UI Compose, SAF/impor media, penyimpanan file proyek (`ProjectStore`, `Autosaver`, migrasi schema `ProjectCodec`), `PreviewController` dan lifecycle `SurfaceView`, `ExportController` (Transformer, MediaStore), seluruh integrasi Media3. Alasan: terikat API/lifecycle Android; tidak ada keuntungan dari memindahkannya; (lihat keputusan Media3 di `TDD.md` bagian 23).
**Belum dimigrasikan (tahap berikut):** `CompositionFactory` masih memakai `PreviewPlan`/`CanvasSpec` Kotlin; versi Rust sudah ada dan teruji kesetaraannya (kanvas) tetapi belum menjadi sumber untuk Media3. Serialisasi file proyek tetap di Kotlin.

## 6. Media3: dipertahankan dan dievaluasi
Terbukti di perangkat pengguna: `CompositionPlayer` (preview multi-klip, gambar, audio), `Transformer` (export MP4 H.264/AAC 720p), efek Crop/Rotate/Presentation. Tidak ada bukti teknis yang mendukung penggantian (alternatif FFmpeg/MediaCodec kustom/crate Rust akan menambah ukuran APK, lisensi (FFmpeg LGPL/GPL), dan risiko hardware-codec tanpa pembanding terukur). Keputusan: **dipertahankan**; Rust hanya merencanakan. Lihat `TDD.md` bagian 23.

## 7. Risiko dan urutan kerja
1. (selesai) core Rust tanpa dependency, teruji lokal; 2. (selesai) bridge JNI + registri sesi; 3. (selesai) `EditorEngine` + feature switch Rust/Kotlin + differential test; 4. CI native (cargo-ndk, arm64-v8a + armeabi-v7a); 5. berikut: konsumsi rencana Rust oleh `CompositionFactory`, pindahkan serialisasi proyek, profil preview di perangkat.
Risiko: bug bridge hanya tampak di perangkat (mitigasi: cadangan Kotlin otomatis bila native gagal dimuat/dibuka, label engine di UI); dua implementasi bisa menyimpang (mitigasi: differential test acak di CI); ukuran APK bertambah (dua ABI).
