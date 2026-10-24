# LightHouse TV - build the APK. Needs JDK 17+ and the Android SDK platform 34.
#
#   .\tool\build.ps1
#
# The result is copied to dist\lighthouse-tv-1.0.0.apk so the install scripts have one predictable file.

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

Set-Location $script:RepoRoot

Step 'Checking Java'
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Die 'java was not found. Install JDK 17 (Temurin, or the one bundled with Android Studio) and set JAVA_HOME.'
}
$versionLine = (& java -version 2>&1 | Select-Object -First 1) -join ''
Say $versionLine
if ($versionLine -match '"(1\.)?(\d+)') {
    $major = [int]$Matches[2]
    if ($major -lt 17) {
        Die "This project needs JDK 17 or newer (found $major). Set JAVA_HOME to a JDK 17 and retry."
    }
}

Step 'Checking the Android SDK'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -and -not (Test-Path (Join-Path $script:RepoRoot 'local.properties'))) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:ProgramFiles 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'Android\Sdk')
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'platforms')) { $sdk = $c; break }
    }
    if ($sdk) {
        Set-Content -Path (Join-Path $script:RepoRoot 'local.properties') -Value "sdk.dir=$($sdk.Replace('\', '\\'))" -Encoding ASCII
        Say "Wrote local.properties -> $sdk"
    }
}
if (-not $sdk -and -not (Test-Path (Join-Path $script:RepoRoot 'local.properties'))) {
    Die @"
The Android SDK was not found.

Install it from Android Studio, or install just what is needed:
  sdkmanager "platforms;android-34" "build-tools;34.0.0"
then set ANDROID_HOME to the SDK directory.
"@
}

Step 'Building'
& (Join-Path $script:RepoRoot 'gradlew.bat') --no-daemon --console=plain assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease
if ($LASTEXITCODE -ne 0) { Die 'Build failed.' }

Step 'Collecting the APK'
$dist = Join-Path $script:RepoRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$from = Join-Path $script:RepoRoot 'app\build\outputs\apk\release\app-release.apk'
$to = Join-Path $dist $script:ApkName
Copy-Item -Force $from $to
Ok ("dist\$($script:ApkName) ({0} MB)" -f [math]::Round((Get-Item $to).Length / 1MB, 2))
Say ''
Say 'Install it on a TV with:  .\tool\install.ps1'
