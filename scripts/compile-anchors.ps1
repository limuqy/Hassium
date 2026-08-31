# 按 docs/version-segments.md 对 7 个锚点 × builds_for 执行 compileJava。
# 单次 Gradle 进程只能绑定一个 mc_ver，故本脚本多次前台调用。
#
# 每个 Gradle 调用均在当前脚本中阻塞至退出。严禁调用 `gradlew --stop`：本脚本可能由
# 根项目的 `compileAnchors` Gradle task 启动，--stop 会终止该父 daemon 并中断自身。
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Anchors = @(
    '1.20.1',
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

    foreach ($loader in $loaders) {
        Write-Host "`n--- $ver / $loader ---" -ForegroundColor DarkCyan
        # 每个 loader 独立 Gradle invocation，避免同一 daemon 在 common:compileJava
        # 绑定前一个 loader 的 Loom classpath，导致 Manifold 版本条件错配。
        # 前台调用会阻塞至本次 gradlew 退出；不停止 daemon，避免终止父 compileAnchors task。
        & $Gradlew ":${loader}:compileJava" "-Pmc_ver=$ver" --console=plain
        $code = $LASTEXITCODE
        if ($code -ne 0) {
            $failed += "$ver/$loader"
            Write-Host "FAILED: $ver/$loader (exit $code)" -ForegroundColor Red
        } else {
            Write-Host "OK: $ver/$loader" -ForegroundColor Green
        }
    }
}

if ($failed.Count -gt 0) {
    Write-Host "`ncompileAnchors failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host "`ncompileAnchors: all anchors OK" -ForegroundColor Green
exit 0
