#!/usr/bin/env bash
# LightHouse TV — block the bloatware and the tracking that ships on a stock Android TV.
#
# Nothing is uninstalled. Every package is switched off for the current user with
# `pm disable-user`, which is reversible with one command and survives reboots.
#
#   ./tool/debloat.sh                    show what would be disabled, change nothing
#   ./tool/debloat.sh --apply            disable it
#   ./tool/debloat.sh --apply --include-apps
#                                        also the preloaded vendor apps (music, video player, ...)
#   ./tool/debloat.sh --restore FILE     undo, using the file --apply wrote
#   ./tool/debloat.sh --list             just show every package on the TV
#
# The restore file is printed at the end of an --apply run. Keep it.

set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/lib.sh"

SERIAL=""
APPLY=0
INCLUDE_APPS=0
DISABLE_STOCK=0
LIST_ONLY=0
RESTORE=""
EXTRA=""

usage() {
    cat <<'EOF'
Usage: tool/debloat.sh [options]

  -s, --serial <device>        adb serial or ip:port of the TV
      --apply                  disable the packages (default is a dry run)
      --include-apps           also disable preloaded vendor apps
      --disable-stock-launcher also disable the vendor's own Home app (only if LightHouse TV is Home)
      --extra a.b,c.d          extra packages to disable
      --restore <file>         re-enable everything listed in a file written by --apply
      --list                   list every package installed on the TV and exit
  -h, --help                   this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s | --serial)
            SERIAL="${2:-}"
            shift 2
            ;;
        --apply)
            APPLY=1
            shift
            ;;
        --include-apps)
            INCLUDE_APPS=1
            shift
            ;;
        --disable-stock-launcher)
            DISABLE_STOCK=1
            shift
            ;;
        --extra)
            EXTRA="${2:-}"
            shift 2
            ;;
        --restore)
            RESTORE="${2:-}"
            shift 2
            ;;
        --list)
            LIST_ONLY=1
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

# Analytics, telemetry, "recommendations" and the backdrops that phone home. Safe on every TV.
TELEMETRY="
com.miui.tv.analytics
com.xiaomi.statistic
com.google.android.feedback
com.google.android.partnersetup
com.google.android.tvrecommendations
com.google.android.leanbacklauncher.recommendations
co.sensara.tv.mitv
co.sensara.tv.mitv.res
com.google.android.backdrop
"

# Vendor home rows, sponsored slots, cleaners and preload deployment. Safe, but vendor-specific.
ADS="
com.mitv.tvhome.atv
com.xm.webcontent
com.xiaomi.mitv.tvmanager
com.xiaomi.android.TV.audio
com.xiaomo.tv.milegal
com.xiaomi.android.tvsetup.partnercustomizer
android.autoinstalls.config.xiaomi.amelie
android.autoinstalls.config.xioami.dangal
com.google.android.play.games
"

# Preloaded apps. Harmless to keep, only disabled with --include-apps.
APPS="
com.xiaomi.mimusic2
com.mitv.videoplayer
com.google.android.videos
com.mitv.gallery
com.yablio.sendfilestotv
"

# The vendor's Home screen. Only touched with --disable-stock-launcher, and never if it is currently
# the resolved Home (that would leave the TV with no Home screen).
STOCK_LAUNCHERS="
com.google.android.tvlauncher
com.google.android.apps.tv.launcherx
"

# Losing any of these breaks the TV: Home, Settings, the permission controller, the installer,
# networking, the remote, or the tuner/HDMI stack.
PROTECTED="
tv.lighthouse.launcher
com.android.systemui
com.android.settings
com.android.tv.settings
com.android.shell
com.android.bluetooth
com.android.packageinstaller
com.android.permissioncontroller
com.google.android.packageinstaller
com.android.providers
com.android.inputmethod
com.android.wallpaper
com.android.vending
com.google.android.gms
com.google.android.gsf
com.google.android.katniss
com.google.android.googlequicksearchbox
com.google.android.tungsten.setupwraith
com.google.android.apps.mediashell
com.droidlogic
com.android.tv
org.droidtv
com.sony.dtv
"

is_protected() {
    local p="$1" k
    for k in $PROTECTED; do
        case "$p" in
            "$k" | "$k".*) return 0 ;;
        esac
    done
    return 1
}

is_stock_launcher() {
    local p="$1" k
    for k in $STOCK_LAUNCHERS; do
        [ "$p" = "$k" ] && return 0
    done
    return 1
}

step "Looking for adb"
require_adb
say "$ADB_BIN"

step "Finding the TV"
pick_serial
ensure_connected
ok "Connected to $SERIAL"

INSTALLED="$(adb_do shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' || true)"
DISABLED="$(adb_do shell pm list packages -d 2>/dev/null | tr -d '\r' | sed 's/^package://' || true)"
HOME_PKG="$(adb_do shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
    | tr -d '\r' | tail -n 1 | cut -d/ -f1 || true)"

if [ "$LIST_ONLY" -eq 1 ]; then
    step "Every package on the TV"
    printf '%s\n' "$INSTALLED" | sort
    exit 0
fi

if [ -n "$RESTORE" ]; then
    [ -f "$RESTORE" ] || die "No such restore file: $RESTORE"
    step "Re-enabling from $RESTORE"
    COUNT=0
    while IFS= read -r p; do
        case "$p" in
            '' | '#'*) continue ;;
        esac
        if adb_do shell pm enable "$p" >/dev/null 2>&1; then
            ok "enabled  $p"
            COUNT=$((COUNT + 1))
        else
            warn "could not enable $p"
        fi
    done <"$RESTORE"
    step "Done"
    say "$COUNT package(s) re-enabled. Reboot the TV to bring everything back."
    say "  $ADB_BIN -s $SERIAL reboot"
    exit 0
fi

WANT="$TELEMETRY $ADS"
[ "$INCLUDE_APPS" -eq 1 ] && WANT="$WANT $APPS"
[ -n "$EXTRA" ] && WANT="$WANT $(printf '%s' "$EXTRA" | tr ',' ' ')"
[ "$DISABLE_STOCK" -eq 1 ] && WANT="$WANT $STOCK_LAUNCHERS"

step "Planning"

TO_DISABLE=""
ALREADY=0
ABSENT=0
SKIPPED=0

for p in $WANT; do
    [ -n "$p" ] || continue

    if ! printf '%s\n' "$INSTALLED" | grep -qx "$p"; then
        ABSENT=$((ABSENT + 1))
        continue
    fi

    if [ "$p" = "$HOME_PKG" ]; then
        warn "keep     $p (it is the current Home screen)"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if is_stock_launcher "$p" && [ "$DISABLE_STOCK" -eq 0 ]; then
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if is_protected "$p" && ! is_stock_launcher "$p"; then
        warn "keep     $p (protected: the TV needs it)"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if printf '%s\n' "$DISABLED" | grep -qx "$p"; then
        ALREADY=$((ALREADY + 1))
        continue
    fi

    say "disable  $p"
    TO_DISABLE="$TO_DISABLE $p"
done

TO_COUNT=0
for p in $TO_DISABLE; do TO_COUNT=$((TO_COUNT + 1)); done

say ""
say "$TO_COUNT to disable, $ALREADY already disabled, $ABSENT not on this TV, $SKIPPED skipped"

if [ "$TO_COUNT" -eq 0 ]; then
    step "Nothing to do"
    exit 0
fi

if [ "$APPLY" -eq 0 ]; then
    step "Dry run"
    say "Nothing was changed. Run again with --apply to disable these:"
    say "  ./tool/debloat.sh --apply${INCLUDE_APPS:+ --include-apps}"
    exit 0
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
SAFE_SERIAL="$(printf '%s' "$SERIAL" | tr ':' '-')"
RESTORE_FILE="debloat-$SAFE_SERIAL-$STAMP.txt"

{
    echo "# LightHouse TV debloat — $SERIAL — $(date)"
    echo "# Re-enable everything with:"
    echo "#   ./tool/debloat.sh --restore $RESTORE_FILE"
    echo "# then reboot the TV."
} >"$RESTORE_FILE"

step "Disabling"
DONE=0
for p in $TO_DISABLE; do
    if adb_do shell pm disable-user --user 0 "$p" >/dev/null 2>&1; then
        ok "disabled $p"
        echo "$p" >>"$RESTORE_FILE"
        DONE=$((DONE + 1))
    else
        warn "failed   $p (the TV refused; probably a system app)"
    fi
done

step "Checking nothing important broke"
NEW_HOME="$(adb_do shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
    | tr -d '\r' | tail -n 1 || true)"
if printf '%s' "$NEW_HOME" | grep -q 'tv.lighthouse.launcher'; then
    ok "Home still resolves to $NEW_HOME"
else
    warn "Home now resolves to ${NEW_HOME:-<nothing>} — restore immediately:"
    warn "  ./tool/debloat.sh --restore $RESTORE_FILE"
fi

step "Done"
say "$DONE package(s) disabled. The TV keeps working; nothing was uninstalled."
say "Restore file: $RESTORE_FILE"
say ""
say "Reboot to see the result:"
say "  $ADB_BIN -s $SERIAL reboot"
say ""
say "If anything looks wrong, undo all of it with:"
say "  ./tool/debloat.sh --restore $RESTORE_FILE"
