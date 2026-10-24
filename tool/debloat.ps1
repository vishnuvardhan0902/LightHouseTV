# LightHouse TV - block the bloatware and the tracking that ships on a stock Android TV.
#
# Nothing is uninstalled. Every package is switched off for the current user with
# `pm disable-user`, which is reversible with one command and survives reboots.
#
#   .\tool\debloat.ps1                     show what would be disabled, change nothing
#   .\tool\debloat.ps1 -Apply              disable it
#   .\tool\debloat.ps1 -Apply -IncludeApps also the preloaded vendor apps (music, video player, ...)
#   .\tool\debloat.ps1 -Restore <file>     undo, using the file -Apply wrote
#   .\tool\debloat.ps1 -List               just show every package on the TV

[CmdletBinding()]
param(
    [string]$Serial = '',
    [switch]$Apply,
    [switch]$IncludeApps,
    [switch]$DisableStockLauncher,
    [string]$Extra = '',
    [string]$Restore = '',
    [switch]$List
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$script:Serial = $Serial

# Analytics, telemetry, "recommendations" and the backdrops that phone home. Safe on every TV.
$telemetry = @(
    'com.miui.tv.analytics'
    'com.xiaomi.statistic'
    'com.google.android.feedback'
    'com.google.android.partnersetup'
    'com.google.android.tvrecommendations'
    'com.google.android.leanbacklauncher.recommendations'
    'co.sensara.tv.mitv'
    'co.sensara.tv.mitv.res'
    'com.google.android.backdrop'
)

# Vendor home rows, sponsored slots, cleaners and preload deployment. Safe, but vendor-specific.
$ads = @(
    'com.mitv.tvhome.atv'
    'com.xm.webcontent'
    'com.xiaomi.mitv.tvmanager'
    'com.xiaomi.android.TV.audio'
    'com.xiaomo.tv.milegal'
    'com.xiaomi.android.tvsetup.partnercustomizer'
    'android.autoinstalls.config.xiaomi.amelie'
    'android.autoinstalls.config.xioami.dangal'
    'com.google.android.play.games'
)

# Preloaded apps. Harmless to keep, only disabled with -IncludeApps.
$apps = @(
    'com.xiaomi.mimusic2'
    'com.mitv.videoplayer'
    'com.google.android.videos'
    'com.mitv.gallery'
    'com.yablio.sendfilestotv'
)

# The vendor's Home screen. Only touched with -DisableStockLauncher, and never if it is currently
# the resolved Home (that would leave the TV with no Home screen).
$stockLaunchers = @(
    'com.google.android.tvlauncher'
    'com.google.android.apps.tv.launcherx'
)

# Losing any of these breaks the TV: Home, Settings, the permission controller, the installer,
# networking, the remote, or the tuner/HDMI stack.
$protected = @(
    'tv.lighthouse.launcher'
    'com.android.systemui'
    'com.android.settings'
    'com.android.tv.settings'
    'com.android.shell'
    'com.android.bluetooth'
    'com.android.packageinstaller'
    'com.android.permissioncontroller'
    'com.google.android.packageinstaller'
    'com.android.providers'
    'com.android.inputmethod'
    'com.android.wallpaper'
    'com.android.vending'
    'com.google.android.gms'
    'com.google.android.gsf'
    'com.google.android.katniss'
    'com.google.android.googlequicksearchbox'
    'com.google.android.tungsten.setupwraith'
    'com.google.android.apps.mediashell'
    'com.droidlogic'
    'com.android.tv'
    'org.droidtv'
    'com.sony.dtv'
)

function Test-Protected {
    param([string]$Package)
    foreach ($k in $protected) {
        if ($Package -eq $k -or $Package.StartsWith("$k.")) { return $true }
    }
    return $false
}

Step 'Looking for adb'
Require-Adb
Say $script:AdbBin

Step 'Finding the TV'
Select-Serial
Confirm-Connected
Ok "Connected to $($script:Serial)"

$installed = @(Invoke-Adb @('shell', 'pm', 'list', 'packages') |
        Where-Object { $_ } | ForEach-Object { $_ -replace '^package:', '' })
$disabled = @(Invoke-Adb @('shell', 'pm', 'list', 'packages', '-d') |
        Where-Object { $_ } | ForEach-Object { $_ -replace '^package:', '' })

$homeLine = @(Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') |
        Where-Object { $_ -and $_.Trim() -ne '' })
$homePkg = ''
if ($homeLine.Count -gt 0) {
    $homePkg = ($homeLine[-1].Trim() -split '/')[0]
}

if ($List) {
    Step 'Every package on the TV'
    $installed | Sort-Object | ForEach-Object { Say $_ }
    exit 0
}

if ($Restore) {
    if (-not (Test-Path $Restore)) { Die "No such restore file: $Restore" }
    Step "Re-enabling from $Restore"
    $count = 0
    Get-Content $Restore | ForEach-Object {
        $p = $_.Trim()
        if (-not $p -or $p.StartsWith('#')) { return }
        Invoke-Adb @('shell', 'pm', 'enable', $p) | Out-Null
        if ($script:AdbExit -eq 0) {
            Ok "enabled  $p"
            $count++
        }
        else {
            Warn "could not enable $p"
        }
    }
    Step 'Done'
    Say "$count package(s) re-enabled. Reboot the TV to bring everything back."
    Say "  $($script:AdbBin) -s $($script:Serial) reboot"
    exit 0
}

$wanted = @($telemetry + $ads)
if ($IncludeApps) { $wanted += $apps }
if ($DisableStockLauncher) { $wanted += $stockLaunchers }
if ($Extra) { $wanted += ($Extra -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }

Step 'Planning'

$toDisable = @()
$already = 0
$absent = 0
$skipped = 0

foreach ($p in $wanted) {
    if (-not $p) { continue }

    if ($installed -notcontains $p) {
        $absent++
        continue
    }

    if ($p -eq $homePkg) {
        Warn "keep     $p (it is the current Home screen)"
        $skipped++
        continue
    }

    if (($stockLaunchers -contains $p) -and (-not $DisableStockLauncher)) {
        $skipped++
        continue
    }

    if ((Test-Protected $p) -and ($stockLaunchers -notcontains $p)) {
        Warn "keep     $p (protected: the TV needs it)"
        $skipped++
        continue
    }

    if ($disabled -contains $p) {
        $already++
        continue
    }

    Say "disable  $p"
    $toDisable += $p
}

Say ''
Say "$($toDisable.Count) to disable, $already already disabled, $absent not on this TV, $skipped skipped"

if ($toDisable.Count -eq 0) {
    Step 'Nothing to do'
    exit 0
}

if (-not $Apply) {
    Step 'Dry run'
    Say 'Nothing was changed. Run again with -Apply to disable these:'
    Say "  .\tool\debloat.ps1 -Apply$(if ($IncludeApps) { ' -IncludeApps' })"
    exit 0
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$safeSerial = $script:Serial -replace ':', '-'
$restoreFile = Join-Path (Get-Location) "debloat-$safeSerial-$stamp.txt"

@(
    "# LightHouse TV debloat - $($script:Serial) - $(Get-Date)"
    '# Re-enable everything with:'
    "#   .\tool\debloat.ps1 -Restore $(Split-Path -Leaf $restoreFile)"
    '# then reboot the TV.'
) | Set-Content -Path $restoreFile -Encoding UTF8

Step 'Disabling'
$done = 0
foreach ($p in $toDisable) {
    Invoke-Adb @('shell', 'pm', 'disable-user', '--user', '0', $p) | Out-Null
    if ($script:AdbExit -eq 0) {
        Ok "disabled $p"
        Add-Content -Path $restoreFile -Value $p
        $done++
    }
    else {
        Warn "failed   $p (the TV refused; probably a system app)"
    }
}

Step 'Checking nothing important broke'
$newLine = @(Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') |
        Where-Object { $_ -and $_.Trim() -ne '' })
$newHome = ''
if ($newLine.Count -gt 0) { $newHome = $newLine[-1].Trim() }

if ($newHome -match 'tv\.lighthouse\.launcher') {
    Ok "Home still resolves to $newHome"
}
else {
    Warn "Home now resolves to $(if ($newHome) { $newHome } else { '<nothing>' }) - restore immediately:"
    Warn "  .\tool\debloat.ps1 -Restore $(Split-Path -Leaf $restoreFile)"
}

Step 'Done'
Say "$done package(s) disabled. The TV keeps working; nothing was uninstalled."
Say "Restore file: $restoreFile"
Say ''
Say 'Reboot to see the result:'
Say "  $($script:AdbBin) -s $($script:Serial) reboot"
Say ''
Say 'If anything looks wrong, undo all of it with:'
Say "  .\tool\debloat.ps1 -Restore $(Split-Path -Leaf $restoreFile)"
