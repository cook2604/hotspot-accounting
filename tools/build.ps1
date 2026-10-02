# =============================================================================
#  Build the hotspot-accounting APK using the self-contained toolchain.
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\build.ps1
#  Optional: -Task assembleDebug | assembleRelease  (default: both)
# =============================================================================
param(
    [string]$Task = 'assembleDebug',
    # Prepend Aliyun mirrors when resolving dependencies. Useful when dl.google.com / Maven Central
    # are slow; Gradle still falls through to the upstream repositories if a mirror lacks an artefact.
    [switch]$UseCnMirrors
)

$ErrorActionPreference = 'Continue'

$Root = 'H:\ddaa\APP'
$ToolsDir = Join-Path $Root 'tools'
$JdkDir = Join-Path $Root 'tools\jdk-17'
$GradleDir = Join-Path $Root 'tools\gradle-8.14.5'
$SdkDir = Join-Path $Root 'tools\android-sdk'

# --- verify toolchain -------------------------------------------------------
$missing = @()
foreach ($p in @(
    (Join-Path $JdkDir 'bin\java.exe'),
    (Join-Path $GradleDir 'bin\gradle.bat'),
    (Join-Path $SdkDir 'platforms\android-35\android.jar'),
    (Join-Path $SdkDir 'build-tools\35.0.0\aapt2.exe')
)) { if (-not (Test-Path $p)) { $missing += $p } }
if ($missing.Count -gt 0) {
    Write-Host "Toolchain incomplete. Missing:" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" }
    Write-Host "`nRun tools\bootstrap.ps1 then tools\sdk-setup.ps1." -ForegroundColor Yellow
    exit 1
}

$env:JAVA_HOME = $JdkDir
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
$env:PATH = "$JdkDir\bin;$GradleDir\bin;$SdkDir\platform-tools;$env:PATH"

# Gradle's native services (native-platform.dll) cannot initialise against the default
# %USERPROFILE%\.gradle location in this environment: it fails with "Could not initialize native
# services / Failed to load native library 'native-platform.dll'". Pointing GRADLE_USER_HOME and the
# temp directory inside the workspace makes it work, and also keeps the build self-contained.
$env:GRADLE_USER_HOME = Join-Path $Root '.gradle-home'
$env:TEMP = Join-Path $Root '.tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $env:TEMP | Out-Null

# Android's own user home (%USERPROFILE%\.android) holds the debug keystore and the SDK download
# cache. It is not writable in this environment, which fails the build at :validateSigningDebug with
# "AccessDeniedException: ...debug.keystore.lock". Relocating it inside the workspace fixes signing
# and stops the SDK repository cache from throwing NoSuchFileException on every configure.
$env:ANDROID_USER_HOME = Join-Path $Root '.android-home'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

# Make sure the SDK is licensed before Gradle runs. Without `licenses/`, AGP considers the SDK
# unlicensed and tries to resolve packages against Google's repository, which stalls the build with
# "Still waiting for package manifests to be fetched remotely".
$licenseScript = Join-Path $ToolsDir 'write-licenses.ps1'
if (Test-Path $licenseScript) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $licenseScript | Out-Null
}

# Inject build settings that must NOT live in the committed gradle.properties (they are tied to this
# self-contained install). `android.builder.sdkDownload=false` stops AGP from reaching out to
# dl.google.com to resolve SDK packages, which it otherwise retries until the build stalls.
$gradleProps = Join-Path $Root '.probe\gradle-overrides.properties'
@"
android.builder.sdkDownload=false
"@ | Set-Content -Encoding ASCII -Path $gradleProps

# Point Gradle at the local SDK without mutating a tracked file.
"sdk.dir=$($SdkDir -replace '\\','\\')" | Set-Content -Encoding ASCII -Path (Join-Path $Root 'local.properties')

Write-Host "=== Building $Task ===" -ForegroundColor Cyan
Write-Host "JAVA_HOME  = $JdkDir"
Write-Host "ANDROID_SDK= $SdkDir"
Write-Host ""

$logFile = Join-Path $Root ".probe\gradle-$Task.log"
$gradleArgs = @(
    '--no-daemon'
    '--console=plain'
    '-Dorg.gradle.jvmargs=-Xmx3072m'
    # Keep AGP from contacting the SDK repository to resolve packages. With the SDK already installed
    # this is unnecessary, and the attempt retries against an unreachable host until the build stalls.
    '-Pandroid.builder.sdkDownload=false'
)
if ($UseCnMirrors) {
    $gradleArgs += '-PuseCnMirrors'
    Write-Host "Using Aliyun mirrors for dependency resolution" -ForegroundColor Yellow
}
$gradleArgs += $Task

Push-Location $Root
try {
    & (Join-Path $GradleDir 'bin\gradle.bat') @gradleArgs 2>&1 | Tee-Object -FilePath $logFile
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

Write-Host ""
if ($code -eq 0) {
    Write-Host "BUILD SUCCEEDED" -ForegroundColor Green
    Get-ChildItem -Recurse -Path (Join-Path $Root 'app\build\outputs\apk') -Filter *.apk -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host ("  APK: {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) }
} else {
    Write-Host "BUILD FAILED (exit $code) - see $logFile" -ForegroundColor Red
}
exit $code
