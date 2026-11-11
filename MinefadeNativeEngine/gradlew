#!/usr/bin/env sh
# Lightweight Gradle bootstrapper. It downloads the pinned Gradle distribution
# because this source bundle does not carry a binary wrapper JAR.
set -eu
GRADLE_VERSION="9.6.0"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/minefade-distributions/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle" >&2; exit 2; }
    command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle" >&2; exit 2; }
    mkdir -p "$GRADLE_USER_HOME/minefade-distributions"
    TMP_ZIP="$GRADLE_USER_HOME/minefade-distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" --output "$TMP_ZIP"
    TMP_DIR="$GRADLE_USER_HOME/minefade-distributions/unpack-$GRADLE_VERSION"
    rm -rf "$TMP_DIR"
    mkdir -p "$TMP_DIR"
    unzip -q "$TMP_ZIP" -d "$TMP_DIR"
    rm -rf "$DIST_DIR"
    mv "$TMP_DIR/gradle-$GRADLE_VERSION" "$DIST_DIR"
    rm -rf "$TMP_DIR" "$TMP_ZIP"
fi
exec "$GRADLE_BIN" "$@"
