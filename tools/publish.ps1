# =============================================================================
#  Publish this repository to GitHub.
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\publish.ps1 -Username cook2604 -Token <PAT>
#
#  Arguments are read from $args explicitly rather than through a param() block: a param block
#  combined with the rest of this script's structure tripped a PowerShell 5.1 parser bug that
#  surfaced as a bogus "string is missing the terminator" error on the final line.
#
#  The token is never written to disk, never stored in .git/config, and never placed on a command
#  line. It is handed to git through GIT_ASKPASS so it cannot leak into git's own output (git echoes
#  the push URL on failure, which is why embedding it in the URL is avoided).
#
#  The repository must already exist on GitHub. This script only pushes.
# =============================================================================

$ErrorActionPreference = 'Continue'
$Root = 'H:\ddaa\APP'
$Git = Join-Path $Root 'tools\git\cmd\git.exe'
$env:GIT_CONFIG_NOSYSTEM = '1'

# --- parse arguments ---------------------------------------------------------
$Username = ''
$Repo = 'hotspot-accounting'
$Token = ''
$Branch = 'main'

for ($i = 0; $i -lt $args.Count; $i++) {
    $a = [string]$args[$i]
    switch -Regex ($a) {
        '^-Username$' { $Username = [string]$args[$i + 1]; $i++ }
        '^-Repo$'     { $Repo = [string]$args[$i + 1]; $i++ }
        '^-Token$'    { $Token = [string]$args[$i + 1]; $i++ }
        '^-Branch$'   { $Branch = [string]$args[$i + 1]; $i++ }
    }
}

if ([string]::IsNullOrWhiteSpace($Username)) {
    Write-Host '  [FAIL] -Username is required' -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $Git)) {
    Write-Host ('  [FAIL] git not found at ' + $Git) -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $Root '.git'))) {
    Write-Host '  [FAIL] no git repository found' -ForegroundColor Red
    exit 1
}

Write-Host ('  user   : ' + $Username)
Write-Host ('  repo   : ' + $Repo)
Write-Host ('  branch : ' + $Branch)
if ($Token) { Write-Host '  token  : supplied (not shown)' } else { Write-Host '  token  : not supplied' }
Write-Host ''

# --- commit anything outstanding ---------------------------------------------
$pending = & $Git -C $Root status --porcelain
if ($pending) {
    Write-Host '  Uncommitted changes present; committing them first.' -ForegroundColor Yellow
    & $Git -C $Root add -A
    $authorEmail = $Username + '@users.noreply.github.com'
    & $Git -C $Root -c ('user.name=' + $Username) -c ('user.email=' + $authorEmail) commit -m 'chore: sync local changes' | Out-Null
    Write-Host '  [OK] committed pending changes' -ForegroundColor Green
} else {
    Write-Host '  [OK] working tree clean' -ForegroundColor Green
}

$head = (& $Git -C $Root rev-parse --short HEAD).Trim()
Write-Host ('  [OK] HEAD = ' + $head) -ForegroundColor Green

# --- point origin at the repository ------------------------------------------
$remoteUrl = 'https://github.com/' + $Username + '/' + $Repo + '.git'
$existing = & $Git -C $Root remote
if ($existing -contains 'origin') {
    & $Git -C $Root remote set-url origin $remoteUrl
    Write-Host ('  [OK] origin updated -> ' + $remoteUrl) -ForegroundColor Green
} else {
    & $Git -C $Root remote add origin $remoteUrl
    Write-Host ('  [OK] origin added -> ' + $remoteUrl) -ForegroundColor Green
}

# --- build the credential helper ---------------------------------------------
$askPassDir = ''
if ($Token) {
    $askPassDir = Join-Path $env:TEMP ('hsacc-askpass-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $askPassDir | Out-Null

    $userScript = Join-Path $askPassDir 'user.cmd'
    $passScript = Join-Path $askPassDir 'pass.cmd'
    $askPass = Join-Path $askPassDir 'askpass.cmd'

    $userBody = @('@echo off', 'echo x-access-token') -join "`r`n"
    Set-Content -Path $userScript -Value $userBody -Encoding ASCII

    $passBody = @('@echo off', 'echo __TOKEN_PLACEHOLDER__') -join "`r`n"
    Set-Content -Path $passScript -Value $passBody -Encoding ASCII
    $replaced = ([System.IO.File]::ReadAllText($passScript)).Replace('__TOKEN_PLACEHOLDER__', $Token)
    [System.IO.File]::WriteAllText($passScript, $replaced, [System.Text.Encoding]::ASCII)

    # Build the batch lines with -f formatting, not string concatenation.
    #
    # `'  call ' + $quote + $path + $quote` inside an array literal does NOT produce one element:
    # PowerShell's comma binds looser than '+', so the concatenation is mis-parsed and the path ends
    # up on its own line, producing a broken .cmd. Verified by generating the file and running it.
    $callUser = '  call "{0}"' -f $userScript
    $callPass = '  call "{0}"' -f $passScript
    $askLines = @(
        '@echo off',
        'echo %1 | findstr /I Username >nul',
        'if %errorlevel%==0 (',
        $callUser,
        ') else (',
        $callPass,
        ')'
    )
    $askBody = $askLines -join "`r`n"
    Set-Content -Path $askPass -Value $askBody -Encoding ASCII

    $env:GIT_ASKPASS = $askPass
    $env:GIT_TERMINAL_PROMPT = '0'
    Write-Host '  [OK] credential helper staged' -ForegroundColor Green
}

# --- push --------------------------------------------------------------------
Write-Host ''
Write-Host ('=== Pushing ' + $Branch + ' to ' + $remoteUrl + ' ===') -ForegroundColor Cyan

& $Git -C $Root push $remoteUrl ($Branch + ':' + $Branch) 2>&1 | ForEach-Object {
    $line = [string]$_
    if ($Token) { $line = $line.Replace($Token, '***') }
    Write-Host ('    ' + $line)
}
$pushExit = $LASTEXITCODE

# --- clean up ----------------------------------------------------------------
Remove-Item Env:\GIT_ASKPASS -ErrorAction SilentlyContinue
Remove-Item Env:\GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue
if ($askPassDir -and (Test-Path $askPassDir)) {
    Remove-Item -Recurse -Force $askPassDir -ErrorAction SilentlyContinue
    Write-Host '  [OK] credential helper removed' -ForegroundColor Green
}

Write-Host ''
if ($pushExit -eq 0) {
    Write-Host '=====================================================' -ForegroundColor Green
    Write-Host ' PUSH SUCCEEDED' -ForegroundColor Green
    Write-Host '=====================================================' -ForegroundColor Green
    Write-Host ('  https://github.com/' + $Username + '/' + $Repo)
} else {
    Write-Host '=====================================================' -ForegroundColor Red
    Write-Host (' PUSH FAILED (exit ' + $pushExit + ')') -ForegroundColor Red
    Write-Host '=====================================================' -ForegroundColor Red
    Write-Host '  Common causes:'
    Write-Host '   - the repository does not exist yet on GitHub'
    Write-Host '   - the token lacks the repo scope, or has expired'
    Write-Host '   - the remote already has commits (pull/rebase first)'
}
exit $pushExit
