# =============================================================================
#  Install the Android SDK components Gradle needs - by direct download.
#
#  Run after tools\bootstrap.ps1:
#    powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\sdk-setup.ps1
#
#  Why not sdkmanager: the cmdline-tools archive alone is ~155 MB, and at the bandwidth available
#  here that dominates the install. The components AGP actually needs are two zips (~122 MB total)
#  whose URLs and SHA-1 checksums come straight from Google's repository manifest, so we fetch them
#  directly and skip sdkmanager entirely. platform-tools (adb) is deliberately omitted: it is not
#  required for assembleDebug, and can be added later if the device needs it.
# =============================================================================
$ErrorActionPreference = 'Stop'

$Root = 'H:\ddaa\APP'
$Tools = Join-Path $Root 'tools'
$JdkDir = Join-Path $Tools 'jdk-17'
$SdkDir = Join-Path $Tools 'android-sdk'
$Cache = Join-Path $Tools 'cache'
$DownloadJs = Join-Path $Tools 'download.js'
$NodeExe = (Get-Command node -ErrorAction SilentlyContinue).Source

New-Item -ItemType Directory -Force -Path $Cache, $SdkDir | Out-Null
if (-not $NodeExe) { throw "Node.js is required (it provides the working TLS stack for downloads)." }

function Write-Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Write-Bad($m)  { Write-Host "  [!!] $m" -ForegroundColor Yellow }

# Component table. URLs and SHA-1 values are taken verbatim from
# https://dl.google.com/android/repository/repository2-3.xml (see tools/dump-repo.js).
$packages = @(
    @{
        Id      = 'platforms;android-35'
        Url     = 'https://dl.google.com/android/repository/platform-35_r02.zip'
        Sha1    = '0bb560a90a7a2cbd0dd8348224d518b638fe7949'
        Size    = 64273788
        Dest    = Join-Path $SdkDir 'platforms\android-35'
        # A file that must exist inside the payload, used to locate the real root after extraction.
        Marker  = 'android.jar'
    },
    @{
        Id      = 'build-tools;35.0.0'
        Url     = 'https://dl.google.com/android/repository/build-tools_r35-windows.zip'
        Sha1    = 'af059bb67cf7786f45ee0db85e2d24985df1b4b6'
        Size    = 59878107
        Dest    = Join-Path $SdkDir 'build-tools\35.0.0'
        Marker  = 'aapt2.exe'
    }
)

function Get-Component($pkg) {
    $name = ($pkg.Id -replace '[;:]', '-') + '.zip'
    $zip = Join-Path $Cache $name

    if (-not (Test-Path $zip) -or (Get-Item $zip).Length -ne $pkg.Size) {
        Write-Host "  fetching $name"
        & $NodeExe $DownloadJs $pkg.Url $zip --hash $pkg.Sha1 --size $pkg.Size
        if ($LASTEXITCODE -ne 0) { throw "download failed for $($pkg.Id)" }
    } else {
        Write-Ok "cached: $name"
    }
    return $zip
}

<#
  Extracts only the entries under a chosen prefix of the archive.

  Why not ZipFile::ExtractToDirectory: it throws
  "The given path's format is not supported" on these archives, because some entries contain
  characters that are legal in a zip but not in a Windows path. Extracting sequentially and
  reporting the offending entries lets the install continue with everything it actually needs.

  Returns a small report: extracted count, skipped count, and whether the marker was found.
#>
function Expand-ArchivePrefix($zip, $prefix, $dest) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
    $extracted = 0
    $skipped = 0
    $skippedSamples = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if (-not $name.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
            # Directory entries end with '/'.
            $relative = $name.Substring($prefix.Length).TrimStart('/')
            if ([string]::IsNullOrEmpty($relative)) { continue }

            $target = Join-Path $dest $relative
            try {
                # Reject paths Windows cannot represent rather than letting .NET throw.
                $full = [System.IO.Path]::GetFullPath($target)
                if ($full.IndexOfAny([System.IO.Path]::GetInvalidPathChars()) -ge 0) {
                    throw "invalid characters in path"
                }
                $parentDir = [System.IO.Path]::GetDirectoryName($full)
                if ($parentDir -and -not (Test-Path $parentDir)) {
                    New-Item -ItemType Directory -Force -Path $parentDir | Out-Null
                }
                if (-not [string]::IsNullOrEmpty($entry.Name)) {
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $full, $true)
                    $extracted++
                }
            } catch {
                $skipped++
                if ($skippedSamples.Count -lt 5) { $skippedSamples.Add("$name  ($($_.Exception.Message))") }
            }
        }
    } finally {
        $archive.Dispose()
    }
    return [pscustomobject]@{
        Extracted = $extracted
        Skipped   = $skipped
        Samples   = $skippedSamples
    }
}

<#
  Google's archives do not always unpack into the directory name we want (build-tools zips have
  historically extracted to a codename folder such as `android-15`). Rather than guess, we extract
  the archive prefix that contains the package's marker file, then move that directory into place.
#>
function Install-Component($pkg) {
    if ((Test-Path $pkg.Dest) -and (Get-ChildItem -Path $pkg.Dest -Filter $pkg.Marker -Recurse -ErrorAction SilentlyContinue)) {
        Write-Ok "$($pkg.Id) already installed"
        return
    }

    $zip = Get-Component $pkg
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    # Find which top-level directory in the archive holds the marker (e.g. "android-35/" or
    # "android-15/"), by inspecting entry names only - no extraction needed to decide.
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
    $prefix = $null
    try {
        $markerEntry = $archive.Entries |
            Where-Object { $_.FullName -match "/$([regex]::Escape($pkg.Marker))$" } |
            Select-Object -First 1
        if (-not $markerEntry) {
            throw "$($pkg.Id): '$($pkg.Marker)' not present in the archive"
        }
        # Keep everything up to and including the directory that contains the marker.
        $prefix = $markerEntry.FullName.Substring(0, $markerEntry.FullName.Length - $pkg.Marker.Length)
        Write-Host "  archive prefix: $prefix"
    } finally {
        $archive.Dispose()
    }

    $staging = Join-Path $Cache ("stage-" + ($pkg.Id -replace '[;:]', '-'))
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    New-Item -ItemType Directory -Force -Path $staging | Out-Null

    Write-Host "  expanding $([System.IO.Path]::GetFileName($zip)) ..."
    $report = Expand-ArchivePrefix -zip $zip -prefix $prefix -dest $staging
    Write-Host "  extracted $($report.Extracted) file(s), skipped $($report.Skipped)"
    foreach ($s in $report.Samples) { Write-Bad "skipped: $s" }

    $markerPath = Join-Path $staging $pkg.Marker
    if (-not (Test-Path $markerPath)) {
        throw "$($pkg.Id): marker '$($pkg.Marker)' missing after extraction"
    }

    $parent = Split-Path $pkg.Dest -Parent
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    if (Test-Path $pkg.Dest) { Remove-Item -Recurse -Force $pkg.Dest }
    # The archive prefix was stripped during extraction, so the staging directory *is* the payload
    # root (its first level holds android.jar / aapt2.exe directly).
    Move-Item -Force $staging $pkg.Dest
    Write-Ok "$($pkg.Id) -> $($pkg.Dest)"
}

foreach ($pkg in $packages) {
    Write-Step "Installing $($pkg.Id)"
    Install-Component $pkg
}

Write-Step 'Verification'
$expected = @(
    @{ Path = 'platforms\android-35\android.jar';              Desc = 'Android 35 platform' },
    @{ Path = 'platforms\android-35\android-stubs-src.jar';    Desc = 'platform stubs (optional)' },
    @{ Path = 'build-tools\35.0.0\aapt2.exe';                 Desc = 'aapt2' },
    @{ Path = 'build-tools\35.0.0\d8.bat';                    Desc = 'd8 dexer' },
    @{ Path = 'build-tools\35.0.0\lib\apksigner.jar';         Desc = 'apksigner' },
    @{ Path = 'build-tools\35.0.0\zipalign.exe';              Desc = 'zipalign' }
)

$allOk = $true
foreach ($e in $expected) {
    $p = Join-Path $SdkDir $e.Path
    if (Test-Path $p) { Write-Ok "$($e.Desc)  ($($e.Path))" }
    else {
        # Stubs jar is not needed by AGP; report it as informational only.
        if ($e.Path -like '*stubs-src*') { Write-Bad "$($e.Desc) missing (harmless)" }
        else { Write-Bad "MISSING: $($e.Path)"; $allOk = $false }
    }
}

# An empty build-tools directory would let the build start and then fail deep inside AGP, so make the
# failure explicit here instead.
$bt = Join-Path $SdkDir 'build-tools\35.0.0'
if (Test-Path $bt) {
    $count = (Get-ChildItem $bt -Force | Measure-Object).Count
    Write-Host "  build-tools entries: $count"
    if ($count -lt 5) { $allOk = $false; Write-Bad "build-tools looks incomplete" }
}

if ($allOk) {
    $marker = Join-Path $Root '.probe\sdk-ready.json'
    @{
        androidSdk = $SdkDir
        packages   = ($packages | ForEach-Object { $_.Id })
        readyAt    = (Get-Date).ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $marker
    Write-Host "`nSDK ready. Next: tools\build.ps1" -ForegroundColor Cyan
} else {
    Write-Host "`nSDK install incomplete." -ForegroundColor Red
    exit 1
}
