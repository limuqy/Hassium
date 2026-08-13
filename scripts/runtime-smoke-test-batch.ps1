# 运行时冒烟测试 — 批量脚本（两轮连服版，支持并行）
# 用法:
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I                              # 全量初始轮（串行）
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel                    # 全量初始轮（并行，fabric+neoforge 同时）
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Versions @("1.20.1","1.20.2")
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase R                              # 回归轮
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Loaders fabric,forge,neoforge  # 含 Forge（仅 1.20.1/1.20.6 有 builds_for=forge，其它版本自动 SKIP）
# 每个版本×加载器 1 个会话（客户端自动两轮：VD=20 + VD=10）
# CleanWorld 策略（按 loader 独立，fabric/forge/neoforge 各有 run/server）:
#   - 该 loader 的第一个版本：清理服务端存档
#   - 版本变化（升或降）：清理（worldgen 跨版本可能变化——1.21.9 地形塑造重构、
#     1.21.4 pale garden 等；复用旧版本 terrain 会让新版本 seedgen 影子端系统性
#     mismatch，R2 命中率崩塌。T8 1.21.11 实测 17.8%）
#   - 同版本：复用存档加快启动
#   - 同会话失败重试：强制清理（干净重试）
# 并行模式: 同版本多 loader 同时跑，端口按 -Loaders 顺序 fabric=BasePort, forge=+1, neoforge=+2
#           版本间仍串行（避免跨版本存档冲突）；全程不调 gradlew --stop（全局停 daemon 会误杀
#           并行会话/其他项目的构建；runServer/runClient 均为 --no-daemon，不依赖 daemon）
param(
    [Parameter(Mandatory=$true)][ValidateSet("I","R","UdpFailover")][string]$Phase,
    [string[]]$Versions,
    [ValidateSet("fabric","forge","neoforge")][string[]]$Loaders = @("fabric","neoforge"),
    [int]$MaxRetries = 3,
    [switch]$Parallel,
    [int]$BasePort = 25565,
    [int]$ServerReadyTimeoutSec = 300,
    [int]$ClientTimeoutSec = 600,
    [int]$DelayMs = 10000,
    [int]$ReconnectDelayMs = 3000
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
# 版本相等判断（与 IsVersionDowngrade 同源：数值段比较，避免 "1.9" vs "1.10" 字典序陷阱）
function IsSameVersion($current, $previous) {
    $cur = $current -split '\.' | ForEach-Object { [int]$_ }
    $prev = $previous -split '\.' | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt [Math]::Max($cur.Count, $prev.Count); $i++) {
        $c = if ($i -lt $cur.Count) { $cur[$i] } else { 0 }
        $p = if ($i -lt $prev.Count) { $prev[$i] } else { 0 }
        if ($c -ne $p) { return $false }
    }
    return $true
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
    if (-not (IsSameVersion $Ver $prev)) {
        # 版本变化（升或降）一律清理：worldgen 跨版本可能变化（1.21.9 地形塑造重构 /
        # 1.21.4 pale garden 生物群系表等），复用旧版本 terrain 会让新版本 seedgen
        # 影子端系统性 hash mismatch（T8 1.21.11 实测 R2 命中 17.8%，服务端 90+
        # 条 SECTION_DELTA fallback 风暴）。同版本复用保留「加快启动」语义。
        return $true
    }
    return $false  # 同版本：复用存档加快启动
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
        [int]$ClientTimeoutSec = 300,
        [int]$DelayMs = 10000,
        [int]$ReconnectDelayMs = 3000
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

        # CleanWorld 且预生成存档缺失时，先跑一次预生成（49×49 区域），
        # 让首轮供给曲线由提交上限+充足区块决定，而非 worldgen 节奏。
        # 预生成失败不阻断冒烟（降级为正常 worldgen）。
        if ($doClean) {
            $pregenSrc = Join-Path $projectRoot "build\smoke-test\pregen-world\${Loader}-${Ver}\world"
            if (-not (Test-Path $pregenSrc)) {
                Write-Host "[$sessionId] 预生成存档缺失，先执行预生成 (${Loader}/${Ver})..."
                & $scriptPath -Ver $Ver -Loader $Loader -Phase $Phase `
                    -SessionId "${sessionId}_pregen" -PregenOnly `
                    -ServerPort $ServerPort -ServerReadyTimeoutSec $ServerReadyTimeoutSec
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "[$sessionId] 预生成失败，降级为正常 worldgen 冒烟" -ForegroundColor Yellow
                }
            }
        }

        $result = & $scriptPath `
            -Ver $Ver -Loader $Loader -Phase $Phase `
            -SessionId $sessionId -CleanWorld:$doClean `
            -ServerPort $ServerPort `
            -ServerReadyTimeoutSec $ServerReadyTimeoutSec `
            -ClientTimeoutSec $ClientTimeoutSec `
            -DelayMs $DelayMs -ReconnectDelayMs $ReconnectDelayMs

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
$gradlewPath = Join-Path $projectRoot "gradlew.bat"
$prevVersion = $null

foreach ($ver in $targetVersions) {
    # Manifold #if MC_VER 宏在 javac 阶段求值：切换 mc_ver 后 Gradle 增量缓存
    # 不感知宏值变化，会在 common 报"包不存在"类假错误。版本切换时先 clean
    # 全部模块，确保按新版本宏全量重编（同版本内多 loader 共享 common 产物，不 clean）。
    if ($prevVersion -ne $null -and $prevVersion -ne $ver) {
        Write-Host "[clean] 版本 $prevVersion -> $ver，先清理各模块编译产物（Manifold 宏防污染）..." -ForegroundColor Yellow
        $cleanModules = @("common") + ($Loaders | ForEach-Object { $_ })
        & $gradlewPath ($cleanModules | ForEach-Object { "${_}:clean" }) "--no-daemon" 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[clean] 清理失败 (exit $LASTEXITCODE)，继续（可能产生假编译错误）" -ForegroundColor Yellow
        }
    }
    $prevVersion = $ver
    Write-Host ""
    Write-Host "============================================"
    Write-Host "=== Testing: $ver (loaders: $($Loaders -join ','))"
    Write-Host "============================================"

    # Forge 仅段 A(1.20.1)/段 C 段尾(1.20.6)有 builds_for；其它版本强行跑 :forge:runServer
    # 会因 settings.gradle 未 include forge 子项目而直接失败。读 versionProperties/<ver>.properties
    # 的 builds_for，按它过滤 -Loaders，只跑该版本真正构建的 loader。
    $propsPath = Join-Path $projectRoot "versionProperties\${ver}.properties"
    $supportedLoaders = @()
    if (Test-Path $propsPath) {
        $propsText = Get-Content $propsPath -Raw
        $m = [regex]::Match($propsText, '(?im)^builds_for\s*=\s*(.+)$')
        if ($m.Success) {
            $supportedLoaders = ($m.Groups[1].Value -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        }
    }
    if ($supportedLoaders.Count -gt 0) {
        $activeLoadersForVer = $Loaders | Where-Object { $supportedLoaders -contains $_ }
    } else {
        # 没读到 builds_for（极少见，如缺失 properties）：保留原 Loaders，由后续编译失败兜底
        $activeLoadersForVer = $Loaders
    }
    # 端口分配仍按原 -Loaders 顺序取下标，保证 fabric=BasePort, forge/neoforge 按位偏移
    $loaderPortIndex = @{}
    for ($li = 0; $li -lt $Loaders.Count; $li++) { $loaderPortIndex[$Loaders[$li]] = $li }

    # 跳过该版本不支持的 loader（典型：1.20.2 等段内无 Forge）
    $skippedLoaders = $Loaders | Where-Object { -not ($activeLoadersForVer -contains $_) }
    foreach ($sk in $skippedLoaders) {
        $skipSessionId = "${ver}_${sk}_${Phase}"
        Write-Host "[$skipSessionId] 跳过：versionProperties/${ver}.properties builds_for 不含 ${sk}" -ForegroundColor DarkGray
        $results += [PSCustomObject]@{
            Ver=$ver; Loader=$sk; Phase=$Phase; Result="SKIP"
            SessionId=$skipSessionId; Attempts=0; Reason="not_in_builds_for"
        }
    }
    if ($activeLoadersForVer.Count -eq 0) {
        Write-Host "[$ver] 无匹配 loader（-Loaders 与 builds_for 无交集），跳过该版本" -ForegroundColor Yellow
        continue
    }

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
            foreach ($loader in $activeLoadersForVer) {
                Write-Host "[$ver/${loader}] 预编译 (compileJava)..."
                & $gradlew ":${loader}:compileJava" "-Pmc_ver=${ver}" 2>&1 | Out-Host
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "[$ver/${loader}] 预编译失败 (exit $LASTEXITCODE)，跳过该会话" -ForegroundColor Red
                    $precompileFailed[$loader] = $true
                }
            }
        }

        # 过滤掉预编译失败的 loader
        $activeLoaders = $activeLoadersForVer | Where-Object { -not $precompileFailed[$_] }
        if ($activeLoaders.Count -eq 0) {
            Write-Host "[$ver] 所有 loader 预编译失败，跳过该版本" -ForegroundColor Red
            foreach ($loader in $activeLoadersForVer) {
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
            # 端口分配：按 -Loaders 原顺序取下标（确保 fabric=BasePort, forge=+1, neoforge=+2 等）
            $loaderIndex = $loaderPortIndex[$loader]
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
        # 仅杀本工程 loom dev 实例（dli.config 指向本工程 + env 标记），不杀 gradle daemon，
        # 也不匹配其他项目/会话的实例
        $rootEsc = [regex]::Escape($projectRoot)
        $dliConfig = "-Dfabric\.dli\.config=$rootEsc"
        Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -eq "java.exe" -and $_.CommandLine -and $_.CommandLine -match $dliConfig -and
            $_.CommandLine -match "-Dfabric\.dli\.env=(server|client)"
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 3
    } else {
        # ===== 串行模式（默认）=====
        foreach ($loader in $activeLoadersForVer) {
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

            # 杀残留 Minecraft java 进程（仅本工程 loom dev 实例：dli.config + env 标记；
            # 不杀 gradle daemon，不误杀其他项目/会话）
            $rootEsc = [regex]::Escape($projectRoot)
            $dliConfig = "-Dfabric\.dli\.config=$rootEsc"
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -eq "java.exe" -and $_.CommandLine -and $_.CommandLine -match $dliConfig -and
                $_.CommandLine -match "-Dfabric\.dli\.env=(server|client)"
            } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 3

            # 不调 gradlew --stop：全局停 daemon 会误杀并行会话/其他项目的构建（loom 锁问题由 --no-daemon 规避）
        }
    }
}

# 并行模式收尾：不再统一 gradlew --stop——全局停 daemon 会误杀其他项目/会话的构建；
# 残留 daemon 由后续构建自然复用。

# 最终汇总
Write-Host ""
Write-Host "=== BATCH SUMMARY ($Phase, parallel=$Parallel) ===" -ForegroundColor Cyan
$results | Format-Table Ver,Loader,Result,Attempts -AutoSize
$csvPath = Join-Path $logRoot "batch-results-${Phase}.csv"
$results | Export-Csv $csvPath -NoTypeInformation
Write-Host "Results saved to: $csvPath"

# 统计（SKIP = 该版本的 builds_for 不含该 loader，不去占 BUILD）
$pass = @($results | Where-Object { $_.Result -eq "PASS" }).Count
$fail = @($results | Where-Object { $_.Result -eq "FAIL" }).Count
$skip = @($results | Where-Object { $_.Result -eq "SKIP" }).Count
Write-Host "PASS: $pass / FAIL: $fail / SKIP: $skip / TOTAL: $($results.Count)" -ForegroundColor Cyan
if ($fail -gt 0) {
    Write-Host "Failures log: $failuresLog" -ForegroundColor Yellow
}
