#!/usr/bin/env bash
# LightHouse TV — build the APK. Needs JDK 17+ and the Android SDK platform 34.
#
#   ./tool/build.sh
#
# The result is copied to dist/lighthouse-tv-1.0.0.apk so the install scripts have one predictable file.

set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/lib.sh"

cd "$REPO_ROOT"

step "Checking Java"
if ! command -v java >/dev/null 2>&1; then
    die "java was not found. Install JDK 17 (Temurin, or the one bundled with Android Studio)."
fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ { print $2 }' | cut -d. -f1)"
say "java: $(java -version 2>&1 | head -1)"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 17 ] 2>/dev/null; then
    die "This project needs JDK 17 or newer (found $JAVA_MAJOR). Set JAVA_HOME to a JDK 17 and retry."
fi

step "Checking the Android SDK"
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
    for d in \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "$HOME/android-sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"; do
        if [ -d "$d/platforms" ]; then
            printf 'sdk.dir=%s\n' "$d" >local.properties
            say "Wrote local.properties -> $d"
            break
        fi
    done
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
    die "The Android SDK was not found.

Install it from Android Studio, or install just what is needed:
  sdkmanager 'platforms;android-34' 'build-tools;34.0.0'
then set ANDROID_HOME to the SDK directory."
fi

[ -x ./gradlew ] || chmod +x ./gradlew

step "Building"
./gradlew --no-daemon --console=plain assembleRelease \
    -x lintVitalRelease -x lintVitalAnalyzeRelease

step "Collecting the APK"
mkdir -p dist
cp -f app/build/outputs/apk/release/app-release.apk "dist/$APK_NAME"
ok "dist/$APK_NAME ($(du -h "dist/$APK_NAME" | cut -f1))"
say ""
say "Install it on a TV with:  ./tool/install.sh"
