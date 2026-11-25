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
