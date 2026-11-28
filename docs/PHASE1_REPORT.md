# Laporan Phase 1 (sub-tahap 1.1 - 1.3)

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
- **Unit test (JVM, CI):** test speed/analisis Phase 0B tetap lolos (koreksi: sebelumnya tertulis "7 test"; total di v0.2.0 adalah 52 `@Test`), ditambah `TimeTest`, `TimelineEngineTest`, `EditHistoryTest`, `ProjectCodecTest` (round trip, kunci tak dikenal, schema lebih baru ditolak, rantai migrasi), `ProjectStoreTest` (simpan/muat/daftar, pemulihan dari cadangan, file rusak dilewati, path traversal ditolak), `AutosaverTest` (debounce, flush).
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


---
# Sub-tahap 1.3: Preview video nyata (v0.3.0)

Status: **diimplementasikan dan lolos CI (unit test, lint, assembleDebug). BELUM diverifikasi di perangkat fisik.** Jangan dibaca sebagai "preview terbukti tampil benar".

## Yang dibangun
| Kebutuhan | Implementasi | Bukti |
|---|---|---|
| Video, putar/jeda, seek, multi klip, transisi antar klip | `CompositionFactory` membuat satu sequence video (klip + `addGap`) untuk `CompositionPlayer` | Unit test logika (`PreviewPlanTest`); tampilan: perlu perangkat |
| Gambar (durasi mengikuti timeline) | `setImageDurationMs` + `EditedMediaItem.setDurationUs(clip.durationUs)` | Perlu perangkat |
| Audio video + audio tambahan, volume per klip | Sequence audio terpisah; volume via `ChannelMixingAudioProcessor` bila != 1 | Perlu perangkat (A/V sync tidak diukur di 1.3) |
| Sinkron playhead-preview | Controller: polling 100 ms saat memutar; seek ke pemutar; klip dipilih memindah playhead | `PreviewControllerTest` (engine palsu) |
| Split/trim/reorder/impor/undo tercermin | `PreviewPlan.structureChanged` memicu rebuild (debounce 300 ms); posisi dipertahankan; rename tidak rebuild | `PreviewPlanTest`, `EditorFlowTest` |
| Galat/loading | State `loading/ready/error/notice`; galat pertama pada CompositionPlayer berpindah otomatis ke ExoPlayer (fallback tanpa gap/audio tambahan/efek), galat kedua ditampilkan | `PreviewControllerTest` |
| Lifecycle | Jeda di `onStop`; `release()` saat editor ditutup; engine lama dilepas saat rebuild; callback setelah release diabaikan | `PreviewControllerTest`; kebocoran memori nyata belum diukur |

Keputusan: kanvas preview tetap 1280x720 (letterbox); rotasi model searah jarum jam sehingga sudut dinegasikan untuk `ScaleAndRotateTransformation`; crop dipetakan ke NDC. Rebuild menghentikan pemutaran (posisi dipertahankan).

## Validasi fungsional edit
`EditorFlowTest`: add, split, duplicate, reorder, rotate, crop, trim, delete(ripple) lewat `EditHistory`; invarian `validate()` tiap langkah; undo 9 langkah kembali ke proyek awal persis; redo kembali ke akhir; rotasi/crop/trim lolos round trip JSON. Itu membuktikan model, BUKAN tampilan atau tombol UI.

## Pengujian (dipisah)
- **Unit test JVM:** ditambah `PreviewPlanTest` (11), `PreviewControllerTest` (9), `EditorFlowTest` (2). Hasil tepat per run: lihat Actions (jumlah dan status dikutip di laporan akhir).
- **CI:** commit 3be1975 (UI preview) lolos test, lint, assembleDebug (run 45564442537). Run untuk v0.3.0 dicatat di CHANGELOG/laporan akhir.
- **Perangkat fisik:** **belum**.

## Belum / keterbatasan
- Fallback ExoPlayer: tanpa gap, audio tambahan, volume, rotasi/crop.
- Crop/rotasi belum di export. Thumbnail dan waveform belum ada. Performa perangkat menengah belum diukur; seek saat memutar dan rebuild bisa terasa lambat pada banyak klip.
- Kinerja SurfaceView di dalam Compose scroll belum diuji.

## Uji perangkat yang masih diperlukan
Preview video tampil; putar/jeda; seek menggeser frame; dua klip berurutan tanpa glitch; gambar tampil sesuai durasi; audio ikut timeline dan sinkron; volume; split/trim/reorder/undo terlihat di preview; tutup/buka editor berulang (kebocoran/crash); media gagal dimuat tidak membuat crash; suhu/memori pada media panjang.

---
# v0.4.0: export awal dan UI adaptif
- **Hasil uji perangkat v0.3.0 (laporan pengguna, tanpa rincian perangkat):** video, gambar, audio berjalan lancar di preview. Ini laporan pengguna, bukan pengukuran otomatis; A/V sync tidak diukur.
- **Baru, belum diuji perangkat:** export MP4 (`ExportController`), tata letak adaptif, penanganan insets.
- **Unit test:** + `ExportNamingTest`. UI Compose dan Transformer tidak punya test otomatis.
- **Keterbatasan:** export tanpa foreground service, resolusi tetap 1280x720, hasil export belum diverifikasi (durasi/A-V/rotasi/crop) pada perangkat, API < 29 hanya menyimpan di folder app.
- **Uji perangkat diperlukan:** export klip tunggal dan multi klip; hasil dapat diputar di galeri; durasi cocok; rotasi/crop terlihat; audio ada; batal; export lalu app ke background; tampilan potret, landscape, layar lipat/tablet.

---
# v0.5.0: rasio kanvas dan optimasi preview

## Hasil uji perangkat v0.4.0 (laporan pengguna; OPPO A18 CPH2591, API 35)
Berhasil: aplikasi, impor video/gambar/audio, timeline, edit, rotasi, crop, preview video/gambar/audio, export MP4 (hasil bagus, tanpa kesalahan visual yang ditemukan pengguna). Catatan: preview agak lag; rasio export masih mengikuti 16:9. Tidak ada benchmark numerik yang dilaporkan.

## Investigasi lag preview (jujur: berdasarkan pembacaan kode, BELUM diprofil di perangkat)
Kandidat penyebab pada kode v0.4.0:
1. Preview dirender pada 1280x720 melalui pipeline efek GL (Presentation selalu aktif), padahal OPPO A18 kelas menengah-bawah. Ukuran frame menentukan biaya setiap frame.
2. Setiap pemanggilan `update` membangun ulang `CompositionPlayer` walau struktur tidak berubah, dan setiap perubahan struktur menghentikan pemutaran.
3. Tidak ada metrik waktu muat sehingga lag tak terukur.
Efek crop/rotasi sudah hanya ditambahkan jika dipakai (bukan penyebab pada klip tanpa edit). Polling playhead 100 ms hanya merekomposisi blok yang membaca playhead.

## Optimasi yang diterapkan
- Preview memakai kualitas PREVIEW (sisi pendek 480; 16:9 = 854x480, sekitar 44% piksel dari 720p). Export tetap 720p; kualitas export tidak diturunkan.
- `PreviewController.update` melewati pembangunan ulang bila struktur (track, sumber, kanvas) tidak berubah.
- Metrik terukur di perangkat ditampilkan di panel preview: "siap X ms (#jumlah build)".
- NOT diubah: engine tetap `CompositionPlayer`; tidak ada bukti teknis untuk menggantinya.
- **Belum ada hasil benchmark.** Efek nyata terhadap lag harus dinilai pengguna di OPPO A18; angka "siap X ms" dapat dilaporkan balik.

## Sistem rasio kanvas
Pilihan 16:9, 9:16, 1:1, 4:5 + Fit/Fill; tersimpan di proyek (schema 2); preview dan export memakai konfigurasi sama; ukuran frame export mengikuti rasio. Resolusi yang benar-benar aktif untuk export: **720p saja** (1280x720, 720x1280, 720x720, 720x900). 1080p tersedia di kode tetapi dinonaktifkan; BELUM diuji, tidak diklaim berhasil. Export di rasio selain 16:9 belum diuji perangkat.

## Model dan migrasi
`Project.canvas`, schema 1 -> 2. Proyek lama dibaca dengan default 16:9 FIT (diuji dengan dokumen v1 nyata di `CanvasTest`). Undo/redo mencakup perubahan kanvas; autosave menyimpan; round trip JSON diuji.

## Pengujian
- **Unit test (JVM, CI):** `CanvasTest` (dimensi semua preset, genap, rasio, preview < export, rencana export mengikuti kanvas dan menolak 1080p, default, round trip + undo/redo, migrasi v1, perubahan kanvas memicu rebuild), `PreviewControllerTest` (+ tanpa rebuild bila struktur sama, rebuild saat kanvas berubah, metrik waktu). Hasil CI dicatat di laporan akhir.
- **Perangkat:** v0.5.0 belum diuji.
- **Perlu diuji di OPPO A18:** apakah preview lebih lancar (laporkan "siap X ms"); ganti rasio 9:16/1:1/4:5 dan lihat kanvas preview; export tiap rasio (cek dimensi file, tidak melar); Fit vs Fill; video landscape di kanvas portrait dan sebaliknya; gambar persegi; crop + rotasi di semua rasio; tutup/buka ulang proyek (rasio tetap); proyek lama dari v0.4.0 tetap terbuka.


---
# v0.5.1 — UI/UX refresh (PR #2, 25 Nov 2026)

## Ringkasan perubahan
Versi aplikasi dinaikkan dari `0.5.0` ke `0.5.1`. Perubahan utama adalah penyegaran UI Compose, bukan penambahan fitur editing.

| File | Perubahan |
|---|---|
| `HomeScreen.kt` | Beranda dark studio; kartu/daftar proyek dan aksi proyek baru diberi hierarki visual lebih jelas. |
| `MainActivity.kt` | Tema gelap/palet warna aplikasi diselaraskan. |
| `PreviewPanel.kt` | Preview stage dan kontrol playback ditata ulang; pemutar/controller lama dipertahankan. |
| `EditorScreen.kt` | Panel preview/kanvas, timeline, tools, dan media disusun lebih jelas; layout adaptif dipertahankan. |
| `EditorScreen.kt` | Import `androidx.compose.foundation.background` ditambahkan untuk memperbaiki compile error. |
| `gradle.properties` | `vedit.versionName` dinaikkan ke `0.5.1`; `versionCode` tetap nomor run CI. |
| `.github/workflows/android-ci.yml` | Catatan GitHub Release dibuat eksplisit: versi, perubahan UI, perubahan lain, validasi, dan status uji perangkat. APK tetap aset `.apk` langsung. |

## Yang sengaja tidak diubah
Model/schema proyek, timeline engine dan undo/redo, autosave, media import, preview engine/controller, export controller/pipeline. Thumbnail timeline, waveform, contextual inspector penuh, efek dan transisi belum termasuk dalam refresh ini.

## Validasi
- CI run #33 pada commit UI sebelumnya: unit tests, lint, `assembleDebug`, dan verifikasi APK **lulus**.
- CI baru pada commit v0.5.1 harus lulus sebelum dianggap siap rilis.
- Uji fisik v0.5.1: **belum dilakukan**. Perlu uji tampilan portrait/landscape, sentuhan timeline, playback/seek, simpan/buka proyek, dan export pada HP nyata.
- Build CI dan verifikasi tanda tangan/metadata bukan bukti bahwa APK sudah diuji instalasi atau fungsi di perangkat.

## Distribusi
Setelah PR digabung ke `main`, workflow menjalankan build untuk push `main`; jika seluruh langkah sukses, GitHub Release `build-<nomor-run>` dibuat dengan aset `VEdit-Phase1-v0.5.1.apk`. PR build tidak menerbitkan Release. Signing masih mengikuti konfigurasi `SIGNING.md`; bila secret signing permanen tidak tersedia, APK memakai debug key sementara.

---
# v0.6.0: migrasi Hybrid Rust Engine (milestone 1)
- **Sudah:** core Rust + bridge JNI + `EditorEngine` terpasang di editor; Rust jalur utama bila pustaka termuat. Perilaku edit tidak berubah (diverifikasi differential test, bukan perangkat).
- **Hasil uji:** unit/CI dicatat di CHANGELOG dan laporan akhir; **perangkat fisik: belum**. Tidak ada angka kinerja Rust vs Kotlin yang diukur.
- **Keterbatasan:** UniFFI tidak diprototipekan (lihat BRIDGE_DECISION); preview/export masih Media3 dengan rencana dari Kotlin; ABI arm64-v8a + armeabi-v7a saja; schema proyek tidak berubah.
- **Uji perangkat diperlukan:** aplikasi terbuka (pustaka native termuat; label "Engine edit: Rust"), buka proyek lama dari v0.5.x, semua edit (split/trim/hapus/duplikat/geser/rotasi/crop/kanvas), undo/redo berulang, autosave + buka ulang, preview, export tiap rasio.

## Hasil CI v0.6.0 (dicatat setelah run; bukan uji perangkat)
- Run `46096532420` (commit `198fb49`): sukses. Run sebelumnya `46096240322` (commit `11f003f`) GAGAL pada langkah verifikasi APK: `libvedit_bridge.so` tidak masuk APK karena tugas `buildRustAndroid` tidak terpicu (AGP mengabaikan `builtBy` pada `srcDir`); diperbaiki dengan dependensi eksplisit pada tugas `merge*JniLibFolders`. Langkah verifikasi CI-lah yang menangkap ini.
- Tes Rust: `vedit-core` 8 unit + 13 integrasi lolos; `vedit-bridge` 5 lolos; `cargo fmt --check` dan `cargo clippy -D warnings` lolos.
- Tes Kotlin (JVM): lolos termasuk `DifferentialEngineTest` dengan pustaka host wajib termuat (`VEDIT_REQUIRE_NATIVE=1`; tes gagal bila tidak termuat, tidak dilewati diam-diam). Lint dan `assembleDebug` lolos.
- APK: `VEdit-Phase1-v0.6.0.apk` memuat `lib/arm64-v8a/libvedit_bridge.so` dan `lib/armeabi-v7a/libvedit_bridge.so` (diverifikasi CI); ukuran 14.409.534 byte vs 13.524.640 byte (v0.5.1), selisih sekitar 0,9 MB.
- Belum: uji muat pustaka di perangkat/emulator Android (CI hanya memuat pustaka host di JVM), pengukuran kinerja, uji perangkat fisik.
