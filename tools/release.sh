#!/usr/bin/env bash
#
# Repeatable Android release: bump version -> build signed APK -> validate ->
# publish metadata + APK to the Mobile Release Registry (the web project owns it).
#
#   tools/release.sh 1.1.0 2 --note "Perbaikan unduhan PDF" --note "Deteksi pembaruan" [--force] [--min 2] [--publish]
#
# Without --publish it is a DRY RUN: it builds + validates + prints the exact publish
# request, but does not call the write API.
#
# Required env for a signed build:
#   SC_KEYSTORE, SC_KEYSTORE_PASS, SC_KEY_ALIAS, SC_KEY_PASS   (see README "Release signing")
#   JAVA_HOME            (JDK 17+, e.g. Android Studio's jbr)
# Required for --publish:
#   MOBILE_RELEASE_PUBLISH_TOKEN  Bearer token for POST /api/mobile/android/releases
#                                 (same value as the web app's env var; ask the owner)
#   gh (GitHub CLI), authenticated — uploads the APK as a GitHub Release asset.
#     In CI: `env: GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` (already available).
#   The repo (or a public "releases mirror") must be PUBLIC so the Android app can
#   download the asset without a token.
#
set -euo pipefail

API="https://schoolcommunity.space/api/mobile/android"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"

VERSION_NAME="${1:-}"; VERSION_CODE="${2:-}"; shift $(( $# >= 2 ? 2 : $# )) || true
FORCE=false; PUBLISH=false; MIN_SUPPORTED=""; ALLOW_DIRTY=false; NO_BUMP=false
NOTES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --note) NOTES+=("$2"); shift 2 ;;
    --force) FORCE=true; shift ;;
    --publish) PUBLISH=true; shift ;;
    --min) MIN_SUPPORTED="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=true; shift ;;
    --no-bump) NO_BUMP=true; shift ;;  # build.gradle.kts already at this version (tag-triggered CI)
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || die "usage: release.sh <versionName x.y.z> <versionCode int> [--note ..] [--force] [--min N] [--publish]"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versionName must be x.y.z"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "versionCode must be a positive integer"
[ -z "$MIN_SUPPORTED" ] || [[ "$MIN_SUPPORTED" =~ ^[0-9]+$ ]] || die "--min must be an integer"
: "${MIN_SUPPORTED:=1}"
# Contract: 1 <= minimumSupportedVersion <= versionCode
[ "$MIN_SUPPORTED" -ge 1 ] && [ "$MIN_SUPPORTED" -le "$VERSION_CODE" ] \
  || die "--min must be between 1 and versionCode ($VERSION_CODE)"

CURRENT_CODE="$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+')"
CURRENT_NAME="$(grep -oE 'versionName = "[0-9]+\.[0-9]+\.[0-9]+"' "$GRADLE_FILE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
if $NO_BUMP; then
  [ "$VERSION_CODE" = "$CURRENT_CODE" ] && [ "$VERSION_NAME" = "$CURRENT_NAME" ] \
    || die "--no-bump: build.gradle.kts is $CURRENT_NAME ($CURRENT_CODE), not $VERSION_NAME ($VERSION_CODE)"
else
  [ "$VERSION_CODE" -gt "$CURRENT_CODE" ] || die "versionCode $VERSION_CODE must be > current $CURRENT_CODE"
fi

if ! $ALLOW_DIRTY && command -v git >/dev/null && git -C "$ROOT" rev-parse >/dev/null 2>&1; then
  [ -z "$(git -C "$ROOT" status --porcelain)" ] || die "working tree is dirty (commit, or pass --allow-dirty)"
fi

# Don't reuse a versionCode already in the registry. With --no-bump (push-to-main CI on
# a commit that didn't touch the version) this is normal -> skip quietly, don't fail.
if command -v curl >/dev/null && curl -fsS "$API/releases" 2>/dev/null | grep -q "\"versionCode\":$VERSION_CODE\b"; then
  $NO_BUMP && { echo ">> versionCode $VERSION_CODE sudah dipublish — tidak ada versi baru, lewati."; exit 0; }
  die "versionCode $VERSION_CODE is already published"
fi

if [ -z "${SC_KEYSTORE:-}" ]; then
  echo "WARN: SC_KEYSTORE unset -> APK will be DEBUG-signed and is NOT suitable for distribution." >&2
fi

if ! $NO_BUMP; then
  echo ">> setting version $VERSION_NAME ($VERSION_CODE) in build.gradle.kts"
  sed -i -E "s/versionCode = [0-9]+/versionCode = $VERSION_CODE/" "$GRADLE_FILE"
  sed -i -E "s/versionName = \"[0-9]+\.[0-9]+\.[0-9]+\"/versionName = \"$VERSION_NAME\"/" "$GRADLE_FILE"
fi

echo ">> building release APK"
( cd "$ROOT" && ./gradlew :app:assembleRelease --no-daemon -q )

SRC_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -s "$SRC_APK" ] || die "release APK not found at $SRC_APK"
OUT_APK="$ROOT/app/build/outputs/apk/release/school-community-$VERSION_NAME.apk"
cp -f "$SRC_APK" "$OUT_APK"

# --- validate the built APK matches what we're about to publish ---
AAPT="$(ls -1 "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$AAPT" ] || die "aapt not found under \$ANDROID_HOME/build-tools — cannot validate APK"
BADGING="$("$AAPT" dump badging "$OUT_APK")" || die "APK is not parseable"
echo "$BADGING" | grep -q "package: name='space.schoolcommunity.app'" || die "APK package id mismatch"
echo "$BADGING" | grep -q "versionCode='$VERSION_CODE'" || die "APK versionCode != $VERSION_CODE"
echo "$BADGING" | grep -q "versionName='$VERSION_NAME'" || die "APK versionName != $VERSION_NAME"

# --- metadata payload (documented contract) ---
notes_json="[]"
if [ ${#NOTES[@]} -gt 0 ]; then
  notes_json="$(printf '%s\n' "${NOTES[@]}" | sed 's/"/\\"/g;s/.*/"&"/' | paste -sd, -)"
  notes_json="[$notes_json]"
fi
PUBLISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
APK_FILE_NAME="school-community-$VERSION_NAME.apk"
# apkUrl is filled in after the GitHub Release upload; placeholder for the dry-run print.
build_meta() { # $1 = apkUrl
  cat <<JSON
{
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "apkUrl": "${1:-<diisi dari GitHub Release asset>}",
  "fileName": "$APK_FILE_NAME",
  "applicationName": "School Community",
  "minimumSupportedVersion": $MIN_SUPPORTED,
  "releaseNotes": $notes_json,
  "forceUpdate": $FORCE,
  "publishedAt": "$PUBLISHED_AT"
}
JSON
}
META="$(build_meta "")"

echo
echo "================= RELEASE SUMMARY ================="
echo " APK        : $OUT_APK  ($(wc -c < "$OUT_APK") bytes)"
echo " versionName: $VERSION_NAME"
echo " versionCode: $VERSION_CODE   (min supported: $MIN_SUPPORTED, forceUpdate: $FORCE)"
echo " metadata   :"; echo "$META" | sed 's/^/   /'
echo "=================================================="

TAG="v$VERSION_NAME"

if ! $PUBLISH; then
  note_args=""; [ ${#NOTES[@]} -gt 0 ] && note_args="$(printf -- '--note "%s" ' "${NOTES[@]}")"
  force_arg=""; $FORCE && force_arg="--force "
  cat <<EOF

DRY RUN — not published. To publish (needs the GitHub CLI 'gh', authenticated):

  MOBILE_RELEASE_PUBLISH_TOKEN=... tools/release.sh $VERSION_NAME $VERSION_CODE ${note_args}${force_arg}--publish

Publish does (repo MUST be public):
  1) gh release create/upload $TAG  -> uploads $APK_FILE_NAME as a release asset
  2) apkUrl = https://github.com/<owner>/<repo>/releases/download/$TAG/$APK_FILE_NAME
  3) curl apkUrl  -> expect HTTP 200
  4) POST $API/releases  (application/json)
       Authorization: Bearer \$MOBILE_RELEASE_PUBLISH_TOKEN
       body: the metadata above, with apkUrl filled in
EOF
  exit 0
fi

[ -n "${MOBILE_RELEASE_PUBLISH_TOKEN:-}" ] || die "--publish needs MOBILE_RELEASE_PUBLISH_TOKEN"
command -v gh >/dev/null || die "--publish needs the GitHub CLI (gh), authenticated"

SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner)" \
  || die "cannot resolve GitHub repo — run 'gh auth login' / check the 'origin' remote"
VIS="$(gh repo view --json visibility -q .visibility 2>/dev/null || echo UNKNOWN)"
# The Android app (and every end user) downloads apkUrl with no auth. A private repo's
# release assets 404 for anonymous requests, so publishing from one produces a dead apkUrl.
[ "$VIS" = "PUBLIC" ] || die "repo $SLUG is $VIS. Make it public (Settings → General → Change repository visibility), or host releases in a separate public repo."
APK_URL="https://github.com/$SLUG/releases/download/$TAG/$APK_FILE_NAME"

echo ">> publishing GitHub Release $TAG (asset: $APK_FILE_NAME)"
if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$OUT_APK" --clobber
else
  notes_file="$(mktemp)"; { [ ${#NOTES[@]} -gt 0 ] && printf '%s\n' "${NOTES[@]}"; } > "$notes_file"
  gh release create "$TAG" "$OUT_APK" --title "$TAG" --notes-file "$notes_file"
  rm -f "$notes_file"
fi

echo ">> verifying asset URL: $APK_URL"
# GET (not HEAD): GitHub redirects assets to a signed URL that often rejects HEAD.
curl -fsL --retry 3 --retry-delay 2 -o /dev/null "$APK_URL" \
  || die "release asset not reachable at $APK_URL (asset name mismatch, or repo not public)"

echo ">> publishing metadata to $API/releases"
RESP=/tmp/sc-release-resp.json
HTTP=$(curl -sS -o "$RESP" -w '%{http_code}' -X POST "$API/releases" \
  -H "Authorization: Bearer $MOBILE_RELEASE_PUBLISH_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$(build_meta "$APK_URL")")
echo "HTTP $HTTP"; cat "$RESP" 2>/dev/null; echo
case "$HTTP" in
  201) : ;;
  401) die "401 UNAUTHORIZED — MOBILE_RELEASE_PUBLISH_TOKEN wrong or not configured on the server" ;;
  409) die "409 DUPLICATE_VERSION — versionCode $VERSION_CODE already published" ;;
  422) die "422 INVALID_INPUT — $(grep -oE '\"message\":\"[^\"]*\"' "$RESP" || true)" ;;
  *)   die "publish failed (HTTP $HTTP)" ;;
esac

echo ">> verifying /latest now reports $VERSION_CODE"
curl -fsS "$API/latest" | grep -q "\"versionCode\":$VERSION_CODE\b" \
  && echo "OK — released $VERSION_NAME ($VERSION_CODE)" \
  || echo "WARN: /latest did not report the new versionCode yet (registry cache is 60s)"
