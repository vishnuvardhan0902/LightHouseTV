#!/usr/bin/env bash
# LightHouse TV — put the TV's original launcher back, and optionally remove LightHouse TV.
#
#   ./tool/restore.sh                     hand Home back to the launcher the TV shipped with
#   ./tool/restore.sh --to com.google.android.tvlauncher/.MainActivity
#   ./tool/restore.sh --uninstall         ...and remove LightHouse TV from the TV

set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/lib.sh"

SERIAL=""
TO=""
DO_UNINSTALL=0

usage() {
    cat <<'EOF'
Usage: tool/restore.sh [options]

  -s, --serial <device>        adb serial or ip:port of the TV
      --to <pkg/.Activity>     restore this launcher instead of guessing
      --uninstall              also uninstall LightHouse TV afterwards
  -h, --help                   this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s | --serial)
            SERIAL="${2:-}"
            shift 2
            ;;
        --to)
            TO="${2:-}"
            shift 2
            ;;
        --uninstall)
            DO_UNINSTALL=1
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

# A TV without `cmd package query-activities` makes the pipeline fail; with set -e + pipefail that
# would abort the script before it can say anything useful, so tolerate it.
OTHERS="$(list_home_activities || true)"

if [ -n "$OTHERS" ]; then
    say "Launchers on this TV:"
    printf '%s\n' "$OTHERS" | sed 's/^/  /'
fi

if [ -z "$TO" ]; then
    # Prefer the launchers the big platforms ship; otherwise take the only candidate.
    for known in \
        "com.google.android.tvlauncher/.MainActivity" \
        "com.google.android.apps.tv.launcherx/.MainActivity" \
        "com.gathin.tvhome/.MainActivity" \
        "com.android.tvlauncher/.MainActivity"; do
        if printf '%s\n' "$OTHERS" | grep -qx "$known"; then
            TO="$known"
            break
        fi
    done
fi

if [ -z "$TO" ]; then
    COUNT="$(printf '%s' "$OTHERS" | grep -c . || true)"
    if [ "$COUNT" -eq 1 ]; then
        TO="$OTHERS"
    elif [ "$DO_UNINSTALL" -eq 0 ]; then
        die "No other launcher is registered on this TV, so Home cannot be handed back to one.

Most TVs are fine either way: removing LightHouse TV makes the TV fall back to the launcher it was
built with, which also re-appears in the Home chooser. Do that with:

  ./tool/restore.sh --uninstall

If you know the launcher you want, name it directly:

  ./tool/restore.sh --to <package>/<activity>"
    fi
fi

if [ -n "$TO" ]; then
    step "Returning Home to $TO"
    adb_do shell cmd package set-home-activity "$TO" >/dev/null 2>&1 \
        || adb_do shell pm set-home-activity "$TO" >/dev/null 2>&1 \
        || true

    RESOLVED="$(resolve_home || true)"
    say "Home now resolves to: ${RESOLVED:-<nothing>}"

    if printf '%s' "$RESOLVED" | grep -qx "$TO"; then
        ok "Restored."
    else
        warn "The TV kept a different Home. Press HOME on the TV and pick it manually."
    fi
fi

if [ "$DO_UNINSTALL" -eq 1 ]; then
    step "Removing LightHouse TV"
    adb_do uninstall "$PACKAGE_NAME" | tr -d '\r' || warn "Nothing to uninstall."
fi

step "Done"
say "Reload the list of launchers at any time with:"
say "  $ADB_BIN -s $SERIAL shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
