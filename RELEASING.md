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

### 4. Pastikan repo `school-community-mobile` **PUBLIC**

APK di-host sebagai **GitHub Release asset** repo ini. App Android mengunduhnya
tanpa token, jadi repo harus public. Kalau harus private: buat repo publik
"releases mirror" dan arahkan `gh release` ke sana (ubah `SLUG` di `tools/release.sh`).

Tidak ada yang perlu di-setup di GitHub Releases — `tools/release.sh` membuat
release + meng-upload asset otomatis pakai `GITHUB_TOKEN` bawaan Actions.

### 5. Ambil `MOBILE_RELEASE_PUBLISH_TOKEN` dari owner

Endpoint `POST /api/mobile/android/releases` di proyek **`school-community`** dijaga
env var `MOBILE_RELEASE_PUBLISH_TOKEN` (di Vercel env `school-community`).

- **Minta nilainya ke owner** — jangan generate sendiri (harus sama persis dengan
  yang sudah di-set di Vercel).
- Nilai itu dipakai sebagai GitHub Secret di repo mobile (langkah 6).

Kontrak yang dipakai `tools/release.sh` (sesuai `docs/mobile-release-registry.md`):
build APK → `gh release create/upload v<versionName>` → `apkUrl` =
`https://github.com/<owner>/school-community-mobile/releases/download/v<ver>/school-community-<ver>.apk`
→ `curl -sIL apkUrl` (harus 200) → `POST /releases` **`application/json`**
`{ versionName, versionCode, apkUrl, fileName, applicationName, minimumSupportedVersion,
releaseNotes, forceUpdate, publishedAt }` header
`Authorization: Bearer $MOBILE_RELEASE_PUBLISH_TOKEN`. Respons `201` = rilis baru jadi
`isLatest`. Error: `401` (token), `409` (versionCode dobel), `422` (validasi).

### 6. Tambah GitHub Secrets

Repo GitHub (mobile) → **Settings → Secrets and variables → Actions → New repository secret**.
Buat 5 secret ini (nama harus persis):

| Nama secret | Isi |
|---|---|
| `SC_KEYSTORE_BASE64` | isi file `keystore.b64` dari langkah 3 |
| `SC_KEYSTORE_PASS` | password keystore (yang diminta `keytool`) |
| `SC_KEY_ALIAS` | `schoolcommunity` (alias dari langkah 2) |
| `SC_KEY_PASS` | password key (biasanya sama dengan password keystore) |
| `MOBILE_RELEASE_PUBLISH_TOKEN` | dari langkah 5 (minta ke owner) |

`GITHUB_TOKEN` **tidak perlu dibuat** — otomatis tersedia di Actions.

> Aturan keamanan: repo/aplikasi Android **tidak pernah** menyimpan
> `MOBILE_RELEASE_PUBLISH_TOKEN`. Aplikasi hanya baca `GET /latest` (tanpa auth).

---

## B. Setiap kali rilis versi baru

Sumber tunggal versi: `app/build.gradle.kts` (`versionCode` / `versionName`).

### Opsi 1 — push ke main (otomatis)

```bash
# 1. di app/build.gradle.kts: versionCode = 2, versionName = "1.1.0"
git commit -am "Tarik untuk muat ulang + banner update"   # pesan commit = catatan rilis
git push origin main
```
Workflow `release-android` jalan tiap push ke `main`:
- **versi belum naik** → cek registry, **lewati** (run hijau, tidak ada rilis).
- **versionCode naik** → build APK bertanda tangan → cek identitas → `gh release create v1.1.0`
  + upload APK → `POST /api/mobile/android/releases`. HP yang masih v1 langsung lihat banner.

Catatan rilis diambil dari **subject commit** terakhir (atau isi `notes` di Run workflow).

### Opsi 2 — Run workflow manual (rilis khusus)

Actions → **release-android** → **Run workflow**. Versi tetap dibaca dari `build.gradle.kts`;
input hanya untuk `forceUpdate`, `minimumSupportedVersion`, dan `notes`.

### Opsi 3 — dari laptop

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
export SC_KEYSTORE="/path/school-community-release.jks"
export SC_KEYSTORE_PASS=... SC_KEY_ALIAS=schoolcommunity SC_KEY_PASS=...

# uji dulu tanpa publish (build + validasi APK saja):
tools/release.sh 1.1.0 2 --note "Perbaikan unduhan PDF"

# publish beneran (butuh GitHub CLI `gh` yang sudah `gh auth login`):
export MOBILE_RELEASE_PUBLISH_TOKEN=xxxxx
tools/release.sh 1.1.0 2 --note "Perbaikan unduhan PDF" --publish
```

Flag: `--force` (update wajib), `--min <versionCode>` (batas versi minimum didukung),
`--allow-dirty` (lewati cek git bersih), `--no-bump` (build.gradle.kts sudah di versi itu —
dipakai workflow saat dipicu tag).

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
| **GitHub (repo mobile)** | pastikan repo **PUBLIC** (host APK) |
| **GitHub (repo mobile) → Settings → Secrets → Actions** | `SC_KEYSTORE_BASE64`, `SC_KEYSTORE_PASS`, `SC_KEY_ALIAS`, `SC_KEY_PASS`, `MOBILE_RELEASE_PUBLISH_TOKEN` |
| **GitHub (repo mobile) → Actions** | jalankan workflow **`release-android`** (`.github/workflows/release-android.yml`) tiap rilis |
| **Owner** | kasih nilai `MOBILE_RELEASE_PUBLISH_TOKEN` (sama dengan env Vercel `school-community`) |
| **Proyek web (repo `school-community`)** | tidak ada perubahan; kontrak sesuai `docs/mobile-release-registry.md` |
| **Proyek mobile ini** | tidak ada env/secret di repo; APK di GitHub Release asset; app hanya baca `GET /latest` |
