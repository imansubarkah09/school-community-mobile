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
# Required env for --publish:
#   MOBILE_RELEASE_TOKEN  authenticated write token for POST /api/mobile/android/releases
#                         (never commit this; keep it in the shell / CI secrets)
#
set -euo pipefail

API="https://schoolcommunity.space/api/mobile/android"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"

VERSION_NAME="${1:-}"; VERSION_CODE="${2:-}"; shift $(( $# >= 2 ? 2 : $# )) || true
FORCE=false; PUBLISH=false; MIN_SUPPORTED=""; ALLOW_DIRTY=false
NOTES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --note) NOTES+=("$2"); shift 2 ;;
    --force) FORCE=true; shift ;;
    --publish) PUBLISH=true; shift ;;
    --min) MIN_SUPPORTED="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=true; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || die "usage: release.sh <versionName x.y.z> <versionCode int> [--note ..] [--force] [--min N] [--publish]"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versionName must be x.y.z"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "versionCode must be a positive integer"
[ -z "$MIN_SUPPORTED" ] || [[ "$MIN_SUPPORTED" =~ ^[0-9]+$ ]] || die "--min must be an integer"
: "${MIN_SUPPORTED:=1}"

CURRENT_CODE="$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+')"
[ "$VERSION_CODE" -gt "$CURRENT_CODE" ] || die "versionCode $VERSION_CODE must be > current $CURRENT_CODE"

if ! $ALLOW_DIRTY && command -v git >/dev/null && git -C "$ROOT" rev-parse >/dev/null 2>&1; then
  [ -z "$(git -C "$ROOT" status --porcelain)" ] || die "working tree is dirty (commit, or pass --allow-dirty)"
fi

# Don't reuse a versionCode already in the registry.
if command -v curl >/dev/null; then
  if curl -fsS "$API/releases" 2>/dev/null | grep -q "\"versionCode\":$VERSION_CODE\b"; then
    die "versionCode $VERSION_CODE is already published"
  fi
fi

if [ -z "${SC_KEYSTORE:-}" ]; then
  echo "WARN: SC_KEYSTORE unset -> APK will be DEBUG-signed and is NOT suitable for distribution." >&2
fi

echo ">> setting version $VERSION_NAME ($VERSION_CODE) in build.gradle.kts"
sed -i -E "s/versionCode = [0-9]+/versionCode = $VERSION_CODE/" "$GRADLE_FILE"
sed -i -E "s/versionName = \"[0-9]+\.[0-9]+\.[0-9]+\"/versionName = \"$VERSION_NAME\"/" "$GRADLE_FILE"

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
PUBLISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
META="$(cat <<JSON
{
  "platform": "android",
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "minimumSupportedVersion": $MIN_SUPPORTED,
  "fileName": "school-community-$VERSION_NAME.apk",
  "publishedAt": "$PUBLISHED_AT",
  "releaseNotes": $notes_json,
  "forceUpdate": $FORCE
}
JSON
)"

echo
echo "================= RELEASE SUMMARY ================="
echo " APK        : $OUT_APK  ($(wc -c < "$OUT_APK") bytes)"
echo " versionName: $VERSION_NAME"
echo " versionCode: $VERSION_CODE   (min supported: $MIN_SUPPORTED, forceUpdate: $FORCE)"
echo " metadata   :"; echo "$META" | sed 's/^/   /'
echo "=================================================="

if ! $PUBLISH; then
  note_args=""; [ ${#NOTES[@]} -gt 0 ] && note_args="$(printf -- '--note "%s" ' "${NOTES[@]}")"
  force_arg=""; $FORCE && force_arg="--force "
  cat <<EOF

DRY RUN — not published. To publish:

  MOBILE_RELEASE_TOKEN=... tools/release.sh $VERSION_NAME $VERSION_CODE ${note_args}${force_arg}--publish

The publish step POSTs multipart (apk file + metadata) to:
  POST $API/releases   Authorization: Bearer \$MOBILE_RELEASE_TOKEN

NOTE: confirm the exact field names / whether the APK is uploaded in this call or
separately against the web project's endpoint — that contract is owned there.
EOF
  exit 0
fi

[ -n "${MOBILE_RELEASE_TOKEN:-}" ] || die "--publish needs MOBILE_RELEASE_TOKEN"
echo ">> publishing to $API/releases"
HTTP=$(curl -sS -o /tmp/sc-release-resp.json -w '%{http_code}' -X POST "$API/releases" \
  -H "Authorization: Bearer $MOBILE_RELEASE_TOKEN" \
  -F "apk=@$OUT_APK;type=application/vnd.android.package-archive" \
  -F "metadata=$META")
echo "HTTP $HTTP"; cat /tmp/sc-release-resp.json; echo
[ "$HTTP" -ge 200 ] && [ "$HTTP" -lt 300 ] || die "publish failed"

echo ">> verifying /latest now reports $VERSION_CODE"
curl -fsS "$API/latest" | grep -q "\"versionCode\":$VERSION_CODE\b" \
  && echo "OK — released $VERSION_NAME ($VERSION_CODE)" \
  || echo "WARN: /latest did not report the new versionCode yet (check the registry / 'mark as latest' step)"
