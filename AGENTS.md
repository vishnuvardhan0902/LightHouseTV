# AGENTS.md

Instructions for an AI coding agent asked to install, verify, update or remove **LightHouse TV**, a
replacement Home screen for Android TV. Follow the steps literally; each one ends in a check you can
evaluate. Do not improvise around a failed check — use the failure table.

## Scope

- Target: an Android TV / Google TV device running **Android 8.0 (API 26) or newer**.
- LightHouse TV is a normal sideloaded APK (`tv.lighthouse.launcher`). It needs **no root**, no unlocked bootloader,
  no system modifications, and it disables nothing.
- The install replaces the Home screen by asking the system to prefer LightHouse TV for the `HOME` intent.
  It is fully reversible (`tool/restore.*`).

If the device is a Roku, LG webOS, Samsung Tizen, or an Amazon Fire TV with a locked bootloader, stop
and tell the user LightHouse TV cannot be installed there. Fire TV OS blocks third-party Home replacement
even though it runs Android underneath.

## Prerequisites you may have to arrange

1. **ADB debugging on the TV.** Settings → Device Preferences → About → tap *Build* 7 times, then
   Settings → Device Preferences → Developer options → **USB debugging** on. On some TVs the entry
   is called *ADB debugging* or *Network debugging*.
2. **The TV and this machine on the same network**, or a USB cable. Wireless is far more common for
   TVs and needs one of:
   - the TV's own *Network debugging* switch, or
   - `adb tcpip 5555` once over USB, after which `adb connect <ip>:5555` works until reboot.
3. **adb** on this machine.

## Step 1 — Confirm adb

```bash
adb version
```

If missing: `brew install --cask android-platform-tools` (macOS),
`winget install Google.PlatformTools` (Windows), `sudo apt install android-tools-adb` (Debian).
The scripts also search `$ANDROID_HOME/platform-tools`, `~/Library/Android/sdk`,
`~/Android/Sdk`, `%LOCALAPPDATA%\Android\Sdk`, and accept an `ADB` environment variable.

## Step 2 — Find the TV

```bash
adb devices
```

A line ending in `device` is ready. `unauthorized` means the TV is showing a debugging prompt that
must be accepted with the remote; `offline` means reconnect.

If the TV is not listed, ask the user for its IP address (Settings → Network) and connect:

```bash
adb connect 192.168.1.9:5555
```

Then verify the connection *reports the TV's build*, not just a TCP success:

```bash
adb -s 192.168.1.9:5555 shell getprop ro.build.version.release
adb -s 192.168.1.9:5555 shell getprop ro.product.model
```

## Step 3 — Install

Use the bundled scripts; they detect adb, pick the only device, install, grant the optional
permission and take Home.

```bash
# macOS / Linux
chmod +x tool/*.sh
./tool/install.sh --serial 192.168.1.9:5555
```

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File .\tool\install.ps1 -Serial 192.168.1.9:5555
```

Omit `--serial` / `-Serial` when exactly one device is attached. Add `--build` / `-Build` to compile
from source first (needs JDK 17 and Android SDK platform 34).

Do **not** run the scripts through `sh`; they are `bash`. On Windows prefer invoking the `.ps1` with
`-ExecutionPolicy Bypass` instead of changing the machine's policy.

## Step 4 — Verify (all three must pass)

```bash
adb -s <serial> shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME
```

1. **Home resolves to LightHouse TV.** The last line must be `tv.lighthouse.launcher/.MainActivity`.
2. **It is running.** `adb -s <serial> shell pidof tv.lighthouse.launcher` prints a pid.
3. **It drew a frame.** `adb -s <serial> shell screencap -p /sdcard/m.png` then
   `adb -s <serial> pull /sdcard/m.png` — open the image. You should see a night seascape, a
   vertical arc of app tiles on the left third, one large tile in the centre with a beam of light
   from the lighthouse crossing it, the app's name in large type on the right, a clock top-right and
   three icons down the right edge. A black screen, the stock launcher, or a crash dialog is a
   failure.

If Home does not resolve to LightHouse TV, the firmware is pinning its own launcher. That is not fatal:
the user can press HOME and pick LightHouse TV → *Always*. Report it, do not attempt to disable system
packages unless the user explicitly asks.

## Optional — remote smoke test

```bash
adb -s <serial> shell input keyevent KEYCODE_DPAD_DOWN
adb -s <serial> shell input keyevent KEYCODE_DPAD_RIGHT
```

Down should move the arc by one app and the beam should swing once onto the new centre tile.
Right should open the side rail with Wi-Fi above, Settings centred and Inputs below.

## Rebuilding from source

```bash
./tool/build.sh          # macOS / Linux
.\tool\build.ps1         # Windows
```

Requires JDK 17+ and Android SDK platform 34. The build writes `dist/lighthouse-tv-1.0.0.apk`. The APK is
signed with a local debug key by default so upgrades install over each other; set `LIGHTHOUSE_KEYSTORE`
to sign with your own.

## Rollback

```bash
./tool/restore.sh --uninstall        # or .\tool\restore.ps1 -Uninstall
```

Hands Home back to the launcher the TV shipped with (or the system fallback) and removes LightHouse TV.
Without `--uninstall` it only returns Home.

## Failure table

| Symptom | Cause | Fix |
| --- | --- | --- |
| `adb: no devices/emulators found` | TV asleep, debugging off, or wrong network | Wake the TV, re-check developer options, `adb connect <ip>:5555` |
| `device unauthorized` | Debugging prompt not accepted | Accept the RSA prompt on the TV with the remote, then `adb kill-server` and retry |
| `device offline` | Stale connection after sleep | `adb disconnect <ip>:5555` then `adb connect <ip>:5555` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build signed with another key is installed | `adb uninstall tv.lighthouse.launcher`, then install again |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | TV storage full | Free space, then retry |
| Install succeeds but Home stays on the stock launcher | Firmware pins its launcher, or another HOME app is preferred | Press HOME on the TV, choose LightHouse TV, choose *Always* |
| Wi-Fi line says "Connected" with no network name | Location permission not granted (Android 9+ hides SSIDs) | `adb shell pm grant tv.lighthouse.launcher android.permission.ACCESS_FINE_LOCATION`, or accept the in-app prompt |
| Black screen with no UI | Renderer failed on this GPU | Collect `adb logcat -d -b crash`, report it; `adb shell am force-stop tv.lighthouse.launcher` clears it |
| App icons missing (only the focused one) | Package visibility on Android 11+ | Known limitation on API 30+ TV firmware; report it with `getprop ro.build.version.sdk` |

## Guardrails

- Never root the TV, unlock a bootloader, flash firmware, or disable system packages as part of an
  install. None of it is needed and all of it is hard to undo.
- Do not run `pm uninstall` on anything except `tv.lighthouse.launcher`.
- Do not change the user's network, accounts, or developer settings beyond enabling debugging if
  the user asked you to.
- Report what you actually verified, including the commands and their output. Do not claim the
  launcher is working from a successful `adb install` alone: check Home resolution and look at a
  screenshot.
