# Langkah Rilis Android (Bahasa Indonesia)

Proyek ini **hanya membaca** info rilis dari web (`GET /api/mobile/android/latest`).
Proses publish (build APK → upload → daftar rilis) dijalankan dari repo mobile ini,
lewat GitHub Action **`release-android`** atau lewat `tools/release.sh` di laptop.

---

## A. Setup — cukup SEKALI

### 1. Inisialisasi git & push ke GitHub

```bash
cd school-community-mobile
git init
git add .
git commit -m "chore: initial commit"
gh repo create school-community-mobile --private --source=. --push
# atau buat repo manual di github.com lalu:
# git remote add origin git@github.com:USER/school-community-mobile.git && git push -u origin main
```

`.gitignore` sudah mengecualikan `*.jks`, `*.keystore`, `local.properties` — **keystore
tidak akan ikut ter-commit**.

### 2. Buat keystore rilis (kunci penandatangan produksi)

```bash
keytool -genkey -v -keystore school-community-release.jks \
  -alias schoolcommunity -keyalg RSA -keysize 2048 -validity 10000
```

- Simpan file `.jks` + semua password di **password manager**. Kalau hilang, kamu
  **tidak bisa** merilis update untuk pengguna lama (Android menolak APK dengan tanda
  tangan berbeda).
- Jangan taruh di repo. Jangan kirim lewat chat/email.

### 3. Ubah keystore jadi base64 (untuk GitHub Secret)

```bash
base64 -w0 school-community-release.jks > keystore.b64   # Linux/Git Bash
# macOS: base64 -i school-community-release.jks -o keystore.b64
```

Isi `keystore.b64` yang akan ditempel ke secret. Hapus file `.b64` setelah dipakai.

### 4. Tambah GitHub Secrets

Repo GitHub → **Settings → Secrets and variables → Actions → New repository secret**.
Buat 5 secret ini (nama harus persis):

| Nama secret | Isi |
|---|---|
| `SC_KEYSTORE_BASE64` | isi file `keystore.b64` dari langkah 3 |
| `SC_KEYSTORE_PASS` | password keystore (yang diminta `keytool`) |
| `SC_KEY_ALIAS` | `schoolcommunity` (alias dari langkah 2) |
| `SC_KEY_PASS` | password key (biasanya sama dengan password keystore) |
| `MOBILE_RELEASE_TOKEN` | token tulis untuk `POST /api/mobile/android/releases` — didapat dari proyek web (lihat B) |

### 5. Sisi web / Vercel — token & kontrak API

Proyek **`school-community`** (repo & Vercel terpisah) yang punya endpoint tulis.
Yang perlu dipastikan di sana:

1. **Token tulis.** Endpoint `POST /api/mobile/android/releases` menolak tanpa auth
   (`401 UNAUTHORIZED`). Cari/buat nilai token di proyek web (biasanya env var seperti
   `MOBILE_RELEASE_TOKEN` / `RELEASE_API_TOKEN` di **Vercel → Project → Settings →
   Environment Variables**). Nilai yang **sama** dipakai sebagai GitHub Secret
   `MOBILE_RELEASE_TOKEN` di langkah 4.
2. **Kontrak `POST /releases`.** `tools/release.sh` mengirim `multipart/form-data`:
   field `apk` (file `.apk`) + field `metadata` (JSON). **Cek di kode web** apakah:
   - nama field-nya memang `apk` dan `metadata`, dan
   - APK diunggah lewat call ini, atau harus di-upload ke Vercel Blob dulu lalu
     `apkUrl`-nya dikirim di JSON.
   Kalau berbeda, sesuaikan fungsi `publish()` di `tools/release.sh` (± 3 baris `curl`).
3. **`apkUrl` harus URL permanen** (mis. Vercel Blob) dan nama file immutable
   (`school-community-1.1.0.apk`), bukan `latest.apk`.
4. **Vercel Blob** (kalau dipakai untuk simpan APK): pastikan store aktif dan
   `BLOB_READ_WRITE_TOKEN` ada di env Vercel proyek web. Repo mobile **tidak** perlu
   token Blob — semua upload lewat endpoint web yang sudah terautentikasi.

> Aturan keamanan: repo/aplikasi Android **tidak pernah** menyimpan token tulis atau
> `BLOB_READ_WRITE_TOKEN`. Hanya baca `GET /latest`.

---

## B. Setiap kali rilis versi baru

Naikkan **`versionCode`** setiap rilis (angka bulat, selalu +1). `versionName` untuk
tampilan (1.0.0 → 1.1.0). Sumber tunggal: `app/build.gradle.kts`.

### Opsi 1 — GitHub Action (disarankan)

1. GitHub repo → tab **Actions** → workflow **release-android** → **Run workflow**.
2. Isi:
   - `versionName`: `1.1.0`
   - `versionCode`: `2`
   - `notes`: `Perbaikan unduhan PDF|Deteksi pembaruan dalam aplikasi`
   - `forceUpdate`: centang hanya kalau versi lama benar-benar tidak boleh dipakai lagi
3. **Run**. Action akan: build APK ditandatangani → cek identitas APK (package id,
   versionCode, versionName) → publish ke registry → commit bump versi kembali ke repo →
   simpan APK sebagai artifact.

### Opsi 2 — dari laptop

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
export SC_KEYSTORE="/path/school-community-release.jks"
export SC_KEYSTORE_PASS=... SC_KEY_ALIAS=schoolcommunity SC_KEY_PASS=...

# uji dulu tanpa publish:
tools/release.sh 1.1.0 2 --note "Perbaikan unduhan PDF"

# publish beneran:
MOBILE_RELEASE_TOKEN=xxxxx tools/release.sh 1.1.0 2 --note "Perbaikan unduhan PDF" --publish
```

Flag: `--force` (update wajib), `--min <versionCode>` (batas versi minimum didukung),
`--allow-dirty` (lewati cek git bersih).

---

## C. Verifikasi setelah publish

```bash
curl -s https://schoolcommunity.space/api/mobile/android/latest | jq
```

Harus menampilkan `versionCode` baru + `apkUrl` yang bisa diunduh.

Lalu di HP yang masih pakai versi lama: buka aplikasi → dialog **"Versi Baru Tersedia"**
(atau layar **"Pembaruan Diperlukan"** kalau `forceUpdate`) muncul saat app dibuka.

---

## Ringkasan: apa yang dibuat di mana

| Tempat | Yang perlu dibuat / diisi |
|---|---|
| **Laptop** | keystore `.jks` (sekali), simpan di password manager |
| **GitHub → Settings → Secrets → Actions** | `SC_KEYSTORE_BASE64`, `SC_KEYSTORE_PASS`, `SC_KEY_ALIAS`, `SC_KEY_PASS`, `MOBILE_RELEASE_TOKEN` |
| **GitHub → Actions** | jalankan workflow **`release-android`** (`.github/workflows/release-android.yml`) tiap rilis |
| **Proyek web (repo `school-community`)** | konfirmasi kontrak `POST /api/mobile/android/releases` + nama field |
| **Vercel (proyek web)** | env var token tulis (mis. `MOBILE_RELEASE_TOKEN`), dan `BLOB_READ_WRITE_TOKEN` bila APK disimpan di Vercel Blob |
| **Proyek mobile ini** | tidak ada env/secret yang disimpan di repo; hanya baca `GET /latest` |
