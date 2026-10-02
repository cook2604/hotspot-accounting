# =============================================================================
#  Hotspot Accounting - Android build toolchain bootstrap
#  Installs a self-contained (no-admin, no-installer) toolchain under H:\ddaa\APP\tools
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\bootstrap.ps1
# =============================================================================
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root      = 'H:\ddaa\APP'
$Tools     = Join-Path $Root 'tools'
$Cache     = Join-Path $Tools 'cache'
$JdkDir    = Join-Path $Tools 'jdk-17'
$GradleDir = Join-Path $Tools 'gradle-8.14.5'
$SdkDir    = Join-Path $Tools 'android-sdk'

New-Item -ItemType Directory -Force -Path $Tools, $Cache, $SdkDir | Out-Null

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "  [!!] $msg" -ForegroundColor Yellow }

# `java -version` writes its banner to stderr and still exits 0. PowerShell turns native stderr into
# a NativeCommandError, which aborts the whole script under $ErrorActionPreference='Stop', so the
# preference is relaxed for the duration of this one call. Deliberately non-terminating.
function Show-JavaVersion {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & (Join-Path $JdkDir 'bin\java.exe') -version 2>&1 | Out-String
        Write-Host "     $($out.Trim())"
    } catch {
        Write-Host "     (java -version probe failed: $($_.Exception.Message))"
    } finally {
        $ErrorActionPreference = $prev
    }
}

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------
$NodeExe = (Get-Command node -ErrorAction SilentlyContinue).Source
$DownloadJs = Join-Path $Tools 'download.js'

if (-not $NodeExe) { throw "Node.js is required (it provides the working TLS stack for downloads)." }
if (-not (Test-Path $DownloadJs)) { throw "Missing $DownloadJs" }

<#
  Downloads via tools/download.js.

  curl.exe and BITS both fail on this host (schannel has no credentials in a sandboxed session),
  while Node's bundled OpenSSL works. The URL is tried as given, then through mirrors, because
  GitHub release downloads are frequently unreachable from mainland China.
#>
function Get-File($url, $dest, $expectedHash = '', $expectedSize = 0, $mirrors = @()) {
    $name = Split-Path $dest -Leaf
    # Trust an existing file only when its size matches what was expected. Without a known size there
    # is nothing to check against, so a non-empty file is accepted (the Java hash check still runs).
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)) {
        if ($expectedSize -eq 0 -or (Get-Item $dest).Length -eq $expectedSize) {
            Write-Ok "cached: $name"
            return
        }
        Write-Warn2 "cached $name has the wrong size, re-downloading"
    }

    $candidates = @($url) + $mirrors
    foreach ($candidate in $candidates) {
        Write-Host "  fetching $name"
        Write-Host "    from $candidate"
        # Flags are passed as separate argv entries; see download.js for why positional args are
        # unusable from PowerShell (empty-string arguments get dropped).
        $nodeArgs = @($DownloadJs, $candidate, $dest)
        if ($expectedHash) { $nodeArgs += @('--hash', $expectedHash) }
        if ($expectedSize -gt 0) { $nodeArgs += @('--size', "$expectedSize") }
        & $NodeExe @nodeArgs
        if ($LASTEXITCODE -eq 0 -and (Test-Path $dest) -and (Get-Item $dest).Length -gt 0) {
            $mb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
            Write-Ok "$name ($mb MB)"
            return
        }
        Write-Warn2 "attempt failed, trying next source"
        Remove-Item -Force -ErrorAction SilentlyContinue "$dest.part"
    }
    throw "all download sources failed for $name"
}

function Expand-Zip($zip, $dest) {
    if (Test-Path $dest) { Write-Ok "already expanded: $(Split-Path $dest -Leaf)"; return }
    $staging = "$dest.__staging"
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Write-Host "  expanding $(Split-Path $zip -Leaf) ..."
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $staging)
    # Collapse a single top-level directory if present.
    $entries = @(Get-ChildItem -Force $staging)
    if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
        Move-Item -Force $entries[0].FullName $dest
        Remove-Item -Recurse -Force $staging
    } else {
        Move-Item -Force $staging $dest
    }
    Write-Ok "expanded -> $dest"
}

# ---------------------------------------------------------------------------
# 1. JDK 17 (Temurin)
# ---------------------------------------------------------------------------
Write-Step '1/4  Temurin JDK 17'
if (Test-Path (Join-Path $JdkDir 'bin\java.exe')) {
    Write-Ok "already installed: $JdkDir"
} else {
    $jdkZip = Join-Path $Cache 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
    Get-File `
        -url 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' `
        -dest $jdkZip `
        -expectedHash 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0' `
        -expectedSize 190817615 `
        -mirrors @(
            'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip',
            'https://mirrors.huaweicloud.com/openjdk/17.0.1/openjdk-17.0.1_windows-x64_bin.zip'
        )
    Expand-Zip $jdkZip $JdkDir
}
$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$env:PATH"
Show-JavaVersion

# ---------------------------------------------------------------------------
# 2. Gradle 8.14.5
# ---------------------------------------------------------------------------
Write-Step '2/4  Gradle 8.14.5'
if (Test-Path (Join-Path $GradleDir 'bin\gradle.bat')) {
    Write-Ok "already installed: $GradleDir"
} else {
    $gradleZip = Join-Path $Cache 'gradle-8.14.5-bin.zip'
    Get-File `
        -url 'https://services.gradle.org/distributions/gradle-8.14.5-bin.zip' `
        -dest $gradleZip `
        -mirrors @('https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip')
    Expand-Zip $gradleZip $GradleDir
}
$env:PATH = "$GradleDir\bin;$env:PATH"

# ---------------------------------------------------------------------------
# 3. Android SDK base paths
# ---------------------------------------------------------------------------
# `cmdline-tools` (sdkmanager) is deliberately NOT installed. The archive is ~148 MB, and at the
# bandwidth available here that dominates the whole setup. tools\sdk-setup.ps1 fetches the two
# components AGP actually needs directly from Google's repository instead, with SHA-1 verification.
Write-Step '3/4  Android SDK paths'
New-Item -ItemType Directory -Force -Path (Join-Path $SdkDir 'platforms'), (Join-Path $SdkDir 'build-tools') | Out-Null
Write-Ok "SDK root: $SdkDir"
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

# ---------------------------------------------------------------------------
# 4. Persist environment (SDK packages are installed by sdk-setup.ps1)
# ---------------------------------------------------------------------------
Write-Step '4/4  Environment'
$envFile = Join-Path $Tools 'env.ps1'
@"
# Auto-generated by bootstrap.ps1 - dot-source this to get the toolchain on PATH.
`$env:JAVA_HOME = '$JdkDir'
`$env:ANDROID_HOME = '$SdkDir'
`$env:ANDROID_SDK_ROOT = '$SdkDir'
`$env:GRADLE_USER_HOME = '$Root\.gradle-home'
`$env:TEMP = '$Root\.tmp'
`$env:TMP = '$Root\.tmp'
`$env:PATH = "$JdkDir\bin;$GradleDir\bin;$SdkDir\platform-tools;$env:PATH"
"@ | Set-Content -Encoding UTF8 -Path $envFile
Write-Ok "wrote $envFile"

Write-Step 'Verification'
Write-Host "  JAVA_HOME   = $JdkDir"
Write-Host "  GRADLE      = $GradleDir"
Write-Host "  ANDROID_SDK = $SdkDir"
$allOk = $true
foreach ($p in @(
    "$JdkDir\bin\java.exe",
    "$GradleDir\bin\gradle.bat"
)) {
    if (Test-Path $p) { Write-Host "  [OK] $p" -ForegroundColor Green }
    else { Write-Host "  [MISSING] $p" -ForegroundColor Red; $allOk = $false }
}

Show-JavaVersion

if ($allOk) {
    $marker = Join-Path $Root '.probe\toolchain-ready.json'
    New-Item -ItemType Directory -Force -Path (Split-Path $marker) | Out-Null
    @{
        jdk        = $JdkDir
        gradle     = $GradleDir
        androidSdk = $SdkDir
        readyAt    = (Get-Date).ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $marker
    Write-Host "`nToolchain ready. Next: tools\sdk-setup.ps1" -ForegroundColor Cyan
} else {
    Write-Host "`nToolchain incomplete; see errors above." -ForegroundColor Red
    exit 1
}
