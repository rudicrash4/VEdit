# Audit Preview untuk Preview Engine V2 (v0.7.0)

Status bukti: **tidak ada bug yang direproduksi di sini.** Gejala berasal dari laporan pengguna (OPPO A18 CPH2591, API 35, engine Rust v0.6.0, CompositionPlayer): klip 1 normal; pindah ke klip 2 gambar berhenti tetapi audio lanjut; audio tersendat di transisi pertama; mulai langsung dari klip 2 normal; engine Rust terasa lebih mulus dari Kotlin. Semua di bawah ini adalah **pembacaan kode (fakta)** dan **hipotesis (belum terbukti)** yang dipisah tegas.

## 1. Fakta dari kode

| Komponen | Temuan |
|---|---|
| Dependency | Media3 1.11.1 (`exoplayer`, `transformer`, `effect`). `CompositionPlayer` ada di modul transformer dan ditandai `@ExperimentalApi`. |
| `CompositionEngine` | Membuat **CompositionPlayer baru di setiap `load`**; `STATE_READY` pertama memicu `seekTo(startUs)` lalu `onReady`. Satu SurfaceView. |
| `CompositionFactory` | Track video = satu `EditedMediaItemSequence` bertipe {VIDEO, AUDIO bila ada klip beraudio}; track audio = sequence audio-only terpisah. Celah = `addGap`. Klip video: `ClippingConfiguration(in,out)` + `setDurationUs(src.durationUs)` (durasi **sumber penuh**, bukan durasi klip). Gambar: `setImageDurationMs` + `setDurationUs(durasi klip)` + `setFrameRate(30)`. Efek per item: Crop → ScaleAndRotate → `Presentation` (480p untuk PREVIEW). Volume = `ChannelMixingAudioProcessor`; kecepatan = `SteppedSpeedProvider`. |
| `ExoEngine` | Cadangan: playlist ExoPlayer klip track video saja; tanpa gap/audio tambahan/efek. |
| `PreviewController` | Fallback tunggal Composition → Exo pada `onError`. Dedupe rebuild lewat `PreviewPlan.structureChanged`. |
| Posisi | `CompositionEngine.positionUs = player.currentPosition*1000`; tidak ada pemetaan sendiri. |
| Rust | Sebelum v0.7.0 hanya mesin edit (operasi timeline, undo/redo, kanvas, urutan klip); **tidak ada** penjadwal pemutaran. Jadi engine Rust **tidak berada di jalur pemutaran** — perbedaan "lebih mulus" kemungkinan berasal dari hal lain (hipotesis H8). |
| Tes lama | `PreviewControllerTest`/`PreviewPlanTest` memakai engine palsu; **tidak ada tes yang menyentuh perilaku transisi klip** (tidak bisa tanpa perangkat). |

## 2. Hipotesis penyebab (BELUM terbukti)

- **H1 — Kelas masalah upstream.** androidx/media issue #2742 (video beku di transisi item pada komposisi multi-sequence sementara audio lanjut; pengelola menyebut transisi/seek di CompositionPlayer masih dikerjakan). Gejalanya mirip, tetapi **kesamaan belum bukti** untuk komposisi VEdit.
- **H2 — Dua sequence dengan jam berbeda.** Sequence video (dengan audio internal) + sequence audio terpisah → audio bisa terus ketika jalur video macet di batas item.
- **H3 — `setDurationUs(src.durationUs)` untuk klip ber-clipping.** Mungkin tidak sama dengan durasi pasca-clip yang diharapkan komposisi; efek ke penjadwalan batas **belum diverifikasi**.
- **H4 — Dekoder/buffering di batas.** Menyiapkan dekoder klip berikutnya saat yang pertama berjalan (codec init di perangkat low-end).
- **H5 — Efek per item berbeda** (Crop/Rotate/Presentation per klip) memaksa rekonfigurasi pipeline GL di batas.
- **H6 — Mulai dari klip 2 normal** konsisten dengan masalah di *transisi*, bukan dekoder klip 2 itu sendiri (konsisten dengan H1/H4/H5; bukan bukti).
- **H7 — Surface lifecycle / pembuatan ulang player & seek** (`setVideoSurfaceView` + `seekTo` saat READY) — tidak ada indikasi langsung, hanya kandidat.
- **H8 — "Rust lebih mulus"** kemungkinan efek lain (waktu operasi edit/rebuild lebih singkat), bukan pemutaran.

Kandidat yang diminta dan statusnya: frame scheduling/clip boundary (H1, H2, H4), buffering dekoder (H4), pemetaan posisi (tidak ditemukan masalah di kode), video→gambar (H5), A/V sync (H2), Surface (H7), recreate+seek (H7), durasi/clipping/speed/gap EditedMediaItem (H3), start 0 vs tengah (H6).

## 3. Cara memverifikasi di perangkat (untuk pengguna)
Mesin dapat dipilih di panel preview ("Mesin: …"). Log + statistik dapat disalin ("Log" → "Salin"). Bandingkan Composition vs V2 pada media yang sama (lihat `PREVIEW_V2_DESIGN.md` bagian 10). Hipotesis H1–H8 tidak boleh dianggap benar sebelum data itu ada.
