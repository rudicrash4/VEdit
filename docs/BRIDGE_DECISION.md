# Keputusan Bridge Kotlin–Rust

## Keputusan
**JNI langsung (crate `jni` 0.21) dengan kontrak JSON bersatu dan handle sesi integer.** Dibangun di `rust/vedit-bridge`, dipanggil dari `id.vedit.editor.engine.NativeBridge`.

## Kandidat dan perbandingan
| Kriteria | JNI + JSON (dipilih) | UniFFI |
|---|---|---|
| Ergonomi/tipe Kotlin | Permukaan kecil (8 fungsi, string JSON); tipe domain Kotlin (`@Serializable Project`) dipakai ulang tanpa lapisan pemetaan | Kode Kotlin dihasilkan otomatis dari definisi Rust, bertipe kuat, tetapi tipe Rust harus dipetakan ke/dari model Kotlin yang sudah dipakai seluruh UI |
| Pustaka runtime | Tidak ada tambahan | Memerlukan JNA di Android (+ `libjnidispatch.so` per ABI) |
| Tahap build | Hanya cargo (cargo-ndk) | cargo + `uniffi-bindgen` (generasi Kotlin) di Gradle/CI |
| Galat | Amplop `{ok:false, code, message}`; panic ditangkap di batas JNI | Galat bertipe; panic dipetakan oleh UniFFI |
| Async/callback | Tidak diperlukan: operasi edit sinkron dan cepat; export/preview tetap Media3 | Mendukung async/callback; tidak dibutuhkan saat ini |
| Kinerja data | Satu string JSON per panggilan (proyek kecil; frame video TIDAK melewati bridge) | Serialisasi biner efisien untuk record |
| Uji tanpa perangkat | Pustaka host dimuat di JVM (`java.library.path`) sehingga smoke test + differential test berjalan di CI | Mungkin, dengan JNA di JVM |
| Risiko pemeliharaan | Kontrak JSON dijaga versi (`API_VERSION`) dan tes diferensial | Bergantung pada versi/ekosistem generator |

## Bukti prototipe (jujur)
- **JNI**: dibangun penuh, bukan prototipe sekali pakai; dipakai sebagai jalur produksi. Diuji oleh tes unit Rust (registri sesi, handle basi, panic, konkurensi), smoke test JVM (muat pustaka, versi API) dan differential test Rust vs Kotlin di CI.
- **UniFFI**: **tidak dibangun sebagai prototipe** dalam milestone ini. Perbandingan di atas berdasarkan analisis desain, bukan pengukuran. Ini keterbatasan evaluasi; keputusan dapat ditinjau bila kebutuhan callback/async muncul (mis. progres export dari native).
- **Pengukuran latensi/ukuran APK**: belum ada; tidak ada angka yang diklaim.

## Konvensi API
- Satu kontrak JSON per perintah (`{"op":"split",...}`), semua waktu integer mikrodetik, kunci camelCase, nilai enum sama dengan nama enum Kotlin (`VIDEO`, `R16_9`, `FILL`).
- Hasil selalu amplop: `{"ok":true,"project":{...},"canUndo":b,"canRedo":b,"selected":id|null}` atau `{"ok":false,"code":"...","message":"..."}`. Perintah ditolak tidak mengubah state.
- `API_VERSION` (Rust) harus sama dengan `NativeBridge.EXPECTED_API`; beda = pustaka dianggap tidak tersedia dan Kotlin dipakai.
- Handle sesi = integer yang tidak pernah dipakai ulang (bukan pointer); handle basi menghasilkan galat `bad_handle`.
- Threading: kunci per sesi di Rust; Kotlin memanggil dari thread utama. Tidak ada callback lintas thread.
- Siklus hidup: `RustEngine.close()` saat editor ditutup; `EngineFactory` jatuh ke `KotlinEngine` jika native tidak termuat/menolak proyek, dan UI menampilkan alasan.
- Cancellation: tidak relevan (operasi edit milidetik); export dibatalkan oleh Transformer, di luar bridge.

## Trade-off
Tipe tidak dicek compiler pada batas bridge (JSON); dikompensasi tes diferensial dan versi API. JSON dibuat tangan di core agar core tanpa dependency (dapat diuji offline dan diaudit); pengurai JSON sendiri dites (presisi i64/f32, surrogate Unicode, input rusak, kedalaman).
