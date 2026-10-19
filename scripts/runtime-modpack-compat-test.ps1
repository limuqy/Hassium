param(
    [ValidateSet('setup', 'run', 'status', 'runtime')][string]$Action = 'run',
    [ValidateSet('integrated', 'dedicated')][string]$Stage = 'integrated',
    [string[]]$Packs = @(),
    [string]$Python = 'python',
    [string]$Minecraft = 'D:\MC\HMCL\.minecraft',
    [string]$OutputRoot = '',
    [int]$TimeoutSec = 0,
    [int]$Port = 25579,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    if ($Action -notin @('status', 'runtime') -and -not $SkipBuild) {
        & .\gradlew.bat fabric:remapJar forge:remapJar '-Pmc_ver=1.20.1'
        if ($LASTEXITCODE -ne 0) { throw 'Minecraft 1.20.1 build failed' }
        & .\gradlew.bat neoforge:remapJar '-Pmc_ver=1.21.1'
        if ($LASTEXITCODE -ne 0) { throw 'Minecraft 1.21.1 build failed' }
    }
    if ($Action -eq 'runtime' -and $Stage -ne 'dedicated') { throw 'Runtime preparation requires -Stage dedicated' }
    if ($TimeoutSec -eq 0) { $TimeoutSec = if ($Stage -eq 'dedicated') { 1200 } else { 780 } }
    $script = if ($Stage -eq 'dedicated') { 'scripts/modpack-dedicated.py' } else { 'scripts/modpack-compat.py' }
    $arguments = @($script, $Action, '--minecraft', $Minecraft, '--timeout', "$TimeoutSec")
    if ($Stage -eq 'dedicated') { $arguments += '--port'; $arguments += "$Port" }
    if ($OutputRoot) { $arguments += '--root'; $arguments += $OutputRoot }
    if ($Packs.Count -gt 0) { $arguments += '--packs'; $arguments += $Packs }
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Modpack $Stage harness failed; inspect its report and per-run logs" }
} finally { Pop-Location }
