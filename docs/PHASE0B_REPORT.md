# Laporan Phase 0B (PoC teknis Media3)

Status: **PoC berjalan di perangkat (build pertama); perbaikan preview dan pengukuran tambahan sudah di build v0.1.0 tetapi belum diuji ulang di perangkat.** Tidak ada fitur yang boleh dianggap terbukti sebelum bagian "Hasil perangkat" diisi.

## Pemisahan bukti
| Level | Status |
|---|---|
| Unit test (`SpeedCurveTest`, 7 kasus: durasi konstan, integral rampa vs analitis, interpolasi, kontrak `SpeedProvider`) | Lolos di CI |
| Kompilasi + lint + `assembleDebug` | Lolos di CI (run 45469828697, commit fa31d29) |
| Perangkat fisik (export MP4, preview) | **Belum dijalankan** |

## Keputusan signing
Ditunda atas permintaan pengguna. Build memakai debug key sementara; APK antar-build mungkin perlu uninstall dulu. Konfigurasi signing permanen via Secrets sudah ada di workflow dan aktif otomatis jika secret dibuat (lihat `SIGNING.md`); jalur itu belum teruji.

## Apa yang diuji PoC (tab "PoC 0B")
Pengguna memilih video 1, video 2, dan opsional audio; tiap eksperimen mengekspor MP4 H.264/AAC sungguhan (klip dipotong maks 5 dtk), lalu memeriksa file dengan `MediaExtractor`/`MediaMetadataRetriever` (mime track, durasi, resolusi, ukuran) dan menampilkan satu frame hasil.

| ID | Eksperimen | Kriteria lulus otomatis | Perlu cek manual |
|---|---|---|---|
| A | Dua klip berurutan dalam satu `EditedMediaItemSequence` | H.264 + AAC ada, durasi = jumlah klip (toleransi 8% atau 300 ms) | Transisi antar klip tanpa glitch |
| A2 | Dua sequence paralel + `VideoCompositorSettings` (PiP kanan bawah) | Stream benar, durasi = klip terpanjang | Thumbnail: klip 2 harus kecil di kanan bawah; urutan `inputId` |
| B | Kecepatan konstan 1x, 0.5x, 2x via `setSpeed(SpeedProvider)` | Durasi = sumber / kecepatan | Audio terdengar wajar, A/V sinkron |
| B2 | Speed ramp 1x, 4x, 0.5x, kurva didiskretisasi 250 ms lewat `SpeedProvider` kustom | Durasi = integral analitis | Kehalusan; hentakan antar segmen |
| C | Video + sequence audio-only terpisah dengan `ChannelMixingAudioProcessor` (30%) | Stream audio ada, durasi benar | **Volume harus didengar**; belum ada pengukuran RMS |
| D | `CompositionPlayer` (tanpa Surface) melaporkan durasi komposisi A vs export A | Selisih <= 8% | Perilaku dengan Surface dan seek belum dicakup |

## Fakta API terverifikasi dari sumber Media3 1.11.1
- `EditedMediaItem.Builder#setSpeed(SpeedProvider)` publik; `SpeedProvider` punya `getSpeed(timeUs)` dan `getNextSpeedChangeTimeUs(timeUs)`, yaitu **kecepatan piecewise-constant**. Rampa mulus hanya bisa didekati dengan banyak segmen; tidak ada kurva kontinu bawaan.
- Jika `SpeedProvider` dipasang, efek pengubah kecepatan lain tidak diizinkan pada item itu.
- `CompositionPlayer`: `@ExperimentalApi`; speed-changing effect hanya sebagai efek pertama; beberapa sequence dengan video graph single-input memberi peringatan; setiap `EditedMediaItem` wajib punya `durationUs` agar bisa diputar.
- Audio sequence yang tumpang tindih dalam waktu dicampur ("will be mixed") tanpa dokumentasi algoritma.
- Koreksi lokasi paket: `OverlaySettings` dan `VideoCompositorSettings` ada di `androidx.media3.common`; builder statis di `androidx.media3.effect.StaticOverlaySettings`.

## Keterbatasan yang sudah diketahui
- Frame drop multi-sequence diperbaiki di Media3 1.12 hanya pada API 29+.
- Semua API ini `@UnstableApi`: versi Media3 harus dikunci dan diuji ulang tiap upgrade.
- Preview (ExoPlayer/CompositionPlayer) dan export adalah jalur render berbeda; paritas hanya diklaim untuk kasus yang lulus eksperimen D dan pemeriksaan manual.

## Hasil perangkat CPH2591 (Android API 35), build PoC pertama (laporan pengguna)
Level bukti: **perangkat fisik, otomatis hanya berupa durasi + keberadaan track**.

| Eksperimen | Diharapkan | Hasil | Selisih |
|---|---|---|---|
| A. Dua klip berurutan | 10000 ms | 10077 ms | +77 |
| A2. PiP | 5000 ms | 5062 ms | +62 |
| B. 1.0x | 5000 ms | 5062 ms | +62 |
| B. 0.5x | 10000 ms | 10092 ms | +92 |
| B. 2.0x | 2500 ms | 2577 ms | +77 |
| B2. Ramp 1x, 4x, 0.5x | 2624 ms | 2717 ms | +93 (3.5%) |
| C. Video + audio tambahan 30% | 5000 ms | 5062 ms | +62 |
| D. CompositionPlayer vs export | - | **FAIL** `IllegalStateException: null` | - |

Selisih +60 s/d +95 ms konsisten pada semua kasus (granularitas frame/container), jauh di bawah toleransi (8% atau 300 ms). Durasi 2x terbaca 2577 ms (bukan 5062) dan 0.5x terbaca 10092 ms, jadi pengubahan kecepatan konstan terbukti memengaruhi export. Ramp mengikuti integral analitis dalam 3.5%.

**Yang BELUM dibuktikan oleh hasil itu:** PASS pada A2 dan C hanya menunjukkan durasi dan track yang benar. Keberadaan dua lapisan video, pencampuran audio, kontrol volume, sinkronisasi, dan kehalusan ramp belum terukur. Itu sebabnya build berikutnya menambah pengukuran (di bawah).

## Penyebab preview gagal dan perbaikan
- **Penyebab (dari sumber Media3 1.11.1):** `CompositionPlayer` menghitung durasi komposisi lewat `EditedMediaItem.getPresentationDurationUs()`, yang mensyaratkan `durationUs != TIME_UNSET` dengan `checkState` tanpa pesan. PoC tidak mengisi `setDurationUs(...)` (Transformer boleh tanpa itu, CompositionPlayer tidak), sehingga muncul `IllegalStateException` dengan `message == null`. Bukan masalah surface/lifecycle.
- **Perbaikan:** semua `EditedMediaItem` kini memakai `setDurationUs(durasi sumber tanpa clipping)`. Export dan preview memakai definisi komposisi yang sama (satu builder). Exception saat menyiapkan player sekarang dibungkus (`Analysis.describeThrowable`) dan player selalu dilepas.
- **Verifikasi:** perbaikan sudah lolos kompilasi dan CI, **belum dijalankan di perangkat**.
- **Keputusan CompositionPlayer vs ExoPlayer:** CompositionPlayer tetap kandidat preview karena memakai definisi `Composition` yang sama dengan export (mendukung tujuan paritas), tetapi `@ExperimentalApi`. Evaluasi ulang (ExoPlayer per-klip atau preview kustom) hanya jika uji perangkat memperlihatkan masalah (lihat panel preview manual).

## Tambahan pada build Phase 0B v0.1.0
- **Verifikasi terukur otomatis** (`Analysis.kt` + pengukuran di `Runner.kt`, hasil muncul sebagai kartu E1 s/d E6):
  - E1 PiP: selisih luma area PiP vs klip 1 sendirian, dibanding area acuan.
  - E2 volume video 50%, E3 volume audio tambahan 30%: rasio RMS audio hasil dekode terhadap acuan 100%.
  - E4 mixing: RMS campuran vs prediksi energi dari komponen (asumsi tak berkorelasi).
  - E5 ramp: monotonisitas dan jeda maksimum timestamp frame vs batas pada kecepatan minimum.
  - E6: selisih akhir track audio vs video.
  - Ambang heuristik; gagal ambang = `INFO` (periksa manual), bukan klaim rusak.
- **Preview manual** (tombol A / PiP / Ramp / Mix): memutar komposisi yang sama di `SurfaceView` lewat `CompositionPlayer`.
- Eksperimen baru: C0 (volume video), C1/C2 (audio tambahan 100% dan 30%), D2/D3 (preview vs export untuk PiP dan ramp).

## Status per area (jujur)
| Area | CI/Unit test | Perangkat (otomatis) | Pemeriksaan manual |
|---|---|---|---|
| Multi-track berurutan | logika durasi diuji | durasi + track lolos | belum (transisi antar-klip) |
| Multi-track PiP | pixel diff diuji dengan data sintetis | durasi lolos; **dua lapisan belum terukur** | belum |
| Kecepatan konstan | integral diuji | durasi lolos (0.5x, 1x, 2x) | belum (A/V sinkron, pitch) |
| Speed ramp | integral + batas provider diuji | durasi dalam 3.5% | belum (kehalusan) |
| Audio tambahan/mixing/volume | rumus RMS diuji | durasi + track lolos; **mixing dan volume belum terukur** | belum (didengar) |
| Preview | durasi dan verdict diuji | **gagal sebelum perbaikan; belum diuji ulang** | belum |

## Distribusi APK
- Artifact Actions lama pada run terdahulu memang berupa ZIP. Workflow saat ini mengunggah satu file APK dengan `archive: false` (tanpa pembungkus ZIP).
- APK langsung: workflow membuat **GitHub Release** `build-<nomor run>` (ditandai terbaru) berisi aset `VEdit-<Tahap>-v<versi>.apk` hanya pada push ke `main`. PR/push ke branch fitur tidak menerbitkan Release. Tidak butuh Secrets untuk membuat Release; memakai `GITHUB_TOKEN` dengan izin `contents: write` pada job itu.
- Tautan: `https://github.com/rudicrash4/VEdit/releases/latest/download/VEdit-Phase0B-v0.1.0.apk` (selalu build terbaru dengan nama itu) atau tautan per build di ringkasan run.
- Penamaan: tahap dan versi dari `gradle.properties` (`vedit.stage`, `vedit.versionName`); `versionCode` = nomor run (selalu naik). Mengganti tahap/versi cukup mengubah dua baris itu.
- Verifikasi di CI: `apksigner verify` dan `aapt2 dump badging` (nama VEdit, ikon launcher, applicationId `id.vedit.editor.debug` tidak berubah). Ini bukan uji pemasangan di perangkat.

## Ikon aplikasi
Ikon sementara: latar gelap `#101224`, tombol play biru-ungu dan tiga klip timeline. Adaptive icon (vector foreground + warna latar + layer monochrome untuk ikon bertema) dan PNG fallback `mipmap-mdpi..xxxhdpi` (persegi dan bulat) untuk API 24-25. Label launcher `VEdit`. Diperiksa pada render pratinjau (bentuk lingkaran/squircle, ukuran 24 px tetap terbaca); belum dilihat di launcher perangkat.

## Keterbatasan Media3 yang terkonfirmasi
- Kecepatan hanya piecewise-constant (`SpeedProvider`), tanpa kurva kontinu.
- Semua API `@UnstableApi`; `CompositionPlayer` `@ExperimentalApi`.
- `CompositionPlayer` mewajibkan `durationUs` pada setiap item.
- Frame drop multi-sequence diperbaiki hanya API 29+ (1.12).

## Hasil perangkat build berikutnya (diisi setelah pengujian)
_Menunggu laporan dari tab "PoC 0B" pada APK v0.1.0 (kartu D1-D3 dan E1-E6) serta penilaian visual/audio dari panel preview manual._

## Keputusan arsitektur sementara (belum final)
- Model kurva kecepatan tetap milik VEdit (`SpeedCurve`, sumber kebenaran tunggal); Media3 hanya menerima hasil diskretisasinya.
- Fase 1 tidak boleh menjanjikan speed ramp mulus, multi-track audio, atau PiP sampai eksperimen B2/C/A2 lulus di perangkat.
