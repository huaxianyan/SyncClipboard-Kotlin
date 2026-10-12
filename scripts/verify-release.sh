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

# 主体与系统扩展各自独立维护版本号，一次发布可能只包含其中一个模块。
APP_VERSION_CODE="$(read_property syncClipboard.versionCode)"
APP_VERSION_NAME="$(read_property syncClipboard.versionName)"
EXTENSION_VERSION_CODE="$(read_property syncClipboard.extension.versionCode)"
EXTENSION_VERSION_NAME="$(read_property syncClipboard.extension.versionName)"

if [[ -z "$APP_VERSION_CODE" || -z "$APP_VERSION_NAME" ||
      -z "$EXTENSION_VERSION_CODE" || -z "$EXTENSION_VERSION_NAME" ]]; then
    echo "Missing release version in gradle.properties" >&2
    exit 1
fi

EXPECTED_CERTIFICATE="$(tr -d '[:space:]' < "$ROOT_DIR/gradle/release-certificate.sha256")"
# 发布产物由 `./gradlew distRelease` 收集到 build/dist，文件名带各自的版本号。
DIST_DIR="$ROOT_DIR/build/dist"
APP_APK="$DIST_DIR/SyncClipboard-Kotlin-$APP_VERSION_NAME.apk"
EXTENSION_APK="$DIST_DIR/SyncClipboard-Kotlin-Extension-$EXTENSION_VERSION_NAME.apk"
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

# 只校验本次实际发布的模块，`-Prelease.modules` 决定 distRelease 产出哪些 APK。
RELEASED_APKS=()
if [[ -f "$APP_APK" ]]; then
    RELEASED_APKS+=("$APP_APK")
fi
if [[ -f "$EXTENSION_APK" ]]; then
    RELEASED_APKS+=("$EXTENSION_APK")
fi
if [[ ${#RELEASED_APKS[@]} -eq 0 ]]; then
    echo "build/dist 下没有发布产物" >&2
    echo "请先运行 './gradlew distRelease' 构建并收集发布产物。" >&2
    exit 1
fi

if [[ -n "${RELEASE_TAG:-}" ]]; then
    tag_ok=0
    for apk in "${RELEASED_APKS[@]}"; do
        if [[ "$apk" == "$APP_APK" && "$RELEASE_TAG" == "v$APP_VERSION_NAME" ]]; then
            tag_ok=1
        fi
        if [[ "$apk" == "$EXTENSION_APK" && "$RELEASE_TAG" == "v$EXTENSION_VERSION_NAME" ]]; then
            tag_ok=1
        fi
    done
    if [[ "$tag_ok" -eq 0 ]]; then
        echo "Release tag $RELEASE_TAG 与本次发布的模块版本不匹配" >&2
        echo "主体 v$APP_VERSION_NAME，系统扩展 v$EXTENSION_VERSION_NAME" >&2
        exit 1
    fi
fi

verify_apk() {
    local apk="$1"
    local expected_package="$2"
    local expected_code="$3"
    local expected_name="$4"
    local badging certificate normalized_certificate normalized_expected
    badging="$("$AAPT" dump badging "$apk")"
    grep -Fq "name='$expected_package'" <<<"$badging"
    grep -Fq "versionCode='$expected_code'" <<<"$badging"
    grep -Fq "versionName='$expected_name'" <<<"$badging"

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

for apk in "${RELEASED_APKS[@]}"; do
    if [[ "$apk" == "$APP_APK" ]]; then
        verify_apk "$apk" "com.neko7ina.syncclipboard" "$APP_VERSION_CODE" "$APP_VERSION_NAME"
    else
        verify_apk "$apk" "com.neko7ina.syncclipboard.extension" \
            "$EXTENSION_VERSION_CODE" "$EXTENSION_VERSION_NAME"
    fi
done

mkdir -p "$(dirname "$CHECKSUM_FILE")"
{
    for apk in "${RELEASED_APKS[@]}"; do
        printf '%s  %s\n' "$(sha256sum "$apk" | awk '{ print $1 }')" "$(basename "$apk")"
    done
} > "$CHECKSUM_FILE"

printf 'Verified release (%d APK)\n' "${#RELEASED_APKS[@]}"
cat "$CHECKSUM_FILE"
