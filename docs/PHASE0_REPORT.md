# Laporan Phase 0

## Kondisi awal repository
`rudicrash4/VEdit` kosong saat diperiksa: tidak ada branch, commit, atau workflow (diverifikasi lewat clone dan GitHub API; API menjawab 409 "Git Repository is empty").

## Struktur modul
Saat ini satu modul `:app`. Modul `core:*` / `feature:*` dari TDD dibuat bertahap saat batasnya jelas (Phase 3 untuk timeline/project), bukan modul kosong.

## minSdk: 24
- Media3 1.9.0 menaikkan minSdk ke 23; catatan rilis 1.12.0-alpha menyebut minSdk 24. Memilih 24 menghindari migrasi nanti.
- Perbaikan frame drop multi-sequence di Media3 1.12 hanya API 29+; ini batasan perangkat yang perlu didokumentasikan.
- HDR editing butuh API 31+/33 dan dukungan encoder; di luar MVP.

## Temuan Media3 (dari dokumentasi dan catatan rilis resmi)
Terverifikasi dari dokumen:
- `Composition` menerima beberapa `EditedMediaItemSequence` (indikasi multi-track).
- Efek video per item: `Presentation`, `ScaleAndRotateTransformation`, `Crop`, `OverlayEffect`, efek kustom via `GlShaderProgram`.
- Audio: rantai `AudioProcessor`; ada `ChannelMixingAudioProcessor`.
- 1.9.0: `EditedMediaItem.Builder#setSpeed()` dan kecepatan pada sequence sekunder; 1.11.0: `setSpeed(SpeedParameters)` dengan pitch dipertahankan.
- Stabil terbaru: 1.11.1; 1.12.0-beta01 belum dipakai.

**Belum terverifikasi (wajib PoC sebelum dijanjikan):**
1. Kurva kecepatan variabel (speed ramp) pada export Transformer: dokumentasi yang saya baca tidak menyebut dukungannya jelas.
2. Mixing audio dari beberapa track pada export, dan overlay video multi-layer dengan opacity/transform per klip.
3. Paritas preview (CompositionPlayer/ExoPlayer) dan export. Catatan rilis menyebut CompositionPlayer punya batasan yang tidak seluruhnya terdokumentasi.

## Rencana PoC (Phase 0b/5)
1. **Speed ramp:** instrumented test yang mengekspor klip uji 10 detik dengan profil 1x, 4x, 0.5x lewat API Media3 yang tersedia; ukur durasi output dan probe dengan `MediaExtractor`/`MediaMetadataRetriever`. Jika tidak didukung: evaluasi pemotongan klip menjadi segmen berkecepatan konstan (hasil bertahap, bukan kurva mulus), pipeline MediaCodec/GL kustom, atau FFmpeg (LGPL, build sendiri).
2. **Multi-track:** dua sequence video (PiP) + dua sequence audio; verifikasi stream audio hasil dan durasi.
3. Jalankan di perangkat nyata (CI tidak punya codec yang representatif). Hasil dicatat di dokumen ini.

## OpenCut
README repo menyebut: editor "web, desktop, and mobile", rewrite dengan inti Rust, lisensi MIT, kontribusi luar belum diterima selama rewrite. Tidak ada indikasi mesin Android siap pakai. Keputusan: **tidak dipakai** sebagai basis; hanya referensi.

## FFmpeg
Tidak ditambahkan. Dievaluasi hanya jika PoC menunjukkan Media3 tidak cukup (lihat TDD bagian 3).

## Risiko
- Build hanya bisa diverifikasi di GitHub Actions (sandbox pengembang tanpa jaringan). Phase 0 diuji di CPH2591 (API 35): layar diagnostik terbuka tanpa crash, H.264 HW encoder (MediaTek) dan AAC software encoder terdeteksi.
- (Terselesaikan) compileSdk 36 + Media3 1.11.1 + AGP 8.13.0 berhasil di CI.
- Speed ramp dan multi-track belum terbukti.
