# Install from a laptop, step by step

This is the whole process, from a stock TV to LightHouse TV with the bloatware switched off. It
takes about ten minutes and one laptop. Nothing here needs root or an unlocked bootloader, and every
step says how to undo it.

If you have an agent with shell access, point it at [`AGENTS.md`](../AGENTS.md) instead — same
steps, written for a machine.

---

## What you need

- An Android TV or Google TV device, **Android 8.0 or newer**.
- A laptop on the **same network** as the TV (or a USB cable).
- `adb` — the Android platform tools. That is the only thing to install.

```bash
# macOS
brew install --cask android-platform-tools

# Windows
winget install Google.PlatformTools

# Debian / Ubuntu
sudo apt install android-tools-adb
```

Check it:

```bash
adb version
```

---

## Step 1 — Unlock developer options on the TV

On the TV, not the laptop:

1. **Settings → Device Preferences → About**
2. Scroll to **Build** and press **OK** on it **seven times**. The TV says *"You are now a
   developer"*.
3. Go back to **Settings → Device Preferences → Developer options**.
4. Turn on **USB debugging**. If your TV offers **Network debugging** / **ADB over network**, turn
   that on too — it saves the cable.

Where this menu lives depends on the brand:

| TV | Path to About |
| --- | --- |
| Xiaomi Mi TV / Mi Box | Settings → Device Preferences → About |
| Google TV / Chromecast / Sony / TCL / Hisense | Settings → System → About |
| Philips / Sharp / Grundig (Android) | Settings → Device Preferences → About |
| Nvidia Shield | Settings → Device Preferences → About |
| AOSP boxes | Settings → About, or Settings → System → About |

If you cannot find it: some boxes open developer options by pressing **Menu** five times on the
Settings screen. Search the web for your exact model plus "enable adb".

---

## Step 2 — Connect the laptop to the TV

### Over the network (recommended, no cable)

Find the TV's IP address: **Settings → Network → (your network) → IP address**. Then:

```bash
adb connect 192.168.1.9:5555
```

The TV shows an **"Allow debugging?"** prompt with an RSA fingerprint. Accept it with the remote,
tick *Always allow from this computer*.

Prove you are really talking to the TV, not just to an open port:

```bash
adb -s 192.168.1.9:5555 shell getprop ro.product.model
adb -s 192.168.1.9:5555 shell getprop ro.build.version.release
```

### Over USB

Plug the laptop into the TV's USB port, accept the same prompt, and confirm:

```bash
adb devices
```

You want a line ending in `device`. `unauthorized` means the prompt is still waiting on screen.

### Stop the TV falling asleep mid-install

```bash
adb -s 192.168.1.9:5555 shell svc power stayon true
```

Undo it later with `svc power stayon false`.

---

## Step 3 — Install the launcher

From a clone of this repository:

```bash
git clone https://github.com/vishnuvardhan0902/LightHouseTV.git
cd LightHouseTV

# macOS / Linux
chmod +x tool/*.sh
./tool/install.sh --serial 192.168.1.9:5555

# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File .\tool\install.ps1 -Serial 192.168.1.9:5555
```

The script finds adb, installs `dist/lighthouse-tv-1.0.0.apk`, grants the optional location
permission (needed for the Wi-Fi name) and makes LightHouse TV Home. Drop `--serial` when only one
device is connected.

By hand, it is three commands:

```bash
adb -s 192.168.1.9:5555 install -r dist/lighthouse-tv-1.0.0.apk
adb -s 192.168.1.9:5555 shell pm grant tv.lighthouse.launcher android.permission.ACCESS_FINE_LOCATION
adb -s 192.168.1.9:5555 shell cmd package set-home-activity tv.lighthouse.launcher/.MainActivity
```

Press **HOME** on the remote. The lighthouse should appear. If the TV offers a chooser, tick
*Always* and pick LightHouse TV.

---

## Step 4 — Block the bloatware and the tracking

Stock Android TVs ship with analytics agents, "recommendation" services that upload what you watch,
sponsored rows on the home screen, a vendor cleaner, and a pile of preloaded apps. None of it is
needed for the TV to work.

LightHouse TV does **not** remove anything. It uses `pm disable-user`, which is per-user, reversible,
and survives reboots. The enabled/disabled state is the same mechanism the TV's own app settings use.

**First, look before you touch anything.** This is a dry run — it prints what it *would* disable:

```bash
./tool/debloat.sh --serial 192.168.1.9:5555
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tool\debloat.ps1 -Serial 192.168.1.9:5555
```

You get a list like:

```
disable  com.miui.tv.analytics
disable  com.google.android.tvrecommendations
disable  com.mitv.tvhome.atv
...
14 to disable, 0 already disabled, 9 not on this TV, 0 skipped
```

**Then apply it:**

```bash
./tool/debloat.sh --apply                    # telemetry + ad/recommendation services
./tool/debloat.sh --apply --include-apps     # ...and the preloaded apps (music, video player, ...)
```

The script writes `debloat-<tv>-<date>.txt` in the current folder and prints the exact command to
undo it. **Keep that file.**

What it refuses to touch, by design: the current Home screen, Settings, System UI, the permission
controller, the package installer, Google Play services, the setup/Wi-Fi owner, the input method, and
the Amlogic/Droidlogic HDMI and tuner stack. If the Home screen stops resolving to LightHouse TV
after the run, the script tells you to restore immediately.

Afterwards, reboot and check that Wi-Fi, sound, HDMI inputs and the remote still work:

```bash
adb -s 192.168.1.9:5555 reboot
```

### Undo the debloat only

```bash
./tool/debloat.sh --restore debloat-192.168.1.9-20261024-193000.txt
```

### Undo everything

```bash
./tool/restore.sh --uninstall      # Home back to the stock launcher, LightHouse TV removed
```

Then re-enable the packages with the restore file, and reboot.

---

## Step 5 — Check it worked

```bash
# Home resolves to the launcher
adb -s 192.168.1.9:5555 shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME
# -> tv.lighthouse.launcher/.MainActivity

# It is running
adb -s 192.168.1.9:5555 shell pidof tv.lighthouse.launcher

# See the screen
adb -s 192.168.1.9:5555 exec-out screencap -p > home.png
```

A good frame has the night seascape, the arc of app tiles, the beam falling on the centred app, the
app's name in large type, the clock top right and three icons down the right edge.

Working the remote over adb:

```bash
adb -s 192.168.1.9:5555 shell input keyevent KEYCODE_DPAD_DOWN   # next app
adb -s 192.168.1.9:5555 shell input keyevent KEYCODE_DPAD_RIGHT  # into the rail
adb -s 192.168.1.9:5555 shell input keyevent KEYCODE_DPAD_CENTER # launch
```

---

## If something goes wrong

| Symptom | What it means | Fix |
| --- | --- | --- |
| `adb devices` shows nothing | Debugging off, TV asleep, or different network | Recheck Step 1, wake the TV, re-run `adb connect` |
| `unauthorized` | The RSA prompt was never accepted | Accept it on the TV, then `adb kill-server` and reconnect |
| `connection refused` on port 5555 | Network debugging is off | Turn it on in developer options, or plug in USB and run `adb tcpip 5555` |
| Install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build signed with another key is installed | `adb uninstall tv.lighthouse.launcher`, then install again |
| Home stays on the stock launcher | The firmware pins its own launcher | Press HOME, choose LightHouse TV, choose *Always* |
| Wi-Fi panel says "Connected" without a name | Location permission missing (Android 9+) | `adb shell pm grant tv.lighthouse.launcher android.permission.ACCESS_FINE_LOCATION` |
| A debloated TV misbehaves after reboot | Something you disabled was in use | Run the `--restore` command from the file the script wrote |
| Screen edges are cut off | TV overscan, not the app | Set the TV picture size to *Just Scan* / *Screen Fit* / 16:9 with no overscan |

---

## The packages it disables, and why

Three groups, all optional, all reversible:

**Telemetry and recommendations** — these report viewing habits or fetch "recommended" content:

```
com.miui.tv.analytics                            Xiaomi usage analytics
com.xiaomi.statistic                             Xiaomi statistics agent
com.google.android.feedback                      Google feedback agent
com.google.android.partnersetup                  preloads partner apps from Play
com.google.android.tvrecommendations             Google's recommendation service
com.google.android.leanbacklauncher.recommendations
co.sensara.tv.mitv(.res)                         Sensara recommendations (Mi TV)
com.google.android.backdrop                      Google ambient screensaver
```

**Ads, sponsored rows and vendor housekeeping** — the stuff that puts ads on the stock home screen:

```
com.mitv.tvhome.atv                              PatchWall rows, including the sponsored slot
com.xm.webcontent                                web content provider for those rows
com.xiaomi.mitv.tvmanager                        vendor "cleaner"
com.xiaomi.android.TV.audio                      audio debug tool
com.xiaomo.tv.milegal                            legal notices app
com.xiaomi.android.tvsetup.partnercustomizer      partner app preload customiser
android.autoinstalls.config.*                    Play auto-install of partner apps
com.google.android.play.games                    Play Games (unused on a TV)
```

**Preloaded apps** — only with `--include-apps`:

```
com.xiaomi.mimusic2      com.mitv.videoplayer     com.google.android.videos
com.mitv.gallery         com.yablio.sendfilestotv
```

Different brands ship different package names, so a list tuned for Xiaomi will skip most entries on
a Sony or a TCL. Use `--list` to see what your TV actually has, then add packages explicitly:

```bash
./tool/debloat.sh --list | grep -i -E "ads|telemetry|analytics|recommend|promo"
./tool/debloat.sh --apply --extra com.example.promo,com.example.analytics
```

The dry run always runs first, so you can see exactly what would change before it changes.
