# Laporan Phase 1 (sub-tahap 1.1 dan 1.2)

Status: **fondasi dibangun, lolos CI. Belum diuji di perangkat fisik.** Preview video, playback, dan thumbnail BELUM ada.

## Cakupan sub-tahap ini
| Bagian (spesifikasi Phase 1) | Status |
|---|---|
| Editor UI: tampilan utama, panel alat | Ada (shell). Area preview hanya menampilkan info playhead/klip terpilih, bukan video |
| Video preview, playback controls | **Belum** (sub-tahap 1.3). Tombol -1s/+1s hanya memindah playhead |
| Media import (video, gambar, audio, SAF) | Ada. Izin baca dipertahankan bila didukung; metadata dibaca nyata |
| Timeline: video track, audio track, klip, seleksi, scroll, zoom, playhead | Ada. Zoom lewat tombol (belum pinch); playhead lewat ketukan |
| Split, trim, delete, duplicate, reorder, rotate, crop | Ada di mesin dan UI. Trim memakai posisi playhead; reorder lewat tombol geser |
| Project: buat, simpan, autosave, buka kembali, pulih setelah ditutup | Ada |
| Thumbnail klip, waveform | Belum |
| Crop/rotasi terhadap export dan preview | **Belum**: nilai disimpan di model, belum diterapkan ke render |

## Arsitektur (sesuai TDD bagian 4-6, 13)
Satu modul `:app` dengan paket terpisah, tanpa modul kosong (TDD 4):
- `domain`: model waktu mikrodetik, `Project/Track/Clip/MediaSource`, `TimelineEngine` (murni, tanpa Android), `EditHistory` (undo/redo snapshot).
- `data`: `ProjectCodec` (JSON berversi + rantai migrasi), `ProjectStore` (tulis atomik .tmp + rename, cadangan .bak, pemulihan), `Autosaver` (debounce 800 ms, flush saat `onStop`), `MediaImporter` (SAF).
- `ui`: `HomeScreen`, `EditorScreen`, `Timeline`.
Pemisahan ke modul `core:model` / `core:project` dilakukan saat batasnya stabil.

### Semantik edit yang dikunci oleh unit test
- split: kiri dan kanan bersambung tanpa celah, titik potong minimal 100 ms dari tepi, benar untuk kecepatan non-1x.
- trim: tidak boleh melewati media sumber, klip tetangga, atau < 100 ms.
- delete: tanpa ripple meninggalkan celah; dengan ripple klip setelahnya maju.
- duplicate: salinan setelah asli, klip setelahnya digeser (insert + ripple).
- reorder: klip dirapatkan berurutan mulai start paling awal (celah hilang); indeks dijepit.
- move: tumpang tindih ditolak. Rotasi kelipatan 90. Crop dijaga agar tak menyisakan < 10% area.
- Konversi frame ke mikrodetik dan sebaliknya identik pada 30000/1001, 24000/1001, 25, 60 fps (tanpa drift).

## Pengujian (dipisah)
- **Unit test (JVM, CI):** 7 test speed/analisis Phase 0B tetap lolos, ditambah `TimeTest`, `TimelineEngineTest`, `EditHistoryTest`, `ProjectCodecTest` (round trip, kunci tak dikenal, schema lebih baru ditolak, rantai migrasi), `ProjectStoreTest` (simpan/muat/daftar, pemulihan dari cadangan, file rusak dilewati, path traversal ditolak), `AutosaverTest` (debounce, flush).
- **Build/lint/CI:** lolos pada run 45530489137 (commit 4297b27). Lint sempat menangkap pemakaian `java.nio.file` (API 26) yang tidak cocok minSdk 24; diganti `File.renameTo`.
- **Perangkat fisik:** **belum**. UI Compose (shell, timeline, impor SAF, autosave/reopen) tidak punya test otomatis; hanya terbukti kompilasi.

## Keterbatasan dan hal yang belum teruji
- Impor SAF dengan izin persisten: URI picker tertentu tidak mendukungnya; aplikasi tetap lanjut tetapi akses bisa hilang setelah restart (belum diuji). Mekanisme "relink" media hilang belum ada.
- Video dengan rotasi 90/270 ditukar lebar-tingginya saat impor; belum diverifikasi pada banyak perangkat.
- Gambar memakai durasi bawaan 5 dtk, bisa diperpanjang lewat trim akhir.
- Satu track video dan satu track audio; multi-track/overlay dibangun setelah mesin dibuktikan (Phase 0B).
- Konfigurasi perubahan layar (rotasi) ditangani tanpa membuat ulang Activity; state editor tidak dipertahankan jika proses dimatikan di luar autosave (data tersimpan di disk).

## Dependency baru
`kotlinx-serialization-json` 1.8.1 dan plugin `kotlin-serialization` (Apache 2.0). Lihat `DEPENDENCIES.md`.

## Berikutnya
1.3 Preview: pemutaran klip terpilih/komposisi (CompositionPlayer atau ExoPlayer sesuai hasil uji Phase 0B), kontrol putar, thumbnail. 1.4 Menerapkan rotasi/crop pada preview dan export. Menunggu hasil uji perangkat Phase 0B (build v0.1.0) untuk keputusan engine preview.
