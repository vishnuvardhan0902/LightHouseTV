#!/usr/bin/env bash
# Shared helpers for the LightHouse TV install scripts. Sourced, never run directly.
# Written for bash 3.2 (macOS) as well as bash 5, so: no arrays, no mapfile, no associative arrays.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
PACKAGE_NAME="tv.lighthouse.launcher"
HOME_ACTIVITY="tv.lighthouse.launcher/.MainActivity"
APK_NAME="lighthouse-tv-1.0.0.apk"

die() {
    printf '\033[31m%s\033[0m\n' "$*" >&2
    exit 1
}

say() { printf '%s\n' "$*"; }

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

ok() { printf '\033[32m%s\033[0m\n' "$*"; }

warn() { printf '\033[33m%s\033[0m\n' "$*"; }

# adb lives in a different place on every machine, so look for it rather than assume PATH.
find_adb() {
    local c
    if [ -n "${ADB:-}" ] && [ -x "${ADB}" ]; then
        printf '%s\n' "$ADB"
        return 0
    fi
    c="$(command -v adb 2>/dev/null || true)"
    if [ -n "$c" ]; then
        printf '%s\n' "$c"
        return 0
    fi
    for c in \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Library/Android/sdk/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb" \
        "/usr/local/bin/adb" \
        "/opt/homebrew/bin/adb" \
        "/usr/lib/android-sdk/platform-tools/adb" \
        "/snap/bin/adb"; do
        if [ -n "$c" ] && [ -x "$c" ]; then
            printf '%s\n' "$c"
            return 0
        fi
    done
    return 1
}

require_adb() {
    ADB_BIN="$(find_adb)" || die "adb was not found.

Install the Android platform tools, then run this again:
  macOS   brew install --cask android-platform-tools
  Windows winget install Google.PlatformTools
  Debian  sudo apt install android-tools-adb

Or set ADB to the full path of your adb binary."
    export ADB_BIN
}

adb_do() {
    "$ADB_BIN" -s "$SERIAL" "$@"
}

adb_devices() {
    "$ADB_BIN" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# A TV reached over the network has to be connected first; over USB it is already there.
ensure_connected() {
    case "$SERIAL" in
        *:*)
            "$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1 || true
            ;;
    esac

    local i=0
    while [ "$i" -lt 15 ]; do
        if adb_do get-state 2>/dev/null | tr -d '\r' | grep -q '^device$'; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done

    local state
    state="$(adb_do get-state 2>&1 | tr -d '\r' | head -1)"
    die "Cannot talk to $SERIAL (adb says: ${state:-no answer}).

  - Is the TV awake and on the same network?
  - Is ADB debugging still enabled in the TV developer options?
  - For a network TV, connect once by USB and run: adb tcpip 5555"
}

pick_serial() {
    if [ -n "${SERIAL:-}" ]; then
        return 0
    fi

    local list count
    list="$(adb_devices)"
    count="$(printf '%s' "$list" | grep -c . || true)"

    if [ "$count" -eq 0 ]; then
        say "No device is connected over adb yet."
        say ""
        say "Over the network (most TVs): find the TV's IP in Settings > Network, then"
        say "  $ADB_BIN connect <tv-ip>:5555"
        say "and run this script again."
        say ""
        say "Over USB: plug the TV in, accept the debugging prompt on screen, and retry."
        die "No adb device found."
    fi

    if [ "$count" -gt 1 ]; then
        say "More than one device is connected:"
        printf '%s\n' "$list" | sed 's/^/  /'
        die "Pick one with --serial <device>."
    fi

    SERIAL="$list"
    export SERIAL
    ok "Using device: $SERIAL"
}

find_apk() {
    local candidate
    for candidate in \
        "${APK:-}" \
        "$REPO_ROOT/dist/$APK_NAME" \
        "$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"; do
        if [ -n "$candidate" ] && [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

resolve_home() {
    adb_do shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
        | tr -d '\r' | tail -n 1
}

# Real launchers only: a ROM's FallbackHome is a stub that does nothing useful once you leave it.
list_home_activities() {
    adb_do shell cmd package query-activities --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
        | tr -d '\r' \
        | grep -E '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' \
        | grep -v "$PACKAGE_NAME/" \
        | grep -vi 'fallbackhome'
}
