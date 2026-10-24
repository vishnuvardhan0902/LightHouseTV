# LightHouse TV - put the TV's original launcher back, and optionally remove LightHouse TV.
#
#   .\tool\restore.ps1
#   .\tool\restore.ps1 -To com.google.android.tvlauncher/.MainActivity
#   .\tool\restore.ps1 -Uninstall

[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$To = '',
    [switch]$Uninstall
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

$others = @(Get-HomeActivities)
if ($others.Count -gt 0) {
    Say 'Launchers on this TV:'
    $others | ForEach-Object { Say "  $_" }
}

if (-not $To) {
    # Prefer the launchers the big platforms ship; otherwise take the only candidate.
    foreach ($known in @(
            'com.google.android.tvlauncher/.MainActivity',
            'com.google.android.apps.tv.launcherx/.MainActivity',
            'com.gathin.tvhome/.MainActivity',
            'com.android.tvlauncher/.MainActivity')) {
        if ($others -contains $known) { $To = $known; break }
    }
}

if (-not $To) {
    if ($others.Count -eq 1) {
        $To = $others[0]
    }
    elseif (-not $Uninstall) {
        Die @"
No other launcher is registered on this TV, so Home cannot be handed back to one.

Most TVs are fine either way: removing LightHouse TV makes the TV fall back to the launcher it was
built with, which also re-appears in the Home chooser. Do that with:

  .\tool\restore.ps1 -Uninstall

If you know the launcher you want, name it directly:

  .\tool\restore.ps1 -To <package>/<activity>
"@
    }
}

if ($To) {
    Step "Returning Home to $To"
    Invoke-Adb @('shell', 'cmd', 'package', 'set-home-activity', $To) | Out-Null
    if ($script:AdbExit -ne 0) {
        Invoke-Adb @('shell', 'pm', 'set-home-activity', $To) | Out-Null
    }

    $resolved = Resolve-Home
    Say "Home now resolves to: $(if ($resolved) { $resolved } else { '<nothing>' })"

    if ($resolved -eq $To) {
        Ok 'Restored.'
    }
    else {
        Warn 'The TV kept a different Home. Press HOME on the TV and pick it manually.'
    }
}

if ($Uninstall) {
    Step 'Removing LightHouse TV'
    $out = (Invoke-Adb @('uninstall', $script:PackageName)) -join "`n"
    Write-Host $out
}

Step 'Done'
Say 'Reload the list of launchers at any time with:'
Say "  $($script:AdbBin) -s $($script:Serial) shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
