#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT"

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Android SDK is missing. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 2
fi
if ! command -v java >/dev/null 2>&1; then
    echo "JDK 17 is required." >&2
    exit 2
fi
if command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon :app:assembleDebug
else
    ./gradlew --no-daemon :app:assembleDebug
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -s "$APK" ] || { echo "Build completed without a debug APK at $APK" >&2; exit 3; }
echo "APK ready: $APK"
