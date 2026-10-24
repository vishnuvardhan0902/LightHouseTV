# Shared helpers for the LightHouse TV install scripts. Dot-sourced, never run directly.
#
# Native commands are always invoked through Invoke-AdbRaw / Invoke-Adb with an ARGUMENT ARRAY.
# Two reasons, both learned the hard way on Windows:
#   1. adb takes flags such as -r and -d, which PowerShell would otherwise try to bind to the
#      script's own parameters and fail.
#   2. adb writes progress to stderr; with $ErrorActionPreference = 'Stop' PowerShell turns that
#      into a terminating error even though the command succeeded.

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:PackageName = 'tv.lighthouse.launcher'
$script:HomeActivity = 'tv.lighthouse.launcher/.MainActivity'
$script:ApkName = 'lighthouse-tv-1.0.0.apk'
$script:AdbExit = 0

function Die {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Red
    exit 1
}

function Say {
    param([string]$Message)
    Write-Host $Message
}

function Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor White
}

function Ok {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Warn {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Yellow
}

# adb is in a different place on every machine, so look for it rather than assume PATH.
function Find-Adb {
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }

    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        (Join-Path $env:ProgramFiles 'Android\Sdk\platform-tools\adb.exe'),
        (Join-Path $env:ProgramFiles 'Android\platform-tools\adb.exe'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk\platform-tools\adb.exe')
    )
    if ($env:ANDROID_HOME) { $candidates = @((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) + $candidates }
    if ($env:ANDROID_SDK_ROOT) { $candidates = @((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')) + $candidates }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

function Require-Adb {
    $adb = Find-Adb
    if (-not $adb) {
        Die @"
adb was not found.

Install the Android platform tools, then run this again:
  winget install Google.PlatformTools
Or set `$env:ADB to the full path of your adb.exe.
"@
    }
    $script:AdbBin = $adb
}

# Run adb without a device serial (connect, devices, start-server).
function Invoke-AdbRaw {
    param([string[]]$AdbArgs)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:AdbBin @AdbArgs 2>&1
        $script:AdbExit = $LASTEXITCODE
        return @($out | ForEach-Object { "$_" -replace "`r", '' })
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

# Run adb against the chosen device.
function Invoke-Adb {
    param([string[]]$AdbArgs)
    return (Invoke-AdbRaw (@('-s', $script:Serial) + $AdbArgs))
}

function Get-AdbDevices {
    $lines = Invoke-AdbRaw @('devices')
    @($lines | Select-Object -Skip 1 | ForEach-Object {
            $parts = ($_ -split '\s+')
            if ($parts.Count -ge 2 -and $parts[1] -eq 'device') { $parts[0] }
        } | Where-Object { $_ })
}

# A TV reached over the network has to be connected first; over USB it is already there.
function Confirm-Connected {
    if ($script:Serial -match ':') {
        Invoke-AdbRaw @('connect', $script:Serial) | Out-Null
    }

    for ($i = 0; $i -lt 15; $i++) {
        $state = (Invoke-Adb @('get-state')) -join ''
        if ($state.Trim() -eq 'device') { return }
        Start-Sleep -Seconds 1
    }

    $state = (Invoke-Adb @('get-state')) -join ''
    Die @"
Cannot talk to $($script:Serial) (adb says: $($state.Trim())).

  - Is the TV awake and on the same network?
  - Is ADB debugging still enabled in the TV developer options?
  - For a network TV, connect once by USB and run: adb tcpip 5555
"@
}

function Select-Serial {
    if ($script:Serial) { return }

    $list = @(Get-AdbDevices)
    if ($list.Count -eq 0) {
        Say 'No device is connected over adb yet.'
        Say ''
        Say 'Over the network (most TVs): find the TV IP in Settings > Network, then'
        Say "  $($script:AdbBin) connect <tv-ip>:5555"
        Say 'and run this script again.'
        Say ''
        Say 'Over USB: plug the TV in, accept the debugging prompt on screen, and retry.'
        Die 'No adb device found.'
    }

    if ($list.Count -gt 1) {
        Say 'More than one device is connected:'
        $list | ForEach-Object { Say "  $_" }
        Die 'Pick one with -Serial <device>.'
    }

    $script:Serial = $list[0]
    Ok "Using device: $($script:Serial)"
}

function Find-Apk {
    param([string]$Explicit)
    $candidates = @(
        $Explicit,
        (Join-Path $script:RepoRoot "dist\$script:ApkName"),
        (Join-Path $script:RepoRoot 'app\build\outputs\apk\release\app-release.apk')
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Resolve-Home {
    $lines = @(Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') |
            Where-Object { $_ -and $_.Trim() -ne '' })
    if ($lines.Count -eq 0) { return '' }
    return $lines[-1].Trim()
}

# Real launchers only: a ROM's FallbackHome is a stub that does nothing useful once you leave it.
function Get-HomeActivities {
    $lines = Invoke-Adb @('shell', 'cmd', 'package', 'query-activities', '--brief',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    @($lines | Where-Object {
            $_ -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' -and
            $_ -notmatch '^tv\.lighthouse\.launcher/' -and
            $_ -notmatch '(?i)fallbackhome'
        })
}
