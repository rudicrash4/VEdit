# Inventaris dependency dan lisensi (Phase 0)

| Dependency | Versi | Tujuan | Lisensi | Status |
|---|---|---|---|---|
| androidx.media3:media3-exoplayer | 1.11.1 (stabil, 10 Sep 2026) | Preview playback | Apache 2.0 | Dipakai |
| androidx.media3:media3-transformer | 1.11.1 | Kandidat export | Apache 2.0 | Dipakai (belum difungsikan) |
| androidx.media3:media3-effect | 1.11.1 | Efek video/overlay | Apache 2.0 | Dipakai (belum difungsikan) |
| androidx.compose BOM / ui / material3 | 2025.09.00 | UI | Apache 2.0 | Dipakai |
| androidx.activity:activity-compose, core-ktx | 1.10.1 / 1.16.0 | Dasar app | Apache 2.0 | Dipakai |
| JUnit | 4.13.2 | Unit test | EPL 1.0 | Dipakai (test saja) |
| FFmpeg / wrapper | - | - | LGPL/GPL tergantung konfigurasi | **Tidak ditambahkan.** Evaluasi hanya jika PoC Media3 gagal |
| OpenCut | - | Referensi | MIT (sumber: README repo) | **Tidak dipakai** (lihat laporan) |

Catatan: lisensi Apache 2.0 untuk AndroidX/Media3 berasal dari pengetahuan umum, belum diverifikasi ulang per artefak di sesi ini. Verifikasi lewat POM sebelum rilis (tugas Phase 0 lanjutan).
