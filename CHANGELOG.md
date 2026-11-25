# Changelog

## Phase 0 (0.0.1-phase0)
- Scaffold Gradle (AGP 8.13.0, Kotlin 2.1.21, Gradle 8.14.3 wrapper), modul `:app`.
- Layar diagnostik codec perangkat (bukan editor).
- Workflow GitHub Actions: test, lint, debug APK artifact.
- Dokumen audit Phase 0 dan inventaris lisensi.

## Phase 0B (0.0.3-phase0b)
- PoC Media3: konkatenasi, PiP multi-track, kecepatan konstan, speed ramp (SpeedProvider kustom), audio mix, preview vs export.
- `SpeedCurve` + unit test. Workflow: artifact `VEdit-Phase0B-debug-<run>-<sha>`, ringkasan versi/commit/sidik jari.
- Signing permanen tersedia via Secrets tetapi ditunda; build memakai debug key.

## Phase 0B v0.1.0
- Fix: `CompositionPlayer` preview (durationUs wajib). Verifikasi terukur: PiP, volume, mixing, ramp, A/V. Preview manual dengan Surface.
- APK langsung lewat GitHub Release; nama `VEdit-Phase0B-v0.1.0.apk`; versionName dari `gradle.properties`.
- Ikon adaptive sementara.

## Phase 1 v0.2.0 (sub-tahap 1.1-1.2)
- Mesin timeline murni + undo/redo, proyek JSON berversi dengan autosave atomik, impor SAF, shell editor dengan timeline. Preview belum ada.

## Phase 1 v0.3.0 (sub-tahap 1.3, preview)
- Preview nyata: `PreviewPlan` (logika murni: gap, lokasi playhead, deteksi perubahan struktur), `CompositionFactory` (Project ke Media3 Composition), `CompositionEngine` (CompositionPlayer), `ExoEngine` (fallback otomatis), `PreviewController` (state, seek, play/pause, penanganan galat).
- UI editor: panel SurfaceView 16:9, putar/jeda, waktu, status muat/galat, nama engine; playhead mengikuti pemutar; memilih klip memindah playhead; preview dibangun ulang (debounce 300 ms) saat struktur berubah (split/trim/reorder/impor/undo).
- Pemutar dijeda di `onStop` dan dilepas saat editor ditutup.
- Test baru: `PreviewPlanTest`, `PreviewControllerTest`, `EditorFlowTest`.
- Belum diuji perangkat. Docs: README ditulis ulang; koreksi jumlah test di laporan Phase 1.

## Phase 1 v0.4.0 (export awal + UI adaptif)
- Export MP4 nyata: `ExportController` memakai `CompositionFactory` yang sama dengan preview (Transformer, H.264/AAC), progres, batal, galat, salin ke Movies/VEdit via MediaStore (API 29+; di bawahnya tetap di folder app). `ExportNaming` (+ test) membersihkan nama file.
- UI adaptif: `BoxWithConstraints` memilih satu kolom (potret) atau dua panel (lebar >= 640dp dan landscape); lebar preview dibatasi tinggi layar; `safeDrawingPadding` untuk status/navigation bar dan cutout.
- Hasil uji perangkat v0.3.0 dari pengguna: video, gambar, audio berjalan lancar. v0.4.0 belum diuji perangkat.

## Phase 1 v0.5.0 (rasio kanvas + optimasi preview)
- Hasil uji perangkat v0.4.0 (OPPO A18, API 35, laporan pengguna): semua fitur utama dan export berhasil; preview agak lag; rasio export mengikuti 16:9 tetap.
- Model: `Project.canvas` (`CanvasSettings`: `AspectPreset` 16:9/9:16/1:1/4:5, `FitMode` FIT/FILL); schema 1 -> 2 dengan migrasi (proyek lama = 16:9 FIT). `TimelineEngine.setCanvas` (masuk undo/redo).
- `CanvasSpec`/`OutputQuality`/`ExportPlan`: rasio kanvas, resolusi keluaran, dan ukuran tampilan dipisah. Export 720p aktif; 1080p ada di kode tapi `exportEnabled=false`.
- `CompositionFactory.build(project, quality)`: Presentation mengikuti ukuran kanvas dan Fit/Fill; preview memakai `PREVIEW` (480), export `P720`.
- Preview: tidak membangun ulang pemutar bila struktur tidak berubah; metrik waktu siap (`lastBuildMs`) dan jumlah build tampil di panel.
- UI: pemilih rasio dan Fit/Fill di bawah preview; panel preview mengikuti rasio kanvas.
- Test: `CanvasTest` (dimensi semua preset, migrasi v1, undo/redo, round trip), tambahan `PreviewControllerTest`.


## Unreleased — UI/UX refresh
- Redesign awal beranda proyek dan panel editor dengan visual dark studio terinspirasi editor video mobile.
- Rapikan preview stage dan kontrol playback tanpa mengganti preview engine.
- Fix compile: tambahkan import `androidx.compose.foundation.background` yang terlewat pada `EditorScreen.kt`.
- Validasi CI ulang masih berjalan; belum ada APK yang dinyatakan siap.
