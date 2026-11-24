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
