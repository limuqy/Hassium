# 对 versionProperties 中每个 MC 版本执行 build + publishCurseForge（按 builds_for 上传各加载器）。
# 用法：
#   $env:CURSEFORGE_TOKEN = "你的token"
#   .\scripts\publish-curseforge.ps1
#   .\scripts\publish-curseforge.ps1 -DryRun
#   .\scripts\publish-curseforge.ps1 -Versions 1.20.1,1.20.6
#   .\scripts\publish-curseforge.ps1 -AnchorsOnly
param(
    [switch]$DryRun,
    [switch]$AnchorsOnly,
    [string]$Versions = '',
    [string]$ReleaseType = '',
    [string]$Changelog = ''
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not $env:CURSEFORGE_TOKEN -and -not (Select-String -Path "$env:USERPROFILE\.gradle\gradle.properties" -Pattern '^\s*curseforge_token\s*=' -ErrorAction SilentlyContinue)) {
    Write-Host "缺少 CURSEFORGE_TOKEN（或本机 ~/.gradle/gradle.properties 中的 curseforge_token）。" -ForegroundColor Red
    Write-Host "获取：https://www.curseforge.com/account/api-tokens" -ForegroundColor Yellow
    exit 1
}

$propsPath = Join-Path $Root 'gradle.properties'
$projectIdLine = Get-Content $propsPath | Where-Object { $_ -match '^\s*curseforge_project_id\s*=\s*(\S+)' }
if (-not $projectIdLine) {
    Write-Host "gradle.properties 中 curseforge_project_id 为空。请填写 CurseForge 数字项目 ID 后重试。" -ForegroundColor Red
    exit 1
}

$AnchorList = @(
    '1.20.1', '1.20.2', '1.20.5', '1.20.6',
    '1.21.1', '1.21.2', '1.21.5', '1.21.6', '1.21.9', '1.21.11'
)

$versionList = @()
if ($Versions) {
    $versionList = $Versions.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
} elseif ($AnchorsOnly) {
    $versionList = $AnchorList
} else {
    $versionList = Get-ChildItem (Join-Path $Root 'versionProperties\*.properties') |
        ForEach-Object { $_.BaseName } |
        Sort-Object {
            $parts = $_.Split('.')
            [int]$parts[0] * 10000 + [int]$parts[1] * 100 + [int]$parts[2]
        }
}

$Gradlew = Join-Path $Root 'gradlew.bat'
if (-not (Test-Path $Gradlew)) {
    $Gradlew = Join-Path $Root 'gradlew'
}

$extra = @()
if ($DryRun) {
    $extra += '-Pcurseforge_debug=true'
}
if ($ReleaseType) {
    $extra += "-Pcurseforge_release_type=$ReleaseType"
}
if ($Changelog) {
    $extra += "-Pcurseforge_changelog=$Changelog"
}

$failed = @()

foreach ($ver in $versionList) {
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

    Write-Host "`n=== Publish $ver ($($loaders -join ',')) ===" -ForegroundColor Cyan
    # PowerShell 会拆开 1.20.1，必须给 -P 参数加引号；锚点间 --stop 释放 loom 锁
    & $Gradlew build publishCurseForge "-Pmc_ver=$ver" @extra
    $code = $LASTEXITCODE
    & $Gradlew --stop 2>$null | Out-Null
    if ($code -ne 0) {
        $failed += "$ver"
        Write-Host "FAILED: $ver" -ForegroundColor Red
    } else {
        Write-Host "OK: $ver" -ForegroundColor Green
    }
}

if ($failed.Count -gt 0) {
    Write-Host "`npublish-curseforge failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host "`npublish-curseforge: all versions OK" -ForegroundColor Green
exit 0
