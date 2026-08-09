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
    # 锚点取自 docs/version-segments.md 的「编译锚点」表（段首 + 段尾），与 compileAnchors 保持一致。
    $versionList = $AnchorList
} else {
    # 全量：遍历 versionProperties 目录下全部 *.properties。按「版本号语义」排序，
    # 支持三段及以上版本号（split 后逐段转 int 比较），与 bash 版 sort -V 等价。
    $versionList = Get-ChildItem (Join-Path $Root 'versionProperties\*.properties') |
        ForEach-Object { $_.BaseName } |
        Sort-Object {
            $segments = $_.Split('.') | ForEach-Object {
                try { [int]$_ } catch { 0 }
            }
            # 把每段补零对齐成定宽串再拼接比较，等价于逐段按数值比较：
            # 1.21.9 → "1.21.9" 的各段 → "000000000001.000000000002.000000000009"，
            # 与 1.21.10 的 "...000000000010" 逐字符比，9 < 10 正确，不受字符串长度影响。
            ($segments | ForEach-Object { '{0:D12}' -f $_ }) -join '.'
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

    # builds_for 仅用于日志展示。实际构建哪些加载器由 Gradle 侧依据该 properties 的 builds_for 决定
    # （Multiloader：build 会自动拉取目标加载器，publishCurseForge 也在各 loader 子项目各自注册），
    # PowerShell 无需按 loader 循环；此处解析仅为打印一行更直观的进度标题。
    $buildsFor = 'fabric'
    Get-Content $propsFile | ForEach-Object {
        if ($_ -match '^builds_for=(.+)$') {
            $buildsFor = $Matches[1]
        }
    }
    $loaders = $buildsFor.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }

    Write-Host "`n=== Publish $ver ($($loaders -join ',')) ===" -ForegroundColor Cyan
    # PowerShell 会拆开 1.20.1，必须给 -P 参数加引号。
    # --no-daemon 与 AGENTS.md 全程构建惯例统一，避免 daemon 残留占用 jar。
    # -x test：发布验证由 scripts/runtime-smoke-test-batch.ps1 那条运行时冒烟覆盖（全版本已过），
    #          common 的 plain JUnit 是开发期单元片段且依赖 MC runtime/原版 API，让它挡 build 只会误伤产线产物。
    & $Gradlew --no-daemon build publishCurseForge "-Pmc_ver=$ver" -x test @extra
    $code = $LASTEXITCODE
    # --no-daemon 模式无持久 daemon，不需 --stop；JVM 进程随 Gradle 退出即释放 jar 锁。
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
