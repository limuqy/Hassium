# 按 docs/version-segments.md 对 9 个锚点 × builds_for 执行 compileJava。
# 单次 Gradle 进程只能绑定一个 mc_ver，故本脚本多次调用。
#
# 卡住常见原因：fabric-loom 全局锁被 IDEA Gradle Sync 或其他 Gradle 占用，
# 会无限打印 "Waiting for lock..."。本脚本在启动前检测存活持锁进程并直接失败；
# 每个锚点结束后 --stop，避免 Daemon/锁残留拖死下一版本。
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

function Stop-GradleDaemons {
    & $Gradlew --stop 2>$null | Out-Null
}

function Get-LoomLockHolderPids {
    $pids = [System.Collections.Generic.HashSet[int]]::new()
    $loom = Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'
    if (-not (Test-Path $loom)) {
        return @()
    }
    Get-ChildItem -Path $loom -Filter '*.lock' -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            $text = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
            if ($text -match 'pid["\s:=]+(\d+)') {
                [void]$pids.Add([int]$Matches[1])
            }
        } catch {
            # ignore unreadable locks
        }
    }
    return @($pids)
}

function Assert-NoForeignLoomLock {
    $holders = Get-LoomLockHolderPids
    foreach ($pid in $holders) {
        $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
        if ($null -eq $proc) {
            continue
        }
        $name = $proc.ProcessName
        Write-Host @"

错误: fabric-loom 缓存锁仍被存活进程占用 (pid=$pid, name=$name)。
命令行 Gradle 会无限等待该锁（表现为「卡住」）。
请先：
  1) 在 IDEA 中取消/停止 Gradle Sync，或暂时关闭本项目的 Gradle 导入
  2) 或结束该 Java/Gradle 进程后执行: .\gradlew --stop
然后重新运行本脚本。

"@ -ForegroundColor Red
        exit 2
    }
}

Write-Host "Stopping existing Gradle daemons..." -ForegroundColor DarkGray
Stop-GradleDaemons
Assert-NoForeignLoomLock

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
    Assert-NoForeignLoomLock

    # PowerShell 会拆开 1.20.1，必须给 -P 参数加引号；勿加 --no-daemon
    & $Gradlew @tasks "-Pmc_ver=$ver" --console=plain
    $code = $LASTEXITCODE

    # 无论成败都释放 Daemon / loom 锁，再进下一锚点
    Stop-GradleDaemons

    if ($code -ne 0) {
        $failed += "$ver"
        Write-Host "FAILED: $ver (exit $code)" -ForegroundColor Red
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
