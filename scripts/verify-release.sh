#!/usr/bin/env bash
set -euo pipefail

for required_command in awk grep jar java sha256sum; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "Missing release verification command: $required_command" >&2
        exit 1
    fi
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
read_property() {
    awk -F= -v key="$1" '$1 == key { print $2 }' "$ROOT_DIR/gradle.properties"
}

VERSION_CODE="$(read_property syncClipboard.versionCode)"
VERSION_NAME="$(read_property syncClipboard.versionName)"

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
    echo "Missing release version in gradle.properties" >&2
    exit 1
fi

EXPECTED_CERTIFICATE="$(tr -d '[:space:]' < "$ROOT_DIR/gradle/release-certificate.sha256")"
# 发布产物由 `./gradlew distRelease` 收集到 build/dist，文件名带版本号。
DIST_DIR="$ROOT_DIR/build/dist"
RELEASE_APK="$DIST_DIR/SyncClipboard-Kotlin-$VERSION_NAME.apk"
CHECKSUM_FILE="$ROOT_DIR/build/release/SHA256SUMS"
VERIFY_TEMP="$ROOT_DIR/build/release/verify-temp"
rm -rf "$VERIFY_TEMP"
mkdir -p "$VERIFY_TEMP"
trap 'rm -rf "$VERIFY_TEMP"' EXIT

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

for required in "$AAPT" "$APKSIGNER_JAR"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing release verification tool: $required" >&2
        exit 1
    fi
done

if [[ ! -f "$RELEASE_APK" ]]; then
    echo "build/dist 下没有发布产物：$RELEASE_APK" >&2
    echo "请先运行 './gradlew distRelease' 构建并收集发布产物。" >&2
    exit 1
fi

# 标签必须与 gradle.properties 的版本号一致，避免打出与产物不符的 Release。
if [[ -n "${RELEASE_TAG:-}" && "$RELEASE_TAG" != "v$VERSION_NAME" ]]; then
    echo "Release tag $RELEASE_TAG 与版本 v$VERSION_NAME 不匹配" >&2
    exit 1
fi

verify_apk() {
    local apk="$1"
    local badging certificate normalized_certificate normalized_expected
    badging="$("$AAPT" dump badging "$apk")"
    grep -Fq "name='com.neko7ina.syncclipboard'" <<<"$badging"
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

verify_apk "$RELEASE_APK"

mkdir -p "$(dirname "$CHECKSUM_FILE")"
printf '%s  %s\n' \
    "$(sha256sum "$RELEASE_APK" | awk '{ print $1 }')" \
    "$(basename "$RELEASE_APK")" > "$CHECKSUM_FILE"

printf 'Verified release (%s)\n' "$(basename "$RELEASE_APK")"
cat "$CHECKSUM_FILE"
