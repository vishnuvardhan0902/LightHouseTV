# LightHouse TV - install the launcher on an Android TV over adb and make it the Home screen.
#
#   .\tool\install.ps1
#   .\tool\install.ps1 -Serial 192.168.1.9:5555
#   .\tool\install.ps1 -Build
#   .\tool\install.ps1 -NoHome
#
# Nothing is rooted, disabled or debloated by this script.

[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$ApkPath = '',
    [switch]$Build,
    [switch]$NoHome,
    [switch]$NoGrant
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$script:Serial = $Serial

Step 'Looking for adb'
Require-Adb
Say $script:AdbBin

Step 'Finding the TV'
Select-Serial
Confirm-Connected
Ok "Connected to $($script:Serial)"

if ($Build) {
    Step 'Building'
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { Die 'Build failed.' }
}

$apk = Find-Apk -Explicit $ApkPath
if (-not $apk) {
    Die @"
No APK to install.

Expected one of:
  dist\$($script:ApkName)
  app\build\outputs\apk\release\app-release.apk

Build it with:  .\tool\build.ps1
"@
}

Step "Installing $(Split-Path -Leaf $apk)"
Say "$([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB from $apk"

$installOut = (Invoke-Adb @('install', '-r', $apk)) -join "`n"
Write-Host $installOut

if ($installOut -match 'Success') {
    Ok 'Installed.'
}
elseif ($installOut -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_ALREADY_EXISTS') {
    Die @"
A copy of LightHouse TV signed with a different key is already on the TV.

Remove it first (this only clears the launcher's own settings):
  $($script:AdbBin) -s $($script:Serial) uninstall $($script:PackageName)
then run this script again.
"@
}
else {
    Die 'The install failed. The message above is from the TV.'
}

if (-not $NoGrant) {
    Step 'Granting the location permission'
    # Android 9+ hides the Wi-Fi network name from apps without it. The app also asks on first run.
    Invoke-Adb @('shell', 'pm', 'grant', $script:PackageName, 'android.permission.ACCESS_FINE_LOCATION') | Out-Null
    if ($script:AdbExit -eq 0) {
        Ok 'Granted (the Wi-Fi line can show your network name).'
    }
    else {
        Warn 'Could not grant it over adb. LightHouse TV will ask on the TV instead.'
    }
}

if (-not $NoHome) {
    Step 'Making LightHouse TV the Home screen'

    # Two spellings of the same thing across Android versions; try both.
    Invoke-Adb @('shell', 'cmd', 'package', 'set-home-activity', $script:HomeActivity) | Out-Null
    if ($script:AdbExit -ne 0) {
        Invoke-Adb @('shell', 'pm', 'set-home-activity', $script:HomeActivity) | Out-Null
    }

    $resolved = Resolve-Home
    Say "Home now resolves to: $(if ($resolved) { $resolved } else { '<nothing>' })"

    if ($resolved -match [regex]::Escape($script:PackageName)) {
        Ok 'LightHouse TV is the Home screen.'
        Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    }
    else {
        Warn 'The TV did not accept the change automatically.'
        Say ''
        Say 'On the TV, press HOME, choose LightHouse TV, then choose "Always".'
        Say 'If LightHouse TV is not offered at all, the stock launcher is pinned by the firmware:'
        Say "  $($script:AdbBin) -s $($script:Serial) shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        Say 'shows the candidates, and tool\restore.ps1 reverses this at any time.'
    }
}

Step 'Done'
Say 'Up/Down walks the apps, Right opens the rail, OK launches, MUTE silences the tick.'
Say 'Undo everything with:  .\tool\restore.ps1'
