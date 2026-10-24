#!/usr/bin/env bash
# LightHouse TV â€” install the launcher on an Android TV over adb and make it the Home screen.
#
#   ./tool/install.sh                        use the only connected TV
#   ./tool/install.sh --serial 192.168.1.9:5555
#   ./tool/install.sh --build                compile first, then install
#   ./tool/install.sh --no-home              install only, leave the current launcher alone
#
# Nothing is rooted, disabled or debloated by this script.

set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/lib.sh"

SERIAL=""
APK=""
DO_BUILD=0
DO_HOME=1
DO_GRANT=1

usage() {
    cat <<'EOF'
Usage: tool/install.sh [options]

  -s, --serial <device>   adb serial or ip:port of the TV
  -a, --apk <path>        install this APK instead of dist/lighthouse-tv-1.0.0.apk
      --build             build the APK first
      --no-home           do not make LightHouse TV the Home screen
      --no-grant          do not pre-grant the location permission
  -h, --help              this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s | --serial)
            SERIAL="${2:-}"
            shift 2
            ;;
        -a | --apk)
            APK="${2:-}"
            shift 2
            ;;
        --build)
            DO_BUILD=1
            shift
            ;;
        --no-home)
            DO_HOME=0
            shift
            ;;
        --no-grant)
            DO_GRANT=0
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "Unknown option: $1 (try --help)"
            ;;
    esac
done

step "Looking for adb"
require_adb
say "$ADB_BIN"

step "Finding the TV"
pick_serial
ensure_connected
ok "Connected to $SERIAL"

if [ "$DO_BUILD" -eq 1 ]; then
    step "Building"
    "$REPO_ROOT/tool/build.sh"
fi

APK_PATH="$(find_apk)" || die "No APK to install.

Expected one of:
  dist/$APK_NAME
  app/build/outputs/apk/release/app-release.apk

Build it with:  ./tool/build.sh"

step "Installing $(basename "$APK_PATH")"
say "$(du -h "$APK_PATH" | cut -f1) from $APK_PATH"

INSTALL_OUT="$(adb_do install -r "$APK_PATH" 2>&1 | tr -d '\r')" || true
printf '%s\n' "$INSTALL_OUT"

case "$INSTALL_OUT" in
    *Success*)
        ok "Installed."
        ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *INSTALL_FAILED_ALREADY_EXISTS*)
        die "A copy of LightHouse TV signed with a different key is already on the TV.

Remove it first (this only clears the launcher's own settings):
  $ADB_BIN -s $SERIAL uninstall $PACKAGE_NAME
then run this script again."
        ;;
    *)
        die "The install failed. The message above is from the TV."
        ;;
esac

if [ "$DO_GRANT" -eq 1 ]; then
    step "Granting the location permission"
    # Android 9+ hides the Wi-Fi network name from apps without it. The app also asks on first run.
    if adb_do shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1; then
        ok "Granted (the Wi-Fi line can show your network name)."
    else
        warn "Could not grant it over adb. LightHouse TV will ask on the TV instead."
    fi
fi

if [ "$DO_HOME" -eq 1 ]; then
    step "Making LightHouse TV the Home screen"

    # Two spellings of the same thing across Android versions; try both.
    adb_do shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 \
        || adb_do shell pm set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 \
        || true

    # set -e plus pipefail means a failing adb inside $( ) would abort silently; tolerate it and
    # report the unresolved Home instead.
    RESOLVED="$(resolve_home || true)"
    say "Home now resolves to: ${RESOLVED:-<nothing>}"

    if printf '%s' "$RESOLVED" | grep -q "$PACKAGE_NAME"; then
        ok "LightHouse TV is the Home screen."
        adb_do shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    else
        warn "The TV did not accept the change automatically."
        say ""
        say "On the TV, press HOME, choose LightHouse TV, then choose \"Always\"."
        say "If LightHouse TV is not offered at all, the stock launcher is pinned by the firmware:"
        say "  $ADB_BIN -s $SERIAL shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        say "shows the candidates, and tool/restore.sh reverses this at any time."
    fi
fi

step "Done"
say "Up/Down walks the apps, Right opens the rail, OK launches, MUTE silences the tick."
say "Undo everything with:  ./tool/restore.sh"
