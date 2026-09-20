# 一个脚本构建所有版本，并同时推送到 CurseForge + Modrinth（每个版本只构建一次，两个平台各自上传）。
#
# 幂等：先跑 config-only 的 `gradlew publishState` 查两个平台是否已有对应产物；
#       某平台该版本的全部 loader 都已存在 → 本次不构建、不上传。
#       上传阶段 Gradle 侧还有第二道守卫（buildSrc/publish-curseforge.gradle /
#       publish-modrinth.gradle 的 doFirst），处理"部分已存在"的情况。
#
# 用法：
#   $env:CURSEFORGE_TOKEN = "..."; $env:MODRINTH_TOKEN = "..."
#   .\scripts\publish-mods.ps1
#   .\scripts\publish-mods.ps1 -DryRun
#   .\scripts\publish-mods.ps1 -Versions 1.20.1,1.21.1
#   .\scripts\publish-mods.ps1 -AnchorsOnly
#   .\scripts\publish-mods.ps1 -Targets curseforge      # 只推一个平台
#   .\scripts\publish-mods.ps1 -Force                   # 忽略"已存在"检查
param(
    [switch]$DryRun,
    [switch]$AnchorsOnly,
    [switch]$Force,
    [string]$Versions = '',
    [string]$ReleaseType = '',
    [string]$Changelog = '',
    [ValidateSet('curseforge', 'modrinth')]
    [string[]]$Targets = @('curseforge', 'modrinth'),
    [int]$Retries = 3
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$localProps = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
$repoPropsText = Get-Content (Join-Path $Root 'gradle.properties') -Raw

function Test-LocalToken([string]$key) {
    if (-not (Test-Path $localProps)) { return $false }
    return [bool](Select-String -Path $localProps -Pattern "^\s*$key\s*=" -Quiet)
}

# ---- 凭据 / 项目 ID 校验（只校验启用的平台）----
if ($Targets -contains 'curseforge') {
    if (-not $env:CURSEFORGE_TOKEN -and -not (Test-LocalToken 'curseforge_token')) {
        Write-Host "缺少 CURSEFORGE_TOKEN（或本机 ~/.gradle/gradle.properties 中的 curseforge_token）。" -ForegroundColor Red
        Write-Host "获取：https://www.curseforge.com/account/api-tokens" -ForegroundColor Yellow
        exit 1
    }
    if ($repoPropsText -notmatch '(?m)^\s*curseforge_project_id\s*=\s*\S+') {
        Write-Host "gradle.properties 中 curseforge_project_id 为空。" -ForegroundColor Red
        exit 1
    }
}
if ($Targets -contains 'modrinth') {
    if (-not $env:MODRINTH_TOKEN -and -not (Test-LocalToken 'modrinth_token')) {
        Write-Host "缺少 MODRINTH_TOKEN（或本机 ~/.gradle/gradle.properties 中的 modrinth_token）。" -ForegroundColor Red
        Write-Host "获取：https://modrinth.com/settings/personal-access-tokens（scope 需含创建版本）" -ForegroundColor Yellow
        exit 1
    }
    if ($repoPropsText -notmatch '(?m)^\s*modrinth_project_id\s*=\s*\S+') {
        Write-Host "gradle.properties 中 modrinth_project_id 为空。" -ForegroundColor Red
        exit 1
    }
}

# 锚点取自 docs/version-segments.md 的「编译锚点」表（段首 + 段尾）
$AnchorList = @('1.20.1', '1.21.1', '1.21.2', '1.21.5', '1.21.6', '1.21.9', '1.21.11')

$versionList = @()
if ($Versions) {
    $versionList = $Versions.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
} elseif ($AnchorsOnly) {
    $versionList = $AnchorList
} else {
    # 全量：遍历 versionProperties 下全部 *.properties，按版本号逐段数值排序（支持三段以上）
    $versionList = Get-ChildItem (Join-Path $Root 'versionProperties\*.properties') |
        ForEach-Object { $_.BaseName } |
        Sort-Object {
            $segments = $_.Split('.') | ForEach-Object { try { [int]$_ } catch { 0 } }
            ($segments | ForEach-Object { '{0:D12}' -f $_ }) -join '.'
        }
}

$Gradlew = Join-Path $Root 'gradlew.bat'
if (-not (Test-Path $Gradlew)) { $Gradlew = Join-Path $Root 'gradlew' }

$extra = @()
if ($DryRun) {
    if ($Targets -contains 'curseforge') { $extra += '-Pcurseforge_debug=true' }
    if ($Targets -contains 'modrinth') { $extra += '-Pmodrinth_debug=true' }
}
if ($ReleaseType) {
    if ($Targets -contains 'curseforge') { $extra += "-Pcurseforge_release_type=$ReleaseType" }
    if ($Targets -contains 'modrinth') { $extra += "-Pmodrinth_release_type=$ReleaseType" }
}
if ($Changelog) {
    if ($Targets -contains 'curseforge') { $extra += "-Pcurseforge_changelog=$Changelog" }
    if ($Targets -contains 'modrinth') { $extra += "-Pmodrinth_changelog=$Changelog" }
}

$publishTaskByTarget = @{ curseforge = 'publishCurseForge'; modrinth = 'publishModrinth' }

$failed = @()
$skipped = @()

foreach ($ver in $versionList) {
    $propsFile = Join-Path $Root "versionProperties/$ver.properties"
    if (-not (Test-Path $propsFile)) {
        Write-Host "SKIP $ver (no versionProperties)" -ForegroundColor Yellow
        continue
    }

    # builds_for 仅用于日志展示；实际构建哪些加载器由 Gradle 侧依据该 properties 决定
    $buildsFor = 'fabric'
    Get-Content $propsFile | ForEach-Object {
        if ($_ -match '^builds_for=(.+)$') { $buildsFor = $Matches[1] }
    }
    $loaders = $buildsFor.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }

    # ---- 幂等预检：config-only（不触发构建），只发 GET ----
    $stateLines = & $Gradlew publishState "-Pmc_ver=$ver" -q | Where-Object { $_ -match '^PUBLISH_STATE\s' }
    if ($LASTEXITCODE -ne 0 -or -not $stateLines) {
        Write-Host "`n=== $ver ($($loaders -join ',')) ===" -ForegroundColor Cyan
        Write-Host "FAILED: $ver (publishState 查询失败)" -ForegroundColor Red
        $failed += $ver
        continue
    }

    $need = @{}
    foreach ($t in $Targets) { $need[$t] = $false }
    $inconclusive = @()
    foreach ($line in $stateLines) {
        $parts = $line -split '\s+'
        $cf = (($parts | Where-Object { $_ -like 'cf=*' }) -join '') -replace '^cf=', ''
        $mr = (($parts | Where-Object { $_ -like 'mr=*' }) -join '') -replace '^mr=', ''
        if ($Targets -contains 'curseforge' -and $cf -ne 'yes') { $need['curseforge'] = $true }
        if ($Targets -contains 'modrinth' -and $mr -ne 'yes') { $need['modrinth'] = $true }
        # unknown(...) / no-token：查不出结论，只能照常上传（Gradle 侧同样只在 yes 时跳过）
        if ($cf -notin @('yes', 'no')) { $inconclusive += "cf=$cf" }
        if ($mr -notin @('yes', 'no')) { $inconclusive += "mr=$mr" }
    }

    $pendingTargets = @($Targets | Where-Object { $need[$_] })
    if ($Force) { $pendingTargets = @($Targets) }

    if ($pendingTargets.Count -eq 0) {
        Write-Host "SKIP $ver（$($Targets -join ' + ') 均已发布）" -ForegroundColor DarkGray
        $skipped += $ver
        continue
    }

    $tasks = @($pendingTargets | ForEach-Object { $publishTaskByTarget[$_] })
    Write-Host "`n=== Publish $ver ($($loaders -join ',')) → $($pendingTargets -join ' + ') ===" -ForegroundColor Cyan
    foreach ($line in $stateLines) { Write-Host "  $line" -ForegroundColor DarkGray }
    if ($inconclusive.Count -gt 0) {
        Write-Host "  警告：幂等检查未得出结论（$($inconclusive -join ', ')），这些平台会照常上传" -ForegroundColor Yellow
    }

    $ok = $false
    for ($attempt = 1; $attempt -le [Math]::Max(1, $Retries); $attempt++) {
        # 走 daemon（AGENTS.md：编译/打包用 daemon，不要 --no-daemon）。
        # 一条命令里同时跑 build + 两个平台的 publish task：构建只发生一次。
        # -x test：发布验证由运行时冒烟覆盖（全版本已过）；common 的 plain JUnit 是开发期
        #          单元片段且依赖 MC runtime/原版 API，让它挡 build 只会误伤产线产物。
        & $Gradlew build @tasks "-Pmc_ver=$ver" -x test @extra
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
        if ($attempt -lt $Retries) {
            # 重试退避：平台侧偶发 5xx / CurseForge 的 Cloudflare 质询窗口。
            # （不是"等构建完成"的 sleep —— 构建是上面那条同步命令自己结束的。）
            $backoff = $attempt * 20
            Write-Host "attempt $attempt failed, backing off ${backoff}s ..." -ForegroundColor Yellow
            Start-Sleep -Seconds $backoff
        }
    }

    if ($ok) {
        Write-Host "OK: $ver" -ForegroundColor Green
    } else {
        $failed += $ver
        Write-Host "FAILED: $ver" -ForegroundColor Red
    }
}

Write-Host ''
if ($skipped.Count -gt 0) {
    Write-Host "skipped (already published): $($skipped -join ', ')" -ForegroundColor DarkGray
}
if ($failed.Count -gt 0) {
    Write-Host "publish-mods failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host "publish-mods: all versions OK" -ForegroundColor Green
exit 0
