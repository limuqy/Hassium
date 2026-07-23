# 运行时冒烟测试 — 批量脚本（两轮连服版，支持并行）
# 用法:
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I                              # 全量初始轮（串行）
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel                    # 全量初始轮（并行，fabric+neoforge 同时）
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Versions @("1.20.1","1.20.2")
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase R                              # 回归轮
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Loaders fabric              # 仅 fabric
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel -BasePort 25570    # 并行 + 自定义起始端口
# 每个版本×加载器 1 个会话（客户端自动两轮：VD=16 + VD=8）
# CleanWorld 策略（按 loader 独立，fabric/neoforge 各有 run/server）:
#   - 该 loader 的第一个版本：清理服务端存档
#   - 后续版本：默认不清理（复用存档加快启动）
#   - 退版本（高→低）：强制清理（高版本存档无法被低版本读取）
#   - 同会话失败重试：强制清理（干净重试）
# 并行模式: 同版本 fabric+neoforge 同时跑，端口分配 fabric=BasePort, neoforge=BasePort+1
#           版本间仍串行（避免跨版本存档冲突）；不调用会话间 gradlew --stop，batch 结束后统一 stop
param(
    [Parameter(Mandatory=$true)][ValidateSet("I","R")][string]$Phase,
    [string[]]$Versions,
    [ValidateSet("fabric","neoforge")][string[]]$Loaders = @("fabric","neoforge"),
    [int]$MaxRetries = 3,
    [switch]$Parallel,
    [int]$BasePort = 25565,
    [int]$ServerReadyTimeoutSec = 300,
    [int]$ClientTimeoutSec = 600
)

$ErrorActionPreference = "Continue"

# 路径自推导（脚本位于 <repo>/scripts/，项目根是父目录）
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot "build\smoke-test"
$logDir = Join-Path $logRoot "logs"
$resultsDir = Join-Path $logRoot "results"
$failuresLog = Join-Path $logRoot "failures-${Phase}.log"

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

# 版本顺序（低到高）
$allVersions = @(
    "1.20.1","1.20.2","1.20.3","1.20.4","1.20.5","1.20.6",
    "1.21.1","1.21.2","1.21.3","1.21.4","1.21.5","1.21.6",
    "1.21.7","1.21.8","1.21.9","1.21.10","1.21.11"
)

if ($Versions) {
    $targetVersions = $Versions
} else {
    $targetVersions = $allVersions
}

# 版本比较函数：返回 true 表示 currentVer < prevVer（退版本）
function IsVersionDowngrade($current, $previous) {
    $cur = $current -split '\.' | ForEach-Object { [int]$_ }
    $prev = $previous -split '\.' | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt [Math]::Max($cur.Count, $prev.Count); $i++) {
        $c = if ($i -lt $cur.Count) { $cur[$i] } else { 0 }
        $p = if ($i -lt $prev.Count) { $prev[$i] } else { 0 }
        if ($c -lt $p) { return $true }
        if ($c -gt $p) { return $false }
    }
    return $false
}

# 按 loader 决定是否清理服务端存档（fabric/neoforge 各有独立 run/server）
# 返回: $true=清理, $false=复用
function Get-ShouldCleanWorld {
    param(
        [string]$Ver,
        [string]$Loader,
        [hashtable]$PrevVerByLoader
    )
    if (-not $PrevVerByLoader.ContainsKey($Loader)) {
        return $true  # 该 loader 第一个版本：清理
    }
    $prev = $PrevVerByLoader[$Loader]
    if (IsVersionDowngrade $Ver $prev) {
        return $true  # 退版本：强制清理
    }
    return $false  # 后续同向/升版本：不清理
}

# 单会话执行函数（封装重试逻辑，供串行路径共用）
# 返回 [PSCustomObject]@{ Ver; Loader; Phase; Result; SessionId; Attempts; Reason }
function Invoke-Session {
    param(
        [string]$Ver,
        [string]$Loader,
        [string]$Phase,
        [int]$ServerPort,
        [int]$MaxRetries,
        [switch]$CleanWorld,
        [int]$ServerReadyTimeoutSec = 180,
        [int]$ClientTimeoutSec = 300
    )
    $sessionId = "${Ver}_${Loader}_${Phase}"
    $sessionResult = $null
    $attempt = 0
    $lastReason = ""
    $scriptPath = Join-Path $PSScriptRoot "runtime-smoke-test.ps1"

    while ($attempt -lt $MaxRetries) {
        $attempt++
        # 首试遵循 batch 策略；失败重试强制清档，避免脏存档导致连环失败
        $doClean = $CleanWorld -or ($attempt -gt 1)
        $cleanLabel = if ($doClean) { "CleanWorld" } else { "ReuseWorld" }
        Write-Host "[$sessionId] 尝试 $attempt/$MaxRetries (port=$ServerPort, $cleanLabel)..."

        $result = & $scriptPath `
            -Ver $Ver -Loader $Loader -Phase $Phase `
            -SessionId $sessionId -CleanWorld:$doClean `
            -ServerPort $ServerPort `
            -ServerReadyTimeoutSec $ServerReadyTimeoutSec `
            -ClientTimeoutSec $ClientTimeoutSec

        if ($result -eq "PASS") {
            $sessionResult = "PASS"
            $lastReason = ""
            break
        }

        $sessionResult = "FAIL"
        # 读取 result JSON 提取失败原因
        $resultJsonPath = Join-Path $resultsDir "result_${sessionId}.json"
        if (Test-Path $resultJsonPath) {
            try {
                $resultObj = Get-Content $resultJsonPath -Raw | ConvertFrom-Json
                $lastReason = if ($resultObj.Reason) { $resultObj.Reason } else {
                    "Round1Pass=$($resultObj.Round1Pass) Round2Pass=$($resultObj.Round2Pass) Exit=$($resultObj.ClientExitCode)"
                }
            } catch {
                $lastReason = "result JSON parse error"
            }
        }
        Write-Host "[$sessionId] 尝试 $attempt 失败: $lastReason" -ForegroundColor Red
    }

    if ($sessionResult -eq "FAIL") {
        $failLine = "[$sessionId] FAILED after $MaxRetries attempts: $lastReason"
        Add-Content -Path $failuresLog -Value $failLine
        Write-Host $failLine -ForegroundColor Red
    }

    return [PSCustomObject]@{
        Ver=$Ver
        Loader=$Loader
        Phase=$Phase
        Result=$sessionResult
        SessionId=$sessionId
        Attempts=$attempt
        Reason=$lastReason
    }
}

$results = @()
# 每个 loader 上次成功调度的版本（用于首轮清档 / 退版本强制清档）
$prevVerByLoader = @{}

foreach ($ver in $targetVersions) {
    Write-Host ""
    Write-Host "============================================"
    Write-Host "=== Testing: $ver (loaders: $($Loaders -join ','))"
    Write-Host "============================================"

    if ($Parallel -and $Loaders.Count -gt 1) {
        # ===== 并行模式：同版本多 loader 用 Start-Process 同时跑 =====
        # 注意：不能用 Start-Job（Job 内 Start-Process gradlew.bat 会静默失败）
        # 改用 Start-Process powershell.exe -File 启动独立进程，各进程内 Start-Process gradlew.bat 正常工作

        # 预编译：在并行启动前先同步编译所有 loader，避免两个并行进程同时触发编译冲突
        # 优先用 :classes 一次编译所有模块（gradle.properties 已启用 parallel，Gradle 内部并行编译）
        # 失败时回退到逐 loader 编译，以隔离错误（fabric 失败仍可跑 neoforge）
        $gradlew = Join-Path $projectRoot "gradlew.bat"
        $precompileFailed = @{}

        Write-Host "[$ver] 预编译 (classes, parallel)..."
        & $gradlew classes "-Pmc_ver=${ver}" 2>&1 | Out-Host
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[$ver] 预编译成功 (classes)" -ForegroundColor Green
        } else {
            Write-Host "[$ver] :classes 失败 (exit $LASTEXITCODE)，回退到逐 loader 编译以隔离错误" -ForegroundColor Yellow
            foreach ($loader in $Loaders) {
                Write-Host "[$ver/${loader}] 预编译 (compileJava)..."
                & $gradlew ":${loader}:compileJava" "-Pmc_ver=${ver}" 2>&1 | Out-Host
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "[$ver/${loader}] 预编译失败 (exit $LASTEXITCODE)，跳过该会话" -ForegroundColor Red
                    $precompileFailed[$loader] = $true
                }
            }
        }

        # 过滤掉预编译失败的 loader
        $activeLoaders = $Loaders | Where-Object { -not $precompileFailed[$_] }
        if ($activeLoaders.Count -eq 0) {
            Write-Host "[$ver] 所有 loader 预编译失败，跳过该版本" -ForegroundColor Red
            foreach ($loader in $Loaders) {
                $skipSessionId = "${ver}_${loader}_${Phase}"
                $results += [PSCustomObject]@{
                    Ver=$ver; Loader=$loader; Phase=$Phase; Result="FAIL"
                    SessionId=$skipSessionId; Attempts=0; Reason="precompile_failed"
                }
                Add-Content -Path $failuresLog -Value "[$skipSessionId] FAILED: precompile_failed"
            }
            continue
        }

        $processes = @()
        $scriptPath = Join-Path $PSScriptRoot "runtime-smoke-test.ps1"
        for ($i = 0; $i -lt $activeLoaders.Count; $i++) {
            $loader = $activeLoaders[$i]
            # 端口分配：原 Loaders 顺序保持不变，确保 fabric=BasePort, neoforge=BasePort+1
            $loaderIndex = [Array]::IndexOf($Loaders, $loader)
            $port = $BasePort + $loaderIndex
            $jobName = "${ver}_${loader}_${Phase}"
            $cleanWorld = Get-ShouldCleanWorld -Ver $ver -Loader $loader -PrevVerByLoader $prevVerByLoader
            $cleanLabel = if ($cleanWorld) { "CleanWorld" } else { "ReuseWorld" }
            if ($cleanWorld -and $prevVerByLoader.ContainsKey($loader)) {
                Write-Host "=== [$loader] 退版本 $($prevVerByLoader[$loader]) -> $ver，清理服务端存档 ===" -ForegroundColor Yellow
            }
            Write-Host "[$jobName] 启动进程 (port=$port, $cleanLabel)..."

            $procArgs = @(
                "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", $scriptPath,
                "-Ver", $ver, "-Loader", $loader, "-Phase", $Phase,
                "-SessionId", $jobName,
                "-ServerPort", $port,
                "-ServerReadyTimeoutSec", $ServerReadyTimeoutSec,
                "-ClientTimeoutSec", $ClientTimeoutSec
            )
            if ($cleanWorld) {
                $procArgs += "-CleanWorld"
            }

            $procOutLog = Join-Path $logDir "parallel_${jobName}.log"
            $procErrLog = Join-Path $logDir "parallel_${jobName}_err.log"

            $proc = Start-Process -FilePath "powershell.exe" `
                -ArgumentList $procArgs `
                -RedirectStandardOutput $procOutLog `
                -RedirectStandardError $procErrLog `
                -PassThru -WindowStyle Hidden

            $processes += [PSCustomObject]@{ Name=$jobName; Process=$proc; Loader=$loader; Port=$port; OutLog=$procOutLog }
            # 调度后即记录该 loader 的上一版本（不论成败，下一轮按策略决定是否清档）
            $prevVerByLoader[$loader] = $ver

            # 启动后等 3 秒再启动下一个，避免同时启动竞争资源；最后一个不用等
            if ($i -lt $activeLoaders.Count - 1) {
                Start-Sleep -Seconds 3
            }
        }

        # 等待所有进程完成（总超时 = serverReadyTimeout + clientTimeout + 120s 缓冲）
        $procTimeoutMs = ($ServerReadyTimeoutSec + $ClientTimeoutSec + 120) * 1000
        Write-Host "等待 $($processes.Count) 个进程完成..."

        foreach ($p in $processes) {
            if (-not $p.Process.HasExited) {
                $p.Process.WaitForExit($procTimeoutMs) | Out-Null
            }
            if (-not $p.Process.HasExited) {
                Write-Host "[$($p.Name)] 进程超时，强制停止" -ForegroundColor Red
                Stop-Process -Id $p.Process.Id -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 2
            }
        }

        # 回显进程输出 + 从 result JSON 收集结果
        foreach ($p in $processes) {
            $sessionId = $p.Name

            # 回显进程 stdout 到控制台
            if (Test-Path $p.OutLog) {
                $procOutput = Get-Content $p.OutLog -Raw
                if ($procOutput -and $procOutput.Trim()) {
                    Write-Host $procOutput
                }
            }

            # 从 result JSON 读取结果
            $resultJsonPath = Join-Path $resultsDir "result_${sessionId}.json"
            if (Test-Path $resultJsonPath) {
                try {
                    $resultObj = Get-Content $resultJsonPath -Raw | ConvertFrom-Json
                    $lastReason = if ($resultObj.Reason) { $resultObj.Reason } else {
                        if ($resultObj.Result -ne "PASS") {
                            "Round1Pass=$($resultObj.Round1Pass) Round2Pass=$($resultObj.Round2Pass) Exit=$($resultObj.ClientExitCode)"
                        } else { "" }
                    }
                    if ($resultObj.Result -ne "PASS") {
                        Add-Content -Path $failuresLog -Value "[$sessionId] FAILED: $lastReason"
                    }
                    $results += [PSCustomObject]@{
                        Ver=$ver; Loader=$p.Loader; Phase=$Phase; Result=$resultObj.Result
                        SessionId=$sessionId; Attempts=1; Reason=$lastReason
                    }
                } catch {
                    $results += [PSCustomObject]@{
                        Ver=$ver; Loader=$p.Loader; Phase=$Phase; Result="FAIL"
                        SessionId=$sessionId; Attempts=1; Reason="result JSON parse error"
                    }
                }
            } else {
                $results += [PSCustomObject]@{
                    Ver=$ver; Loader=$p.Loader; Phase=$Phase; Result="FAIL"
                    SessionId=$sessionId; Attempts=0; Reason="no_result_json"
                }
            }
        }

        # 并行模式：每版本结束后清理残留 Minecraft java 进程，保留 gradle daemon 供下一版本复用
        # 仅杀命令行包含 "run\server" 或 "run\client" 的 java（即 Minecraft 实例），不杀 gradle daemon
        Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -eq "java.exe" -and $_.CommandLine -and $_.CommandLine -match "run[\\/]+(server|client)"
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 3
    } else {
        # ===== 串行模式（默认）=====
        foreach ($loader in $Loaders) {
            $sessionId = "${ver}_${loader}_${Phase}"
            $cleanWorld = Get-ShouldCleanWorld -Ver $ver -Loader $loader -PrevVerByLoader $prevVerByLoader
            $cleanLabel = if ($cleanWorld) { "CleanWorld" } else { "ReuseWorld" }
            if ($cleanWorld -and $prevVerByLoader.ContainsKey($loader)) {
                Write-Host "=== [$loader] 退版本 $($prevVerByLoader[$loader]) -> $ver，清理服务端存档 ===" -ForegroundColor Yellow
            }
            Write-Host ""
            Write-Host "--- $sessionId ($cleanLabel) ---"

            $r = Invoke-Session -Ver $ver -Loader $loader -Phase $Phase -ServerPort $BasePort -MaxRetries $MaxRetries `
                -CleanWorld:$cleanWorld -ServerReadyTimeoutSec $ServerReadyTimeoutSec -ClientTimeoutSec $ClientTimeoutSec
            $results += $r
            $prevVerByLoader[$loader] = $ver

            # 杀残留 Minecraft java 进程（不杀 gradle daemon）
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -eq "java.exe" -and $_.CommandLine -and $_.CommandLine -match "run[\\/]+(server|client)"
            } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 3

            # Gradle daemon 清理（避免 loom 锁）
            & (Join-Path $projectRoot "gradlew.bat") --stop 2>&1 | Out-Null
            Start-Sleep -Seconds 2
        }
    }
}

# 并行模式：batch 结束后统一调用一次 gradlew --stop
if ($Parallel) {
    Write-Host ""
    Write-Host "=== batch 结束，统一清理 Gradle daemon ===" -ForegroundColor Cyan
    & (Join-Path $projectRoot "gradlew.bat") --stop 2>&1 | Out-Null
}

# 最终汇总
Write-Host ""
Write-Host "=== BATCH SUMMARY ($Phase, parallel=$Parallel) ===" -ForegroundColor Cyan
$results | Format-Table Ver,Loader,Result,Attempts -AutoSize
$csvPath = Join-Path $logRoot "batch-results-${Phase}.csv"
$results | Export-Csv $csvPath -NoTypeInformation
Write-Host "Results saved to: $csvPath"

# 统计
$pass = @($results | Where-Object { $_.Result -eq "PASS" }).Count
$fail = @($results | Where-Object { $_.Result -eq "FAIL" }).Count
Write-Host "PASS: $pass / FAIL: $fail / TOTAL: $($results.Count)" -ForegroundColor Cyan
if ($fail -gt 0) {
    Write-Host "Failures log: $failuresLog" -ForegroundColor Yellow
}
