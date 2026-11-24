# Laporan Phase 0B (PoC teknis Media3)

Status: **PoC sudah dibangun dan lolos CI; hasil perangkat nyata BELUM ada.** Tidak ada fitur yang boleh dianggap terbukti sebelum bagian "Hasil perangkat" diisi.

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

## Hasil perangkat (diisi setelah pengujian)
_Menunggu laporan dari tab "PoC 0B" → tombol "Salin laporan" di CPH2591 (API 35)._

## Keputusan arsitektur sementara (belum final)
- Model kurva kecepatan tetap milik VEdit (`SpeedCurve`, sumber kebenaran tunggal); Media3 hanya menerima hasil diskretisasinya.
- Fase 1 tidak boleh menjanjikan speed ramp mulus, multi-track audio, atau PiP sampai eksperimen B2/C/A2 lulus di perangkat.
