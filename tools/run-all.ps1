# =============================================================================
#  One-shot setup + build.  This is the command to run on your own machine.
#
#    powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\run-all.ps1
#
#  If dependency downloads are slow, add -UseCnMirrors to resolve through Aliyun instead:
#
#    powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\run-all.ps1 -UseCnMirrors
#
#  It performs, in order:
#    1. JDK 17 + Gradle 8.14.5           (~314 MB, skipped if already present)
#    2. Android SDK: platform 35 + build-tools 35   (~122 MB, SHA-1 verified)
#    3. assembleDebug                     (downloads Gradle deps, then compiles)
#
#  Everything is self-contained under H:\ddaa\APP\tools - no admin rights, no system changes.
#  Re-running is safe: completed steps are detected and skipped, and downloads resume.
# =============================================================================
param(
    [switch]$UseCnMirrors
)

$ErrorActionPreference = 'Continue'
$Root = 'H:\ddaa\APP'
$Tools = Join-Path $Root 'tools'

function Step($n, $title) {
    Write-Host ""
    Write-Host ("=" * 68) -ForegroundColor DarkCyan
    Write-Host " STEP $n  $title" -ForegroundColor Cyan
    Write-Host ("=" * 68) -ForegroundColor DarkCyan
}

$sw = [Diagnostics.Stopwatch]::StartNew()

Step 1 'Toolchain: JDK 17 + Gradle 8.14.5'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools 'bootstrap.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nStep 1 failed. Fix the error above and re-run this script; it resumes." -ForegroundColor Red
    exit 1
}

Step 2 'Android SDK: platform 35 + build-tools 35'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools 'sdk-setup.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nStep 2 failed. Re-run this script; the partial download will resume." -ForegroundColor Red
    exit 1
}

Step 3 'Compiling the APK'
$buildArgs = @('-Task', 'assembleDebug')
if ($UseCnMirrors) { $buildArgs += '-UseCnMirrors' }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools 'build.ps1') @buildArgs
$buildExit = $LASTEXITCODE

$sw.Stop()
Write-Host ""
Write-Host ("=" * 68) -ForegroundColor DarkCyan
if ($buildExit -eq 0) {
    Write-Host (" BUILD OK   total elapsed {0:N1} min" -f $sw.Elapsed.TotalMinutes) -ForegroundColor Green
    Write-Host ("=" * 68) -ForegroundColor DarkCyan
    Get-ChildItem -Recurse -Path (Join-Path $Root 'app\build\outputs\apk') -Filter *.apk -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host ("  APK: {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) -ForegroundColor Green }
    Write-Host ""
    Write-Host "  Install with:  adb install -r <the APK path above>"
    Write-Host "  Or copy it to the phone and open it."
} else {
    Write-Host (" BUILD FAILED (exit {0})   elapsed {1:N1} min" -f $buildExit, $sw.Elapsed.TotalMinutes) -ForegroundColor Red
    Write-Host ("=" * 68) -ForegroundColor DarkCyan
    Write-Host "  Full log: H:\ddaa\APP\.probe\gradle-assembleDebug.log"
    Write-Host "  Send me the last ~100 lines and I will fix the cause."
}
exit $buildExit
