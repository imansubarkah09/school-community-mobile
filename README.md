# School Community — Android Shell

A thin native Android container that loads the existing web app at
`https://schoolcommunity.space` in a full-screen WebView. All business logic stays
in the web app; this project only adds Android platform glue.

---

## 1. Technology decision

**Chosen: Native Android WebView (Kotlin), single Activity.**

| Option | Why not |
|---|---|
| **Capacitor** | Pulls in Node/npm + a plugin runtime and a JS bridge we don't need. The web app is already deployed and responsive — we're not bundling web assets, just pointing at a URL. Extra moving parts, extra upgrades, no payoff here. |
| **Trusted Web Activity (TWA)** | Cleanest "invisible shell" *if* you can publish `assetlinks.json` on the domain. We were told not to touch the production site, and without Digital Asset Links a TWA shows a browser URL bar. TWA also gives less control over the offline screen, file chooser, and authenticated downloads. |
| **Native WebView** ✅ | ~200 lines, one dependency-light module, direct control over back-nav, external links, file upload/download with session cookies, and a Bahasa Indonesia offline screen. Standard Gradle → APK. Play-Store-ready as-is. |

Trade-offs accepted: WebView tracks the system WebView/Chrome version (fine — it's evergreen on all supported devices), and Google OAuth needs a small user-agent tweak (done — see `MainActivity.kt`).

**Future needs:** push notifications → add Firebase Messaging to this same module. Deep links → add an `intent-filter` to `MainActivity` + `assetlinks.json`. iOS → this shell is small enough to re-implement natively with `WKWebView`; nothing here locks that out.

---

## 2. Project structure

```
school-community-mobile/
├── build.gradle.kts               # plugin versions (AGP 8.5.2, Kotlin 1.9.24)
├── settings.gradle.kts
├── gradle.properties
├── local.properties               # sdk.dir (git-ignored)
├── gradlew / gradlew.bat          # Gradle 8.9 wrapper
└── app/
    ├── build.gradle.kts           # applicationId, min/target SDK, deps
    └── src/main/
        ├── AndroidManifest.xml    # perms, HTTPS-only, single launcher activity
        ├── java/space/schoolcommunity/app/MainActivity.kt   # the whole shell
        └── res/
            ├── layout/activity_main.xml          # WebView + spinner + offline view
            ├── values/strings.xml                # Bahasa Indonesia UI text
            ├── values/themes.xml, colors.xml
            ├── xml/network_security_config.xml   # cleartext disabled
            ├── drawable/ic_launcher_foreground.xml   # TEMP placeholder icon
            └── mipmap-anydpi-v26/ic_launcher*.xml
```

- **Application ID:** `space.schoolcommunity.app` (reverse of `schoolcommunity.space`)
- **minSdk 26 (Android 8.0), targetSdk 34, versionName 1.0.0**

---

## 3. Implementation summary

Everything is in [`MainActivity.kt`](app/src/main/java/space/schoolcommunity/app/MainActivity.kt):

| Requirement | How |
|---|---|
| Load site on launch | `webView.loadUrl(START_URL)` |
| Full-screen, no native chrome | `NoActionBar` theme, WebView = `match_parent` |
| Back button | `OnBackPressedCallback`: `webView.goBack()` while history exists, otherwise disable the callback and let the system finish the activity — no loop |
| External links | `shouldOverrideUrlLoading`: same-host + Google domains load in-app; everything else (`tel:`, `mailto:`, `sms:`, `geo:`, `whatsapp:`, `wa.me`, `intent:`, other sites) → `ACTION_VIEW` to the right app |
| Google auth | Third-party cookies on; `"; wv"` stripped from the user agent so Google doesn't reject the WebView; `accounts.google.com` / `*.google.com` allowed to load in-app |
| File upload | `WebChromeClient.onShowFileChooser` → `ActivityResultLauncher` with the system picker |
| File download | `setDownloadListener` → `DownloadManager`, forwards the `User-Agent` **and session `Cookie`** so login-gated PDF/Excel exports work; saves to public **Downloads** with a completion notification |
| Offline / load error | `onReceivedError` for the main frame → Bahasa Indonesia panel with a **Coba Lagi** button |
| Loading state | Indeterminate `ProgressBar`, hidden on `onPageFinished` |
| HTTPS only | `network_security_config.xml` + `usesCleartextTraffic="false"` |
| Rotation | `configChanges` keeps the WebView alive (no reload, no state loss) |

**Security defaults applied:** no `@JavascriptInterface` bridge, no file/content URL access, SSL errors not overridden (WebView cancels by default), navigation restricted to trusted hosts, cleartext disabled, no secrets in the repo.

Dependencies: `core-ktx`, `appcompat`, `activity-ktx`. Nothing else.

---

## 4. Build result

Both build clean with the Android Studio JBR (JDK 21) + Android SDK already on this machine:

```
BUILD SUCCESSFUL   :app:assembleDebug     → app-debug.apk    (3.1 MB)
BUILD SUCCESSFUL   :app:assembleRelease   → app-release.apk  (2.5 MB)
BUILD SUCCESSFUL   :app:lintDebug         → warnings only (newer dep versions, targetSdk 34<35)
```

---

## 5. APK location

```
D:\Iman\Private\school-community-mobile\app\build\outputs\apk\debug\app-debug.apk
```

Release (debug-signed for now):
`D:\Iman\Private\school-community-mobile\app\build\outputs\apk\release\app-release.apk`

Install on a phone (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the `.apk` to the phone and tap it (allow "install from unknown sources").

---

## 6. Manual Android test checklist

Test on a real device — none of this has been verified on hardware.

| # | Test | Expected |
|---|---|---|
| 1 | Tap the **School Community** icon | App opens, spinner shows briefly |
| 2 | Initial load | Web app renders full-screen, no URL bar, no action bar |
| 3 | Google login | Google account chooser / consent works, returns to the app logged in |
| 4 | Login persistence | Kill the app, reopen → still logged in |
| 5 | Internal navigation | Tapping menu items navigates inside the WebView |
| 6 | Back button (with history) | Goes back one web page |
| 7 | Back button (on the first page) | App exits normally, no loop |
| 8 | WhatsApp link | Opens WhatsApp (or chooser), not the WebView |
| 9 | External website link | Opens the phone's browser |
| 10 | `tel:` / `mailto:` link | Opens dialer / email app |
| 11 | PDF download | Downloads, notification appears, file opens from Downloads |
| 12 | Excel / report export | Same — file lands in Downloads and opens |
| 13 | File upload (`<input type=file>`) | System picker opens; chosen file/photo uploads |
| 14 | Airplane mode → launch | Bahasa Indonesia "Tidak dapat memuat halaman" + **Coba Lagi** |
| 15 | Turn network back on → **Coba Lagi** | Site loads |
| 16 | Rotate the screen | No reload, no lost form input |
| 17 | Background the app, return | Resumes on the same page (URL unchanged); update re-checked |
| 18 | Cold launch while logged in (session cookie present) | Opens `/dashboard` directly |
| 19 | Cold launch while logged out | Opens `/` |
| 20 | Cold launch, cookie present but session expired | `/dashboard` → server redirects to `/login?next=/dashboard`, log in, land on dashboard |
| 21 | Fresh install, no release published | App works normally, no update prompt (API 404 NO_RELEASE) |
| 22 | Publish v2 (`versionCode` 2), keep v1 | Blue banner "Versi baru tersedia (v…)" over the WebView; [Tutup] hides it, reappears next cold launch |
| 23 | Tap **Perbarui** on the banner / dialog | `apkUrl` opens in the browser (downloads the APK) |
| 24 | Publish v2 with `--force` (or `minimumSupportedVersion` > installed) | "Wajib Perbarui" dialog, non-cancelable, only [Perbarui Sekarang]; Back does nothing |
| 25 | Airplane mode during update check | No prompt, app usable (graceful) |
| 26 | Install the downloaded APK, relaunch | New `versionCode` ≥ latest → no further prompt |

---

## 7. Known limitations / future work

- **Not tested on hardware.** All items in §6 need a real device.
- **Google OAuth** relies on the user-agent tweak. If Google ever hard-blocks the
  WebView, the fallback is a Chrome Custom Tab for the auth leg — not implemented
  (YAGNI until it breaks).
- **Placeholder launcher icon.** `res/drawable/ic_launcher_foreground.xml` is a
  generic glyph. Replace via Android Studio → *New → Image Asset*, which writes
  `res/mipmap-*/ic_launcher*`. Add a 512×512 icon for the Play Store listing.
- **Release signing:** wired to env vars (see §9), falls back to the debug keystore
  when unset so `assembleRelease` still runs locally. No production keystore exists yet.
- **No offline caching.** Offline = the error screen. Acceptable for a shell.
- **targetSdk 34.** Bump to 35 before the next Play Store deadline; needs `platforms;android-35`.
- **Push notifications / deep links** not implemented — see §1 for the path.
- **Release tooling not run end-to-end.** `tools/release.sh` matches the web repo's
  `docs/mobile-release-registry.md` and is validated up to the network calls (build + APK
  identity checks pass); the `gh release` upload + `POST /releases` need real credentials to exercise.

---

## 8. In-app update integration

The app consumes the web project's **Mobile Release Registry** as the single source of
truth. It never scans APK filenames, blob listings, or hardcoded URLs.

**API used (read-only):** `GET https://schoolcommunity.space/api/mobile/android/latest`

Actual production contract observed:
- No release yet → `HTTP 404 {"error":"NO_RELEASE"}` → app treats as "no update", continues.
- Once published → JSON object; the client reads `versionCode` (int, required),
  `apkUrl` (https, required), `versionName`, `minimumSupportedVersion`, `fileName`,
  `releaseNotes[]`, `forceUpdate`. Unknown/missing optional fields are tolerated.

**Launch URL** ([`MainActivity.startUrl()`](app/src/main/java/space/schoolcommunity/app/MainActivity.kt)):
cold launch checks `CookieManager.getCookie(BASE_URL)` for `better-auth.session_token` →
`$BASE_URL/dashboard` if present, else `$BASE_URL/`. Session expiry is the server's job
(`/dashboard` → `/login?next=/dashboard`). Return-from-background never changes the URL.
`BASE_URL` is one constant.

**Update check** ([`update/`](app/src/main/java/space/schoolcommunity/app/update/)) — runs on
every `onResume` (cold launch **and** return-from-background), off-thread:
```
ReleaseApi.fetchLatest()   (5s timeout, useCaches=false, never throws → null on any failure/404)
  → UpdateDecision.of(BuildConfig.VERSION_CODE, release)   (versionCode ints only)
      NONE       → nothing
      OPTIONAL   → dismissible banner over the WebView: "Versi baru tersedia (v…)"
                   [Perbarui] [Tutup]. Dismiss is session-only (no persistence) → reappears
                   next cold launch.
      MANDATORY  → non-cancelable AlertDialog "Wajib Perbarui" (body = releaseNotes),
                   one button [Perbarui Sekarang].
```
Both buttons → `Intent.ACTION_VIEW` to `apkUrl` (browser / Download manager handles the
download; user installs the APK manually). No in-app download or installer flow.

- **Optional:** `latestVersionCode > installed`, `!forceUpdate`, `installed >= minimumSupportedVersion`.
- **Mandatory:** `forceUpdate == true` **or** `installed < minimumSupportedVersion`.
- **API down / malformed / 404:** `fetchLatest()` → null → `NONE`. Never blocks.

Logic is unit-tested: `./gradlew :app:testDebugUnitTest`
([`UpdateCheckerTest`](app/src/test/java/space/schoolcommunity/app/update/UpdateCheckerTest.kt)).

---

## 9. Release process

Single source of version truth: `versionCode` / `versionName` in
[`app/build.gradle.kts`](app/build.gradle.kts) `defaultConfig`.

```bash
# dry run: bump version, build signed APK, validate identity, print metadata + publish plan
tools/release.sh 1.1.0 2 --note "Perbaikan X" --note "Fitur Y"

# real publish (adds --publish; needs `gh` authenticated)
MOBILE_RELEASE_PUBLISH_TOKEN=yyy tools/release.sh 1.1.0 2 --note "..." --publish
# flags: --force (mandatory update)  --min <versionCode>  --allow-dirty
```

APKs are hosted as **GitHub Release assets** of this repo (repo must be **public**); the
web app (`docs/mobile-release-registry.md`) only stores the release metadata + `apkUrl`.

The script: validates args + `versionCode` increased + not already published +
`1 ≤ min ≤ versionCode` → writes the version into `build.gradle.kts` → `assembleRelease` →
renames to the immutable `school-community-<versionName>.apk` → `aapt` checks
package id / versionCode / versionName → (with `--publish`) `gh release create/upload
v<versionName>` → `curl -sIL` the asset URL (expect 200) → `POST` **JSON** to
`/api/mobile/android/releases` with `Authorization: Bearer $MOBILE_RELEASE_PUBLISH_TOKEN`
→ verifies `/latest`. Handles `201` / `401` / `409 DUPLICATE_VERSION` / `422 INVALID_INPUT`.

`apkUrl` = `https://github.com/<owner>/school-community-mobile/releases/download/v<ver>/school-community-<ver>.apk`

**Required env:**
| var | for | notes |
|---|---|---|
| `SC_KEYSTORE`, `SC_KEYSTORE_PASS`, `SC_KEY_ALIAS`, `SC_KEY_PASS` | signed release build | keystore path + creds; **never commit** |
| `MOBILE_RELEASE_PUBLISH_TOKEN` | `--publish` only | Bearer token for `POST /releases`; same value as the web app's env var (ask the owner) |
| `GH_TOKEN` / `gh auth login` | `--publish` only | uploads the APK asset; in CI it's the automatic `GITHUB_TOKEN` |
| `JAVA_HOME`, `ANDROID_HOME` | build | JDK 17+, Android SDK |

The Android app holds **no** write credentials — it only reads `/latest` (no auth).

Generate the production keystore once:
```bash
keytool -genkey -v -keystore school-community-release.jks -alias schoolcommunity \
  -keyalg RSA -keysize 2048 -validity 10000
```
`applicationId` (`space.schoolcommunity.app`) and this keystore must stay stable for the
life of the app — updates install over the previous version only if the signature matches.

---

## Development commands

```bash
# Prereqs: Android SDK (platform-34, build-tools 34), JDK 17+ (Android Studio JBR works).
# Set once if gradlew can't find Java:
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew :app:assembleDebug        # debug APK  → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # release APK → app/build/outputs/apk/release/app-release.apk
./gradlew :app:installDebug         # build + install to a connected device/emulator
./gradlew :app:testDebugUnitTest    # update-logic unit tests
./gradlew :app:lintDebug            # static checks
./gradlew clean
node tools/download-js.test.js      # blob-download JS interception check
tools/release.sh <ver> <code> ...   # cut a release (see §9)
```

No emulator here; use `adb devices` + `./gradlew installDebug`, or open the folder in Android Studio and Run.
