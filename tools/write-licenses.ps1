# =============================================================================
#  Writes the SDK licence hashes AGP needs to trust this hand-built SDK.
#
#  This SDK was installed by direct download (tools/sdk-setup.ps1) rather than through sdkmanager,
#  so the `licenses/` directory that sdkmanager normally populates does not exist. Without it AGP
#  treats the installation as unlicensed, tries to reach Google's repository to resolve SDK packages,
#  and stalls with "Still waiting for package manifests to be fetched remotely".
#
#  The hash sdkmanager stores is the SHA-1 of the licence *text* as it ships inside the packages.
#  That text is present in the NOTICE.txt files of the installed packages, so we extract the exact
#  section and hash it, rather than hard-coding a value that could silently be wrong.
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\write-licenses.ps1
# =============================================================================
$ErrorActionPreference = 'Stop'

$SdkDir = 'H:\ddaa\APP\tools\android-sdk'
$LicenseDir = Join-Path $SdkDir 'licenses'
New-Item -ItemType Directory -Force -Path $LicenseDir | Out-Null

function Get-Sha1OfText([string]$text) {
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        # Normalise line endings: the licence text ships with LF, and hashing CRLF yields a
        # different digest, which would make AGP reject the licence.
        $normalised = $text -replace "`r`n", "`n"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalised)
        $hash = $sha1.ComputeHash($bytes)
        return ($hash | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha1.Dispose()
    }
}

<#
  Extracts one licence section out of a NOTICE.txt.

  The files concatenate many third-party licences separated by a long dashed rule. We locate the
  wanted heading, then take text up to the next dashed rule (or the next known heading).
#>
function Extract-LicenceSection([string]$file, [string]$heading) {
    $raw = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8) -replace "`r`n", "`n"
    $idx = $raw.IndexOf($heading, [System.StringComparison]::OrdinalIgnoreCase)
    if ($idx -lt 0) { return $null }
    $rest = $raw.Substring($idx)
    # The sections in these NOTICE files are delimited by a line of dashes.
    $ruleIdx = $rest.IndexOf("`n----------------------------------------", 1, [System.StringComparison]::Ordinal)
    if ($ruleIdx -gt 0) { $rest = $rest.Substring(0, $ruleIdx) }
    return $rest.TrimEnd() + "`n"
}

$noticeCandidates = @(
    (Join-Path $SdkDir 'platforms\android-35\data\NOTICE.txt'),
    (Join-Path $SdkDir 'build-tools\35.0.0\NOTICE.txt')
) | Where-Object { Test-Path $_ }

if (-not $noticeCandidates) { throw "No NOTICE.txt found under $SdkDir" }

# The two headings Google's sdkmanager hashes for the standard SDK licence.
$targets = @(
    @{ File = 'android-sdk-license';           Heading = 'Android Software Development Kit License Agreement' },
    @{ File = 'android-sdk-preview-license';   Heading = 'Android Software Development Kit License Agreement' }
)

$results = New-Object System.Collections.Generic.List[string]

foreach ($notice in $noticeCandidates) {
    foreach ($t in $targets) {
        $outFile = Join-Path $LicenseDir $t.File
        if (Test-Path $outFile) { continue }
        $section = Extract-LicenceSection -file $notice -heading $t.Heading
        if (-not $section) { continue }
        $hash = Get-Sha1OfText $section
        # sdkmanager stores one hash per line, wrapped in CRLF.
        Set-Content -Path $outFile -Value $hash -Encoding ASCII -NoNewline
        Add-Content -Path $outFile -Value "" -NoNewline
        $results.Add("$($t.File)  sha1=$hash  (from $(Split-Path $notice -Leaf))")
    }
    if ($results.Count -gt 0) { break }
}

Write-Host "=== licence files written ===" -ForegroundColor Cyan
Get-ChildItem $LicenseDir -File | ForEach-Object {
    $content = (Get-Content $_.FullName -Raw).Trim()
    Write-Host ("  {0,-32} {1}" -f $_.Name, $content)
}

if ((Get-ChildItem $LicenseDir -File | Measure-Object).Count -eq 0) {
    Write-Warning "No licence files were produced; AGP may still try to contact the SDK repository."
    exit 1
}
Write-Host "`nDone. AGP should now treat the SDK as licensed." -ForegroundColor Green
