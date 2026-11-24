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
