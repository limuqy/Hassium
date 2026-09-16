# Full multi-version compile + common:test matrix for Hassium.
# Usage: pwsh -File scripts/full-version-compile.ps1 [-Offline] [-SkipTests] [-Versions 1.20.1,1.21.1]
# Continues on failure; prints RESULT table at end. Exit 0 only if all steps pass.

[CmdletBinding()]
param(
    [switch]$Offline = $true,
    [switch]$SkipTests,
    [string[]]$Versions = @()
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# builds_for from versionProperties (source of truth)
$matrix = [ordered]@{
    '1.20.1'  = @('fabric', 'forge')
    '1.21.1'  = @('fabric', 'neoforge', 'forge')
    '1.21.2'  = @('fabric', 'neoforge')
    '1.21.3'  = @('fabric', 'neoforge', 'forge')
    '1.21.4'  = @('fabric', 'neoforge', 'forge')
    '1.21.5'  = @('fabric', 'neoforge', 'forge')
    '1.21.6'  = @('fabric', 'neoforge', 'forge')
    '1.21.7'  = @('fabric', 'neoforge', 'forge')
    '1.21.8'  = @('fabric', 'neoforge', 'forge')
    '1.21.9'  = @('fabric', 'neoforge', 'forge')
    '1.21.10' = @('fabric', 'neoforge', 'forge')
    '1.21.11' = @('fabric', 'neoforge')
}

if ($Versions.Count -gt 0) {
    $full = [ordered]@{
        '1.20.1'  = @('fabric', 'forge')
        '1.21.1'  = @('fabric', 'neoforge', 'forge')
        '1.21.2'  = @('fabric', 'neoforge')
        '1.21.3'  = @('fabric', 'neoforge', 'forge')
        '1.21.4'  = @('fabric', 'neoforge', 'forge')
        '1.21.5'  = @('fabric', 'neoforge', 'forge')
        '1.21.6'  = @('fabric', 'neoforge', 'forge')
        '1.21.7'  = @('fabric', 'neoforge', 'forge')
        '1.21.8'  = @('fabric', 'neoforge', 'forge')
        '1.21.9'  = @('fabric', 'neoforge', 'forge')
        '1.21.10' = @('fabric', 'neoforge', 'forge')
        '1.21.11' = @('fabric', 'neoforge')
    }
    $matrix = [ordered]@{}
    foreach ($v in $Versions) {
        if (-not $full.Contains($v)) { throw "Unknown version: $v" }
        $matrix[$v] = $full[$v]
    }
}

$results = [System.Collections.Generic.List[object]]::new()
$offlineArgs = @()
if ($Offline) { $offlineArgs += '--offline' }

function Invoke-GradleStep {
    param(
        [string]$Label,
        [string[]]$GradleArgs
    )
    Write-Host ""
    Write-Host "=== [$Label] .\gradlew.bat $($GradleArgs -join ' ')" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # Capture both success and failure markers; do not pipe to tail.
    $output = & .\gradlew.bat @GradleArgs 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $sw.Stop()
    $text = ($output | Out-String)
    $ok = ($code -eq 0) -and ($text -match 'BUILD SUCCESSFUL')
    $status = if ($ok) { 'PASS' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host "=== [$Label] $status in $([math]::Round($sw.Elapsed.TotalSeconds,1))s (exit=$code)" -ForegroundColor $color
    if (-not $ok) {
        # Print last 40 lines for diagnosis
        $lines = $output | Select-Object -Last 40
        Write-Host ($lines -join "`n") -ForegroundColor DarkYellow
    }
    $results.Add([pscustomobject]@{
            Label   = $Label
            Status  = $status
            Exit    = $code
            Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        })
    return $ok
}

# 1) boundary scan (uses current default mc_ver / project versionProperties)
Invoke-GradleStep -Label 'scanVersionBoundaries' -GradleArgs (@('scanVersionBoundaries') + $offlineArgs) | Out-Null

# 2) per-version: common:compileJava, loader:compileJava, common:test
foreach ($ver in $matrix.Keys) {
    $loaders = $matrix[$ver]
    Write-Host "`n########## Minecraft $ver  loaders= $($loaders -join ',') ##########" -ForegroundColor Magenta

    # Clean loader build residue when switching versions (AGENTS: stale generic build/classes)
    # Only wipe when needed; loom output dirs are version-specific for common but not fully for loaders.
    foreach ($loader in $loaders) {
        $bin = Join-Path $root "$loader\bin"
        $out = Join-Path $root "$loader\out"
        foreach ($d in @($bin, $out)) {
            if (Test-Path $d) {
                Write-Host "Removing IDE residue: $d"
                Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue
            }
        }
    }

    Invoke-GradleStep -Label "common $ver compile" `
        -GradleArgs (@('common:compileJava', "-Pmc_ver=$ver") + $offlineArgs) | Out-Null

    foreach ($loader in $loaders) {
        Invoke-GradleStep -Label "$loader $ver compile" `
            -GradleArgs (@("${loader}:compileJava", "-Pmc_ver=$ver") + $offlineArgs) | Out-Null
    }

    if (-not $SkipTests) {
        Invoke-GradleStep -Label "common $ver test" `
            -GradleArgs (@('common:test', "-Pmc_ver=$ver") + $offlineArgs) | Out-Null
    }
}

Write-Host "`n========== RESULT TABLE ==========" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host

$fail = @($results | Where-Object Status -eq 'FAIL')
$pass = @($results | Where-Object Status -eq 'PASS')
Write-Host "PASS=$($pass.Count)  FAIL=$($fail.Count)  TOTAL=$($results.Count)"
if ($fail.Count -gt 0) {
    Write-Host "Failed steps:" -ForegroundColor Red
    $fail | ForEach-Object { Write-Host "  - $($_.Label) (exit=$($_.Exit), $($_.Seconds)s)" -ForegroundColor Red }
    exit 2
}
Write-Host '=== RESULT: PASS ===' -ForegroundColor Green
exit 0
