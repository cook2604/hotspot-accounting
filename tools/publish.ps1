# =============================================================================
#  Publish this repository to GitHub.
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\publish.ps1 `
#            -Username cook2604 -Token <PAT>
#
#  The token is passed as an argument and is intentionally NEVER written to disk, never stored in
#  .git/config, and never echoed. It is supplied to git through GIT_ASKPASS so it cannot leak into the
#  command line of a child process (where another user on the machine could read it via the process
#  list) or into shell history.
#
#  Requires the repository to already exist on GitHub. Creating it needs an API call, which is done
#  by the caller; this script only pushes.
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [string]$Repo = 'hotspot-accounting',
    [string]$Token = '',
    [string]$Branch = 'main'
)

$ErrorActionPreference = 'Continue'
$Root = 'H:\ddaa\APP'
$Git = Join-Path $Root 'tools\git\cmd\git.exe'
$env:GIT_CONFIG_NOSYSTEM = '1'

function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }

if (-not (Test-Path $Git)) { Fail "git not found at $Git" }
if (-not (Test-Path (Join-Path $Root '.git'))) { Fail "no git repository at $Root" }

# --- 1. make sure there is something to push ---------------------------------
$pending = & $Git -C $Root status --porcelain
if ($pending) {
    Write-Host "  Uncommitted changes present; committing them first." -ForegroundColor Yellow
    & $Git -C $Root add -A
    & $Git -C $Root -c user.name="$Username" `
        -c user.email="$Username@users.noreply.github.com" `
        commit -m "更新：同步本地改动" | Out-Null
    Ok "committed pending changes"
} else {
    Ok "working tree clean"
}

$head = (& $Git -C $Root rev-parse HEAD).Trim()
Ok "HEAD = $($head.Substring(0, 8))"

# --- 2. point origin at the repository ---------------------------------------
$remoteUrl = "https://github.com/$Username/$Repo.git"
$existing = & $Git -C $Root remote 2>$null
if ($existing -contains 'origin') {
    & $Git -C $Root remote set-url origin $remoteUrl
    Ok "origin updated -> $remoteUrl"
} else {
    & $Git -C $Root remote add origin $remoteUrl
    Ok "origin added -> $remoteUrl"
}

# --- 3. push ----------------------------------------------------------------
<#
  Credentials are supplied through GIT_ASKPASS, not through the remote URL.

  Embedding a token in the URL (`https://user:token@host/...`) is the common shortcut, but git echoes
  the URL it is pushing to on failure, which would leak the token into this log and into any
  transcript of it. GIT_ASKPASS keeps the secret out of both the command line (visible to other
  processes) and git's own output.

  Git invokes the helper more than once: first asking for a username, then for a password. It reads
  the first line of stdout and ignores the prompt text, so two tiny scripts suffice.
#>
$askPass = $null
$pushUrl = $remoteUrl

if ($Token) {
    $askPassDir = Join-Path $env:TEMP ("hsacc-askpass-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $askPassDir | Out-Null
    $userScript = Join-Path $askPassDir 'user.cmd'
    $passScript = Join-Path $askPassDir 'pass.cmd'

    # `x-access-token` is the username form GitHub expects when authenticating with a PAT.
    "@echo off`r`necho x-access-token`r`n" | Set-Content -Encoding ASCII -Path $userScript
    "@echo off`r`necho $Token`r`n"         | Set-Content -Encoding ASCII -Path $passScript

    # Git uses this single helper value for both prompts; it is passed the prompt text as argv[1],
    # so the helper can tell the two apart and answer accordingly.
    $askPass = Join-Path $askPassDir 'askpass.cmd'
    @"
@echo off
echo %1 | findstr /I "Username" >nul
if %errorlevel%==0 (
  call "$userScript"
) else (
  call "$passScript"
)
"@ | Set-Content -Encoding ASCII -Path $askPass

    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    Ok "credential helper staged (token is not written to .git/config or the URL)"
} else {
    Write-Host "  No token supplied; git may prompt interactively." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== Pushing $Branch to $remoteUrl ===" -ForegroundColor Cyan

& $Git -C $Root push $pushUrl "${Branch}:${Branch}" 2>&1 | ForEach-Object {
    $line = $_
    if ($Token) { $line = $line -replace [regex]::Escape($Token), '***' }
    Write-Host "    $line"
}
$pushExit = $LASTEXITCODE

# --- 4. clean up the credential helper --------------------------------------
Remove-Item Env:\GIT_ASKPASS -ErrorAction SilentlyContinue
Remove-Item Env:\GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
if ($askPass) {
    $askPassDir = Split-Path $askPass -Parent
    Remove-Item -Recurse -Force $askPassDir -ErrorAction SilentlyContinue
    Ok "credential helper removed"
}

Write-Host ""
if ($pushExit -eq 0) {
    Write-Host "=====================================================" -ForegroundColor Green
    Write-Host " PUSH SUCCEEDED" -ForegroundColor Green
    Write-Host "=====================================================" -ForegroundColor Green
    Write-Host "  https://github.com/$Username/$Repo"
    Write-Host ""
    & $Git -C $Root log -1 --format="  latest commit: %h  %s" | ForEach-Object { Write-Host $_ }
    Write-Host "  tracked files: $(((& $Git -C $Root ls-files) | Measure-Object).Count)"
} else {
    Write-Host "=====================================================" -ForegroundColor Red
    Write-Host " PUSH FAILED (exit $pushExit)" -ForegroundColor Red
    Write-Host "=====================================================" -ForegroundColor Red
    Write-Host "  Common causes:"
    Write-Host "   - the repository does not exist yet on GitHub (create it first, without a README)"
    Write-Host "   - the token lacks the 'repo' scope, or has expired"
    Write-Host "   - the remote already has commits (then pull/rebase first)"
}
exit $pushExit
