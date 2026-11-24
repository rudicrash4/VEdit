# Signing APK VEdit

## Kondisi sebelum Tahap 1
`app/build.gradle.kts` tidak punya `signingConfig`, sehingga Gradle memakai `~/.android/debug.keystore` yang dibuat otomatis. Runner GitHub Actions selalu baru, jadi **setiap run menghasilkan debug key berbeda**. Key itu tidak disimpan di mana pun, sehingga APK Phase 0 yang sudah terpasang **tidak bisa dipertahankan**: pindah ke key permanen butuh **uninstall + instal ulang satu kali** (tidak ada data proyek yang hilang karena aplikasi belum menyimpan apa pun). Setelah itu semua build berikutnya bisa menimpa.

applicationId tidak diubah: `id.vedit.editor` + suffix debug `.debug` (jadi paket terpasang: `id.vedit.editor.debug`).

## Cara kerja
- CI mendekode Secret `ANDROID_KEYSTORE_BASE64` ke file sementara, Gradle membacanya lewat environment, file dihapus di akhir job.
- Tanpa secret (mis. PR dari fork) build tetap jalan dengan debug key sementara dan ada peringatan di log.
- `versionCode` = nomor run GitHub (selalu naik), karena Android menolak update dengan versionCode lebih kecil.
- Ringkasan run menampilkan sidik jari sertifikat SHA-256 (info publik, bukan rahasia). Jika sidik jari sama antar-run, key konsisten.

## Langkah membuat keystore (sekali saja)
Pilih satu tempat yang punya Java `keytool`: komputer, atau Termux di HP (`pkg install openjdk-17`).

1. Buat keystore (ganti nama bila perlu). `keytool` akan menanyakan password secara interaktif, jadi tidak muncul di riwayat perintah:
   ```
   keytool -genkeypair -v -keystore vedit-release.jks -alias vedit -keyalg RSA -keysize 2048 -validity 10000
   ```
   Catat: password keystore, alias (`vedit`), password key (boleh sama dengan password keystore).
2. Ubah ke base64 satu baris:
   ```
   base64 -w0 vedit-release.jks > vedit-release.jks.b64
   ```
   (di macOS: `base64 -i vedit-release.jks | tr -d '\n' > vedit-release.jks.b64`)
3. Di GitHub: repo `rudicrash4/VEdit` > **Settings > Secrets and variables > Actions > New repository secret**. Buat empat secret:
   - `ANDROID_KEYSTORE_BASE64`: isi file `.b64` (salin-tempel seluruh isinya)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. **Cadangkan** `vedit-release.jks` dan passwordnya di tempat aman di luar repo (mis. pengelola password atau penyimpanan pribadi). Jika hilang, tidak ada cara membuat update; pengguna harus uninstall.
5. Jalankan ulang workflow (push commit atau Re-run). Cek di ringkasan run: `Signing: permanen (secrets)`.
6. Hapus aplikasi lama dari HP, instal APK baru. Mulai dari sini update berikutnya langsung menimpa.

Jangan pernah commit `*.jks`, `*.keystore`, atau password (sudah ada di `.gitignore`).

## Batasan
- Ini APK **debug** yang ditandatangani key permanen, cukup untuk update antar-build pengujian. Rilis publik (Play Store) memerlukan keputusan terpisah (App Signing, build release).
- Alur signing dengan secret belum bisa diuji sebelum secret dibuat.
