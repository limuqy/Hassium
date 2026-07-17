# 按 docs/version-segments.md 对 9 个锚点 × builds_for 执行 compileJava。
# 单次 Gradle 进程只能绑定一个 mc_ver，故本脚本多次调用。
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Anchors = @(
    '1.20.1',
    '1.20.2',
    '1.20.5',
    '1.21.1',
    '1.21.2',
    '1.21.5',
    '1.21.6',
    '1.21.9',
    '1.21.11'
)

$Gradlew = Join-Path $Root 'gradlew.bat'
if (-not (Test-Path $Gradlew)) {
    $Gradlew = Join-Path $Root 'gradlew'
}

$failed = @()

foreach ($ver in $Anchors) {
    $propsFile = Join-Path $Root "versionProperties/$ver.properties"
    if (-not (Test-Path $propsFile)) {
        Write-Host "SKIP $ver (no versionProperties)" -ForegroundColor Yellow
        continue
    }

    $buildsFor = 'fabric'
    Get-Content $propsFile | ForEach-Object {
        if ($_ -match '^builds_for=(.+)$') {
            $buildsFor = $Matches[1]
        }
    }
    $loaders = $buildsFor.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }

    $tasks = @()
    foreach ($loader in $loaders) {
        $tasks += ":${loader}:compileJava"
    }

    Write-Host "`n=== Anchor $ver ($($loaders -join ',')) ===" -ForegroundColor Cyan
    # PowerShell 会拆开 1.20.1，必须给 -P 参数加引号
    & $Gradlew --no-daemon @tasks "-Pmc_ver=$ver"
    if ($LASTEXITCODE -ne 0) {
        $failed += "$ver"
        Write-Host "FAILED: $ver" -ForegroundColor Red
    } else {
        Write-Host "OK: $ver" -ForegroundColor Green
    }
}

if ($failed.Count -gt 0) {
    Write-Host "`ncompileAnchors failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host "`ncompileAnchors: all anchors OK" -ForegroundColor Green
exit 0
