# Desain Preview Engine V2 (v0.7.0)

Tujuan: pemutaran preview yang tidak bergantung pada `CompositionPlayer` untuk transisi klip. **Belum terbukti lebih baik di perangkat**; CompositionPlayer tetap tersedia dan dapat dipilih.

## 1. Alternatif yang dievaluasi
| Opsi | Penilaian |
|---|---|
| A. Tetap CompositionPlayer, tambal komposisi | Tidak mengatasi akar (hipotesis H1/H2); API eksperimental; tidak ada kontrol atas transisi. |
| B. **ExoPlayer + pengendali timeline sendiri (dipilih)** | Dekode/audio/seek dikerjakan ExoPlayer yang matang; kita memegang jadwal, jam, dan pertukaran slot. |
| C. MediaCodec langsung | Kontrol penuh, tetapi harus menulis demuxer (MediaExtractor), sinkronisasi A/V, resampling audio, kecepatan, seek akurat, dan penanganan format sendiri. Tidak ada bukti bahwa batasannya ada di ExoPlayer; kompleksitas dan risiko perangkat tidak sebanding. **Ditolak untuk saat ini**, ditinjau ulang bila B terbukti tidak cukup. |

## 2. Pembagian peran
- **Rust (`vedit-core::playback`)**: rencana pemutaran (`build_plan`: segmen VIDEO/IMAGE/GAP/AUDIO dengan waktu timeline, in/out sumber, kecepatan, volume, crop, rotasi; celah ≥1 ms eksplisit; tanpa celah ekor), pemetaan timeline↔sumber, `index_at` (setengah terbuka), dan `layout` (crop → rotasi searah jarum jam → Fit/Fill → pusat + persegi klip). API JSON: `nativePlan`, `nativeLayout` (API_VERSION 2).
- **Android (ExoPlayer/MediaCodec)**: dekode video/audio, render ke TextureView, seek.
- **Kotlin**: `TimelineSession` (orkestrasi, jam, sinkron), `ExoVisualSlot`/`ExoAudioSlot`, siklus hidup Surface, UI. `KotlinPlanProvider` = cadangan identik (tes paritas `PlanParityTest`).

## 3. Jam timeline
Saat segmen VIDEO diputar, **posisi pemain video = master**: `t = start + (src − srcIn)/speed`, dikunci ke `[start,end]`, tidak mundur. Saat gambar/celah/ekor: jam dinding. Jam **ditahan** saat pemain BUFFERING atau menunggu slot berikutnya. Audio tambahan mengikuti (koreksi drift: seek ulang bila selisih >120 ms, maksimal 1×/detik).

## 4. Transisi (inti perbaikan yang diusulkan)
Dua slot visual ping-pong. Saat segmen aktif jalan, segmen non-gap berikutnya **dipreload** di slot lain (jeda, tersembunyi, frame pertama dirender). Di batas: slot berikutnya dijadikan terlihat + `play()`, slot lama disembunyikan/dibersihkan — **tanpa membuat ulang pemain** dan tanpa seek. Slot berikutnya belum siap → jam ditahan di batas (frame terakhir tetap tampil), dengan batas waktu 4 dtk lalu retry sekali lalu error. Preload gagal → preload dimatikan dan lanjut berurutan (jeda singkat). Metrik: jumlah transisi, tunggu transisi terakhir/maks.

## 5. Gambar & celah
Gambar didekode (BitmapFactory, sisi ≤2048, EXIF) ke `ImageView` (matrix dari layout Rust); durasi = durasi timeline; jam dinding. Celah = layar hitam, jam berjalan, audio tambahan tetap mengikuti.

## 6. Audio
Audio dari video diputar pemain video (volume/kecepatan klip). Audio tambahan: dua `ExoAudioSlot` ping-pong dengan preload; mulai tepat waktu; berhenti di akhir. **Batasan:** volume >1.0 dijepit ke 1.0 (ExoPlayer); kecepatan memakai `setPlaybackSpeed` (pitch tetap) — bisa berbeda halus dari export.

## 7. Seek & buffering
Seek dalam segmen sama (>40 ms) → `seekTo` pemain; lintas segmen → slot preload dipakai ulang bila cocok, selain itu slot bebas disiapkan; frame lama tetap tampil sampai slot baru siap. Mulai dari klip tengah: hanya segmen itu disiapkan (tes #9).

## 8. Surface & geometri
Per slot: `FrameLayout{TextureView, ImageView}` di dalam host. TextureView merentangkan frame ke view, jadi transform = `M · S⁻¹` (`preScale(vw/W, vh/H)`); `clipBounds` dari layout. Slot tersembunyi memakai alpha 0.01 (bukan INVISIBLE) agar surface tetap hidup — **perlu diverifikasi di perangkat**. Rotasi bawaan video dihitung ExoPlayer lewat `VideoSize`. `attach(host)` dipasang ulang → engine dimuat ulang di posisi terakhir.

## 9. Galat & fallback
Galat pemain aktif: pulih (siapkan ulang di posisi) hingga 3 kali, lalu `onError`. Urutan mesin: **V2 → CompositionPlayer → ExoPlayer**; pengguna dapat memilih mesin (disimpan di SharedPreferences). Plan native gagal → Kotlin.

## 10. Diagnostik & perbandingan
`PreviewLog` (ring buffer 300 baris; tanpa URI/nama file): state pemain, posisi, first-frame, ukuran surface, frame dijatuhkan, error codec, underrun audio, event transisi. UI: tombol Log/Salin + statistik (`trans`, `lastWait`, `maxWait`, `ready`, `stall`, `recover`, `drift`, `drop`). **Pengujian perangkat yang diminta:** media sama, 2 klip berurutan → catat apakah gambar membeku/audio tersendat saat transisi, "ready" ms, `maxWait`, lalu ulangi dengan "Mesin: Composition". Tidak ada klaim performa sebelum data itu.

## 11. Risiko terbuka
Dua dekoder serentak pada perangkat murah; alpha 0.01 pada TextureView; ketepatan first-frame; akurasi `currentPosition` (ms); semuanya belum diuji di perangkat. Kode lama tidak dihapus.
