#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_CODE="$(awk -F= '$1 == "syncClipboard.versionCode" { print $2 }' "$ROOT_DIR/gradle.properties")"
VERSION_NAME="$(awk -F= '$1 == "syncClipboard.versionName" { print $2 }' "$ROOT_DIR/gradle.properties")"
EXPECTED_CERTIFICATE="$(tr -d '[:space:]' < "$ROOT_DIR/gradle/release-certificate.sha256")"
APP_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
EXTENSION_APK="$ROOT_DIR/system-extension/build/outputs/apk/release/system-extension-release.apk"
CHECKSUM_FILE="$ROOT_DIR/build/release/SHA256SUMS"
VERIFY_TEMP="$ROOT_DIR/build/release/verify-temp"
rm -rf "$VERIFY_TEMP"
mkdir -p "$VERIFY_TEMP"
trap 'rm -rf "$VERIFY_TEMP"' EXIT

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
    echo "Missing release version in gradle.properties" >&2
    exit 1
fi
if [[ -n "${RELEASE_TAG:-}" && "$RELEASE_TAG" != "v$VERSION_NAME" ]]; then
    echo "Release tag $RELEASE_TAG does not match version v$VERSION_NAME" >&2
    exit 1
fi

ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
    exit 1
fi
BUILD_TOOLS_DIR=""
for candidate in "$ANDROID_SDK_ROOT"/build-tools/*; do
    [[ -d "$candidate" ]] || continue
    if [[ -z "$BUILD_TOOLS_DIR" || "${candidate##*/}" > "${BUILD_TOOLS_DIR##*/}" ]]; then
        BUILD_TOOLS_DIR="$candidate"
    fi
done
AAPT="$BUILD_TOOLS_DIR/aapt"
[[ -x "$AAPT" ]] || AAPT="$BUILD_TOOLS_DIR/aapt.exe"
APKSIGNER_JAR="$BUILD_TOOLS_DIR/lib/apksigner.jar"

for required in "$AAPT" "$APKSIGNER_JAR" "$APP_APK" "$EXTENSION_APK"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing release verification input: $required" >&2
        exit 1
    fi
done

verify_apk() {
    local apk="$1"
    local expected_package="$2"
    local badging certificate normalized_certificate normalized_expected
    badging="$("$AAPT" dump badging "$apk")"
    grep -Fq "name='$expected_package'" <<<"$badging"
    grep -Fq "versionCode='$VERSION_CODE'" <<<"$badging"
    grep -Fq "versionName='$VERSION_NAME'" <<<"$badging"

    certificate="$(
        java -jar "$APKSIGNER_JAR" verify --print-certs "$apk" 2>&1 |
            awk -F': ' 'tolower($0) ~ /certificate sha-256 digest/ { print $NF; exit }'
    )"
    normalized_certificate="$(printf '%s' "$certificate" | tr '[:upper:]' '[:lower:]')"
    normalized_expected="$(printf '%s' "$EXPECTED_CERTIFICATE" | tr '[:upper:]' '[:lower:]')"
    if [[ "$normalized_certificate" != "$normalized_expected" ]]; then
        echo "Unexpected signing certificate for $(basename "$apk")" >&2
        printf 'Expected: %s (%s characters)\n' "$normalized_expected" "${#normalized_expected}" >&2
        printf 'Actual:   %s (%s characters)\n' "$normalized_certificate" "${#normalized_certificate}" >&2
        exit 1
    fi

    rm -rf "$VERIFY_TEMP/assets"
    (cd "$VERIFY_TEMP" && jar xf "$apk" assets/LICENSE)
    if [[ "$(sha256sum "$VERIFY_TEMP/assets/LICENSE" | awk '{ print $1 }')" != \
          "$(sha256sum "$ROOT_DIR/LICENSE" | awk '{ print $1 }')" ]]; then
        echo "Packaged license differs in $(basename "$apk")" >&2
        exit 1
    fi
}

verify_apk "$APP_APK" "com.neko7ina.syncclipboard"
verify_apk "$EXTENSION_APK" "com.neko7ina.syncclipboard.extension"

mkdir -p "$(dirname "$CHECKSUM_FILE")"
{
    for apk in "$APP_APK" "$EXTENSION_APK"; do
        printf '%s  %s\n' "$(sha256sum "$apk" | awk '{ print $1 }')" "$(basename "$apk")"
    done
} > "$CHECKSUM_FILE"

printf 'Verified release %s (%s)\n' "$VERSION_NAME" "$VERSION_CODE"
cat "$CHECKSUM_FILE"
