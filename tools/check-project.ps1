# =============================================================================
#  Static self-check of the Android project, runnable without the Android SDK.
#
#  Catches the class of mistakes that only surface deep into a Gradle build (and therefore cost a
#  full dependency download to discover): source/package mismatches, unresolved R references,
#  manifest classes that do not exist, and duplicate or conflicting declarations.
#
#  Run:  powershell -ExecutionPolicy Bypass -File H:\ddaa\APP\tools\check-project.ps1
# =============================================================================
$ErrorActionPreference = 'Continue'

$Root = 'H:\ddaa\APP'
$App = Join-Path $Root 'app'
$Src = Join-Path $App 'src\main\java'
$Res = Join-Path $App 'src\main\res'
$Manifest = Join-Path $App 'src\main\AndroidManifest.xml'

$problems = New-Object System.Collections.Generic.List[string]
$notes = New-Object System.Collections.Generic.List[string]

function Fail($m) { $problems.Add($m) }
function Note($m) { $notes.Add($m) }

Write-Host "=== 1. Kotlin sources: package vs directory ===" -ForegroundColor Cyan
$ktFiles = Get-ChildItem -Path $Src -Recurse -Filter *.kt -ErrorAction SilentlyContinue
Write-Host "  found $($ktFiles.Count) .kt files"
foreach ($f in $ktFiles) {
    $firstPackageLine = Select-String -Path $f.FullName -Pattern '^package\s+([\w\.]+)' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $firstPackageLine) {
        Fail "$($f.Name): no package declaration"
        continue
    }
    $pkg = $firstPackageLine.Matches[0].Groups[1].Value
    $expectedDir = Join-Path $Src ($pkg -replace '\.', '\')
    if (-not (Test-Path $expectedDir)) {
        Fail "$($f.Name): package '$pkg' has no matching directory"
    }
}

Write-Host "=== 2. Duplicate top-level declarations ===" -ForegroundColor Cyan
# Two `class Foo` / `object Foo` / `interface Foo` at top level in the same package collide.
$decls = @{}
foreach ($f in $ktFiles) {
    $pkg = (Select-String -Path $f.FullName -Pattern '^package\s+([\w\.]+)' |
        Select-Object -First 1).Matches[0].Groups[1].Value
    foreach ($m in (Select-String -Path $f.FullName -Pattern '^(?:internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*(class|object|interface|enum class)\s+(\w+)')) {
        $kind = $m.Matches[0].Groups[1].Value
        $name = $m.Matches[0].Groups[2].Value
        $key = "$pkg.$name"
        if ($decls.ContainsKey($key)) {
            Fail "duplicate declaration $kind $key in $($decls[$key]) and $($f.Name)"
        } else {
            $decls[$key] = $f.Name
        }
    }
}
Write-Host "  $($decls.Count) top-level declarations"

Write-Host "=== 3. Imports that reference project packages ===" -ForegroundColor Cyan
# Any `import com.hotspot.accounting.X` must resolve to a declaration we actually made.
$projectPkgs = $decls.Keys | ForEach-Object { $_ } | Sort-Object
foreach ($f in $ktFiles) {
    foreach ($m in (Select-String -Path $f.FullName -Pattern '^import\s+(com\.hotspot\.accounting[\w\.]*)')) {
        $imp = $m.Matches[0].Groups[1].Value
        # Match either a class/object or a top-level function/property in that file.
        $match = $projectPkgs | Where-Object { $_ -eq $imp -or $_ -like "$imp.*" } | Select-Object -First 1
        if (-not $match) {
            # Could be a top-level function (e.g. requireValidMac) - check by name occurrence.
            $leaf = ($imp -split '\.')[-1]
            $found = Select-String -Path $ktFiles.FullName -Pattern "fun\s+$leaf\b|val\s+$leaf\b|const\s+val\s+$leaf\b" -ErrorAction SilentlyContinue
            if (-not $found) {
                Fail "$($f.Name): import '$imp' does not resolve to any declaration"
            }
        }
    }
}

Write-Host "=== 4. Manifest class references exist ===" -ForegroundColor Cyan
if (Test-Path $Manifest) {
    $manifestText = Get-Content $Manifest -Raw
    $appPackage = 'com.hotspot.accounting'
    foreach ($m in [regex]::Matches($manifestText, 'android:name="(\.?[\w\.]+)"')) {
        $raw = $m.Groups[1].Value
        # Skip framework/permission constants and property names.
        if ($raw -match '^(android|androidx|android\.app)\.') { continue }
        if ($raw -like 'android.permission*') { continue }
        $fq = if ($raw.StartsWith('.')) { "$appPackage$raw" } else { $raw }
        if ($decls.Keys -notcontains $fq) {
            Fail "AndroidManifest references '$raw' -> '$fq' which is not declared"
        }
    }
    Write-Host "  manifest class references checked"
} else {
    Fail "AndroidManifest.xml missing"
}

Write-Host "=== 5. Resource references resolve ===" -ForegroundColor Cyan
$resFiles = Get-ChildItem -Path $Res -Recurse -File -ErrorAction SilentlyContinue
$resNames = @{}
foreach ($r in $resFiles) {
    $folder = Split-Path $r.FullName -Parent | Split-Path -Leaf
    $base = [System.IO.Path]::GetFileNameWithoutExtension($r.Name)

    if ($folder -match '^values') {
        # Value files declare many resources inside one file (strings.xml holds every string), so the
        # names have to be parsed out of the XML rather than inferred from the filename.
        #
        # Read through .NET rather than Get-Content: on Windows PowerShell 5.1 the latter defaults to
        # the ANSI code page, which turns the UTF-8 Chinese string values into mojibake and breaks
        # XML parsing ("start tag does not match end tag").
        $raw = [System.IO.File]::ReadAllText($r.FullName, [System.Text.Encoding]::UTF8)
        [xml]$doc = $raw
        foreach ($node in $doc.DocumentElement.ChildNodes) {
            if ($node.NodeType -ne 'Element') { continue }
            # LocalName, not Name: PowerShell's XML adapter exposes `.Name` as the `name` *attribute*
            # (the adapter is case-insensitive), so `.Name` would yield "app_name" instead of "string".
            $type = $node.LocalName
            $nameAttr = $node.Attributes['name']
            if ($nameAttr) { $resNames["$type/$($nameAttr.Value)"] = $r.FullName }
        }
    } else {
        # File-based resources: the type is the directory prefix before any config qualifier
        # (drawable-v24 -> drawable, mipmap-anydpi-v26 -> mipmap).
        $type = $folder.Split('-')[0]
        $resNames["$type/$base"] = $r.FullName
    }
}
Write-Host "  $($resNames.Count) resource entries"

# @string/x, @drawable/x, @xml/x, @color/x, @mipmap/x, @style/x referenced from XML or Kotlin.
# Names may contain dots (e.g. @style/Theme.HotspotAccounting), so the pattern allows them.
$refTargets = @($ktFiles.FullName) + @($resFiles.FullName) + @($Manifest)
foreach ($t in $refTargets) {
    foreach ($m in (Select-String -Path $t -Pattern '@(string|drawable|xml|color|mipmap|style)/([\w.]+)' -AllMatches -ErrorAction SilentlyContinue)) {
        foreach ($mm in $m.Matches) {
            $type = $mm.Groups[1].Value
            $name = $mm.Groups[2].Value
            if (-not $resNames.ContainsKey("$type/$name")) {
                Fail "$(Split-Path $t -Leaf): @$type/$name not found in res/"
            }
        }
    }
}

# R.<type>.<name> references from Kotlin. The negative lookbehind skips `android.R.drawable.x`,
# which is a framework resource and correctly not present in this project.
foreach ($f in $ktFiles) {
    foreach ($m in (Select-String -Path $f.FullName -Pattern '(?<![\w\.])R\.(string|drawable|xml|color|mipmap|style)\.(\w+)' -AllMatches -ErrorAction SilentlyContinue)) {
        foreach ($mm in $m.Matches) {
            $type = $mm.Groups[1].Value
            $name = $mm.Groups[2].Value
            if (-not $resNames.ContainsKey("$type/$name")) {
                Fail "$($f.Name): R.$type.$name not found in res/"
            }
        }
    }
}

Write-Host "=== 6. Gradle script sanity ===" -ForegroundColor Cyan
$appGradle = Get-Content (Join-Path $App 'build.gradle.kts') -Raw
foreach ($needle in @('com.android.application', 'org.jetbrains.kotlin.android',
                      'org.jetbrains.kotlin.plugin.compose', 'com.google.devtools.ksp')) {
    if ($appGradle -notmatch [regex]::Escape($needle)) { Fail "app/build.gradle.kts missing plugin: $needle" }
}
if ($appGradle -notmatch 'compose\s*=\s*true') { Fail "app/build.gradle.kts does not enable compose" }
if ($appGradle -notmatch 'room.schemaLocation') { Note "room.schemaLocation not set (exportSchema would fail)" }
if ($appGradle -notmatch 'lifecycle-runtime-compose') { Fail "collectAsStateWithLifecycle needs lifecycle-runtime-compose" }

$rootGradle = Get-Content (Join-Path $Root 'build.gradle.kts') -Raw
# Plugin versions declared at the root must match what :app applies.
foreach ($m in [regex]::Matches($appGradle, 'id\("([\w\.]+)"\)')) {
    $id = $m.Groups[1].Value
    if ($rootGradle -notmatch [regex]::Escape($id)) { Note "plugin '$id' applied in :app but not in root plugins block" }
}
Write-Host "  gradle scripts checked"

Write-Host "=== 7. KSP/Room DAO shape ===" -ForegroundColor Cyan
$dao = Get-Content (Join-Path $Src 'com\hotspot\accounting\data\Daos.kt') -Raw
# Room needs an @Insert before a @Transaction method that relies on the row existing.
if ($dao -match '@Transaction' -and $dao -notmatch '@Insert') {
    Fail "Daos.kt has @Transaction but no @Insert"
}
# Every @Query must be followed by a function declaration.
$queryCount = ([regex]::Matches($dao, '@Query')).Count
$funCount = ([regex]::Matches($dao, '\n\s*(suspend\s+)?fun\s')).Count
if ($queryCount -gt $funCount) { Fail "Daos.kt: $queryCount @Query but only $funCount fun declarations" }
Write-Host "  $queryCount @Query, $funCount functions"

Write-Host "=== 8. Room entity constructor parameters ===" -ForegroundColor Cyan
<#
  A parameter in a data class primary constructor that omits `val`/`var` is not a property at all,
  so Room cannot see the column. KSP then reports the far less obvious
  "Element 'X' references a type that is not present" and refuses to process the whole database.
  This happened once (a missing `val` on DeviceEntity.nickname), so it is checked explicitly.

  Only constructor parameters carrying Room annotations are inspected, which keeps the check precise:
  `@Entity`/`@ColumnInfo`/`@PrimaryKey` all imply the parameter is meant to be a column.
#>
foreach ($f in $ktFiles) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
    $inPrimaryCtor = $false
    $pendingRoomAnnotation = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i].Trim()
        if ($line -match '^(data\s+)?class\s+\w+[^)]*\($' -or $line -match '^(data\s+)?class\s+\w+.*\($') {
            $inPrimaryCtor = $true
            $pendingRoomAnnotation = $false
            continue
        }
        if (-not $inPrimaryCtor) { continue }
        if ($line -match '^\)') { $inPrimaryCtor = $false; continue }

        if ($line -match '@(PrimaryKey|ColumnInfo|Embedded|Ignore|Relation)') {
            $pendingRoomAnnotation = $true
            # The annotation may share the line with the parameter, e.g.
            #   @ColumnInfo(name="mac") val mac: String,
            $rest = $line -replace '^.*@\w+\([^)]*\)\s*', ''
            if ($rest -match '^\s*(val|var)\s') { $pendingRoomAnnotation = $false }
            continue
        }
        if ($pendingRoomAnnotation) {
            if ($line -match '^(val|var)\s') {
                $pendingRoomAnnotation = $false
            } elseif ($line -match '\S') {
                Fail ("$($f.Name):$($i + 1): constructor parameter is missing 'val' or 'var' " +
                      "-> $line")
                $pendingRoomAnnotation = $false
            }
        }
    }
}
Write-Host "  entity constructors checked"

Write-Host ""
Write-Host "================ RESULT ================" -ForegroundColor Cyan
if ($problems.Count -eq 0) {
    Write-Host " NO PROBLEMS FOUND" -ForegroundColor Green
} else {
    Write-Host " $($problems.Count) PROBLEM(S):" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "   - $_" -ForegroundColor Red }
}
if ($notes.Count -gt 0) {
    Write-Host " notes:" -ForegroundColor Yellow
    $notes | ForEach-Object { Write-Host "   * $_" -ForegroundColor Yellow }
}
Write-Host "========================================"
if ($problems.Count -gt 0) { exit 1 } else { exit 0 }
