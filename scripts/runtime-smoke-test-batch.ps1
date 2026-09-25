# 运行时冒烟测试 — 批量脚本（两轮连服版，固定串行）
# 用法:
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I                              # 全量初始轮（串行）
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Versions @("1.20.1","1.21.1")
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase R                              # 回归轮
#   .\scripts\runtime-smoke-test-batch.ps1 -Phase I -Loaders fabric,forge,neoforge  # 含 Forge（仅 1.20.1/1.21.1+ 部分版本有 builds_for=forge，其它版本自动 SKIP）
# 每个版本×加载器 1 个会话（客户端自动两轮：VD=20 + VD=10）
# T8 场景加载: -Scenarios classic,seedgen,dimension（默认仅 classic）。classic 走全矩阵
# （-Versions × -Loaders）；非 classic 场景只在锚点集跑（硬编码：1.20.1 forge、
# 1.21.1 neoforge、1.21.11 fabric，再与 -Versions/-Loaders/versionProperties builds_for
# 取交集）。非 classic 会话 sessionId 追加 _<scenario> 后缀避免 result JSON 冲突；
# CSV 增 Scenario 列。
# CleanWorld：存档已按 loader×ver 隔离（parity_<loader>_<ver>），切版本不会互相覆盖，
# batch 不再因「首个版本 / 退版本」清档。seedgen/dimension 场景脚本内仍强制清理。
# 重试：仅游戏打不开（服务端未就绪 / 客户端没进世界）才重跑；进过服的业务 FAIL 不重试。
# 预生成已退役，不再在重试前跑 PregenOnly、也不从 pregen-world 恢复存档。
# -Parallel 已忽略：单版本（1 服 + 1 客户端 + 影子 worldgen）已经吃满 CPU，并行会过载。
param(
    [Parameter(Mandatory=$true)][ValidateSet("I","R")][string]$Phase,
    [string[]]$Versions,
    [ValidateSet("fabric","forge","neoforge")][string[]]$Loaders = @("fabric","neoforge"),
    # T8 场景列表（默认仅 classic，保持既有行为）：classic=全矩阵；非 classic 只跑锚点集
    [string[]]$Scenarios = @("classic"),
    [int]$MaxRetries = 3,  # 仅「游戏打不开」时重试；进过世界的业务失败不重跑
    # 保留开关以免旧命令行报错；单版本已吃满 CPU，下面会强制关掉。
    [switch]$Parallel,
    [int]$BasePort = 25565,
    [int]$ServerReadyTimeoutSec = 300,
    [int]$ClientTimeoutSec = 600,
    [int]$DelayMs = 20000,
    [int]$ReconnectDelayMs = 3000,
    # classic 口径：滑块 = Vd1 = 20；OVD 仍由 chunk.maxRenderDistance=16 钳制
    [int]$ClientRenderDistance = 20,
    [string]$SessionSuffix
)

$ErrorActionPreference = "Continue"
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error 'runtime-smoke-test-batch.ps1 requires PowerShell 7+ (pwsh).'
    exit 3
}
if ($Parallel) {
    Write-Host "忽略 -Parallel：单版本（服务端+客户端+影子 worldgen）已经吃满 CPU，batch 固定串行。" -ForegroundColor Yellow
    $Parallel = $false
}


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
    "1.20.1",
    "1.21.1","1.21.2","1.21.3","1.21.4","1.21.5","1.21.6",
    "1.21.7","1.21.8","1.21.9","1.21.10","1.21.11"
)

if ($Versions) {
    $targetVersions = $Versions
} else {
    $targetVersions = $allVersions
}

# T8 场景锚点集（硬编码）：非 classic 场景只在锚点 (Ver, Loader) 组合上跑，
# 再与 -Versions / -Loaders / versionProperties builds_for 取交集。
# 最简三锚点：三加载器各一，覆盖 1.20.1 / 1.21.1 / 1.21.11 版本段边界。
$smokeScenarioAnchors = @(
    @{ Ver = "1.20.1";  Loader = "forge" },
    @{ Ver = "1.21.1";  Loader = "neoforge" },
    @{ Ver = "1.21.11"; Loader = "fabric" }
)

# 预展开场景计划：classic=全矩阵 targetVersions（Loader 空=该版本全部 -Loaders）；
# 非 classic=精确到 (Ver, Loader) 锚点，禁止把 1.21.1 锚点扩成 fabric+neoforge。
$scenarioPlan = @()
foreach ($sc in $Scenarios) {
    if ($sc -eq "classic") {
        foreach ($v in $targetVersions) {
            $scenarioPlan += [PSCustomObject]@{ Scenario = $sc; Ver = $v; Loader = "" }
        }
    } else {
        $anchors = @(
            $smokeScenarioAnchors |
                Where-Object { $Loaders -contains $_.Loader } |
                Where-Object { -not $Versions -or ($targetVersions -contains $_.Ver) } |
                # dictionary 场景依赖 /tick 命令（1.20.3+）做十倍速采样，1.20.1 锚点不适用
                Where-Object { $sc -ne "dictionary" -or $_.Ver -ne "1.20.1" }
        )
        if ($anchors.Count -eq 0) {
            Write-Host "[scenario:$sc] 锚点集与 -Versions/-Loaders 无交集，跳过该场景" -ForegroundColor Yellow
            continue
        }
        Write-Host "[scenario:$sc] 锚点: $(($anchors | ForEach-Object { "$($_.Ver)/$($_.Loader)" }) -join ', ')"
        foreach ($a in $anchors) {
            $scenarioPlan += [PSCustomObject]@{ Scenario = $sc; Ver = $a.Ver; Loader = $a.Loader }
        }
    }
}

# T2 PROBE JSON v1：把单会话 result JSON 的 Probe.RoundN 摘要成一行短串（joined/gateway/counters），
# 供 CSV 增列观测；无 probe（旧客户端或解析失败）时返回空串。

function Get-SmokeSessionIdSuffix {
    param(
        [string]$Scenario,
        [string]$SessionSuffix
    )
    $parts = @()
    if ($SessionSuffix) { $parts += $SessionSuffix }
    if ($Scenario -and $Scenario -ne "classic") { $parts += $Scenario }
    if ($parts.Count -eq 0) { return "" }
    return "_" + ($parts -join "_")
}

function Format-SmokeProbeRound {
    param($Round)
    if (-not $Round) { return "" }
    $parts = @()
    if ($null -ne $Round.joined) { $parts += "joined=$($Round.joined)" }
    if ($Round.gateway) { $parts += "gw=$($Round.gateway.state)/c2s=$($Round.gateway.c2s)" }
    if ($Round.counters) {
        foreach ($p in $Round.counters.PSObject.Properties) { $parts += "$($p.Name)=$($p.Value)" }
    }
    return ($parts -join ";")
}

# 游戏打不开才值得重试：服务端没 Done、客户端没写出 ROUND1（没进世界）。
# 进过服的业务 FAIL（applied=0、analyzer、TRACE）重跑没有意义。
function Test-SmokeLaunchFailure {
    param(
        [object]$ScriptExit,
        $ResultObj
    )
    $reason = if ($ResultObj) { [string]$ResultObj.Reason } else { "" }
    if ($reason -match '^(loader_not_supported|mapping_precheck)') {
        return $false
    }
    if ($ScriptExit -eq 3 -or $reason -eq "server_not_ready") {
        return $true
    }
    if (-not $ResultObj) {
        return $true
    }
    return -not [bool]$ResultObj.Round1Stats
}

# 单会话执行函数（封装重试逻辑，供串行路径共用）
# 返回 [PSCustomObject]@{ Ver; Loader; Phase; Result; SessionId; Attempts; Reason }
function Invoke-Session {
    param(
        [string]$Ver,
        [string]$Loader,
        [string]$Phase,
        [string]$Scenario = "classic",
        [int]$ServerPort,
        [int]$MaxRetries,
        [switch]$CleanWorld,
        [int]$ServerReadyTimeoutSec = 180,
        [int]$ClientTimeoutSec = 300,
        [int]$DelayMs = 10000,
        [int]$ReconnectDelayMs = 3000,
        [int]$ClientRenderDistance = 20,
        [string]$SessionSuffix = ""
    )
    $sfx = Get-SmokeSessionIdSuffix -Scenario $Scenario -SessionSuffix $SessionSuffix
    $sessionId = "${Ver}_${Loader}_${Phase}${sfx}"
    $sessionResult = $null
    $attempt = 0
    $lastReason = ""
    $scriptPath = Join-Path $PSScriptRoot "runtime-smoke-test.ps1"

    $maxAttempts = [Math]::Max(1, $MaxRetries)
    while ($attempt -lt $maxAttempts) {
        $attempt++
        $doClean = [bool]$CleanWorld -or ($attempt -gt 1)
        $cleanLabel = if ($doClean) { "CleanWorld" } else { "ReuseWorld" }
        Write-Host "[$sessionId] 尝试 $attempt/$maxAttempts (port=$ServerPort, $cleanLabel)..."

        $sessionArgs = @{
            Ver = $Ver; Loader = $Loader; Phase = $Phase; SessionId = $sessionId
            CleanWorld = $doClean; ServerPort = $ServerPort
            ServerReadyTimeoutSec = $ServerReadyTimeoutSec; ClientTimeoutSec = $ClientTimeoutSec
            DelayMs = $DelayMs; ReconnectDelayMs = $ReconnectDelayMs
            ClientRenderDistance = $ClientRenderDistance
        }
        if ($Scenario -ne "classic") { $sessionArgs.Scenario = $Scenario }
        # 单会话脚本用 Write-Host + exit，不会把 "PASS" 写回管道。
        & $scriptPath @sessionArgs
        $scriptExit = $LASTEXITCODE
        $resultJsonPath = Join-Path $resultsDir "result_${sessionId}.json"
        $resultObj = $null
        $jsonResult = $null
        if (Test-Path $resultJsonPath) {
            try {
                $resultObj = Get-Content $resultJsonPath -Raw | ConvertFrom-Json
                $jsonResult = [string]$resultObj.Result
            } catch {
                $jsonResult = $null
            }
        }
        if ($scriptExit -eq 0 -or $jsonResult -eq "PASS") {
            $sessionResult = "PASS"
            $lastReason = ""
            break
        }

        $sessionResult = "FAIL"
        $lastReason = if ($resultObj -and $resultObj.Reason) {
            [string]$resultObj.Reason
        } elseif ($resultObj) {
            "Round1Pass=$($resultObj.Round1Pass) Round2Pass=$($resultObj.Round2Pass) Exit=$($resultObj.ClientExitCode)"
        } elseif ($null -ne $scriptExit) {
            "smoke_exit=$scriptExit"
        } else {
            "no_result_json"
        }

        $launchFail = Test-SmokeLaunchFailure -ScriptExit $scriptExit -ResultObj $resultObj
        if (-not $launchFail) {
            Write-Host "[$sessionId] 游戏已打开，业务失败不重试: $lastReason" -ForegroundColor Yellow
            break
        }
        if ($attempt -ge $maxAttempts) {
            Write-Host "[$sessionId] 启动失败且已达重试上限: $lastReason" -ForegroundColor Red
            break
        }
        Write-Host "[$sessionId] 启动失败 ($lastReason)，将重试" -ForegroundColor Red
    }

    if ($sessionResult -eq "FAIL") {
        $failLine = "[$sessionId] FAILED after $attempt attempt(s): $lastReason"
        Add-Content -Path $failuresLog -Value $failLine
        Write-Host $failLine -ForegroundColor Red
    }

    # T2 PROBE JSON v1：读取本会话 result JSON 的 Probe 字段，摘要进 CSV 增列
    $probeR1 = ""
    $probeR2 = ""
    $resultJsonPath = Join-Path $resultsDir "result_${sessionId}.json"
    if (Test-Path $resultJsonPath) {
        try {
            $rj = Get-Content $resultJsonPath -Raw | ConvertFrom-Json
            $probeR1 = Format-SmokeProbeRound -Round $rj.Probe.Round1
            $probeR2 = Format-SmokeProbeRound -Round $rj.Probe.Round2
        } catch { }
    }

    return [PSCustomObject]@{
        Ver=$Ver
        Loader=$Loader
        Phase=$Phase
        Scenario=$Scenario
        Result=$sessionResult
        SessionId=$sessionId
        Attempts=$attempt
        Reason=$lastReason
        ProbeR1=$probeR1
        ProbeR2=$probeR2
    }
}

$results = @()
$gradlewPath = Join-Path $projectRoot "gradlew.bat"

foreach ($entry in $scenarioPlan) {
    $scenario = $entry.Scenario
    $ver = $entry.Ver
    $sfx = Get-SmokeSessionIdSuffix -Scenario $scenario -SessionSuffix $SessionSuffix
    Write-Host ""
    Write-Host "============================================"
    $pinnedLoader = [string]$entry.Loader
    $loaderHint = if ($pinnedLoader) { $pinnedLoader } else { $Loaders -join ',' }
    Write-Host "=== Testing: $ver (scenario: $scenario, loaders: $loaderHint)"
    Write-Host "============================================"

    # Forge 仅部分版本有 builds_for（1.20.1、1.21.1、1.21.3+ 等）；其它版本强行跑 :forge:runServer
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
    $requestedLoaders = if ($pinnedLoader) { @($pinnedLoader) } else { $Loaders }
    if ($supportedLoaders.Count -gt 0) {
        # @()：Where-Object 只剩 1 个 loader 时 PowerShell 会拆成标量字符串，
        # `$arr.Count` 变成字符数、`$arr[0]` 变成 'f'，1.20.1 fabric 会变成会话 `1.20.1_f_I`。
        $activeLoadersForVer = @($requestedLoaders | Where-Object { $supportedLoaders -contains $_ })
        $skippedLoaders = @($requestedLoaders | Where-Object { $supportedLoaders -notcontains $_ })
    } else {
        # 没读到 builds_for（极少见，如缺失 properties）：保留原 Loaders，由后续编译失败兜底
        $activeLoadersForVer = @($requestedLoaders)
        $skippedLoaders = @()
    }
    # 端口分配仍按原 -Loaders 顺序取下标，保证 fabric=BasePort, forge/neoforge 按位偏移
    $loaderPortIndex = @{}
    for ($li = 0; $li -lt $Loaders.Count; $li++) { $loaderPortIndex[$Loaders[$li]] = $li }
    foreach ($sk in $skippedLoaders) {
        $skipSessionId = "${ver}_${sk}_${Phase}${sfx}"
        Write-Host "[$skipSessionId] 跳过：versionProperties/${ver}.properties builds_for 不含 ${sk}" -ForegroundColor DarkGray
        $results += [PSCustomObject]@{
            Ver=$ver; Loader=$sk; Phase=$Phase; Scenario=$scenario; Result="SKIP"
            SessionId=$skipSessionId; Attempts=0; Reason="not_in_builds_for"
        }
    }
    if ($activeLoadersForVer.Count -eq 0) {
        Write-Host "[$ver] 无匹配 loader（-Loaders 与 builds_for 无交集），跳过该版本" -ForegroundColor Yellow
        continue
    }

    if ($Parallel -and $activeLoadersForVer.Count -gt 1) {
        # ===== 并行模式：同版本多 loader 用 Start-Process 同时跑 =====
        # 注意：不能用 Start-Job（Job 内 Start-Process gradlew.bat 会静默失败）
        # 改用 Start-Process pwsh.exe -File 启动独立进程（PowerShell 7，utf8NoBOM 写配置必需；
        # Windows PowerShell 5.1 无该编码值且 -Encoding UTF8 带 BOM），各进程内 Start-Process gradlew.bat 正常工作

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
        $activeLoaders = @($activeLoadersForVer | Where-Object { -not $precompileFailed[$_] })
        if ($activeLoaders.Count -eq 0) {
            Write-Host "[$ver] 所有 loader 预编译失败，跳过该版本" -ForegroundColor Red
            foreach ($loader in $activeLoadersForVer) {
                $skipSessionId = "${ver}_${loader}_${Phase}${sfx}"
                $results += [PSCustomObject]@{
                    Ver=$ver; Loader=$loader; Phase=$Phase; Scenario=$scenario; Result="FAIL"
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
            $jobName = "${ver}_${loader}_${Phase}${sfx}"
            Write-Host "[$jobName] 启动进程 (port=$port, ReuseWorld)..."

            $procArgs = @(
                "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", $scriptPath,
                "-Ver", $ver, "-Loader", $loader, "-Phase", $Phase,
                "-SessionId", $jobName,
                "-ServerPort", $port,
                "-ServerReadyTimeoutSec", $ServerReadyTimeoutSec,
                "-ClientTimeoutSec", $ClientTimeoutSec,
                "-ClientRenderDistance", "$ClientRenderDistance"
            )
            if ($scenario -ne "classic") {
                $procArgs += @("-Scenario", $scenario)
            }

            $procOutLog = Join-Path $logDir "parallel_${jobName}.log"
            $procErrLog = Join-Path $logDir "parallel_${jobName}_err.log"
            $proc = Start-Process -FilePath "pwsh.exe" `
                -ArgumentList $procArgs `
                -RedirectStandardOutput $procOutLog `
                -RedirectStandardError $procErrLog `
                -PassThru -WindowStyle Hidden

            $processes += [PSCustomObject]@{ Name=$jobName; Process=$proc; Loader=$loader; Port=$port; OutLog=$procOutLog }

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
                        Ver=$ver; Loader=$p.Loader; Phase=$Phase; Scenario=$scenario; Result=$resultObj.Result
                        SessionId=$sessionId; Attempts=1; Reason=$lastReason
                        ProbeR1=(Format-SmokeProbeRound -Round $resultObj.Probe.Round1)
                        ProbeR2=(Format-SmokeProbeRound -Round $resultObj.Probe.Round2)
                    }
                } catch {
                    $results += [PSCustomObject]@{
                        Ver=$ver; Loader=$p.Loader; Phase=$Phase; Scenario=$scenario; Result="FAIL"
                        SessionId=$sessionId; Attempts=1; Reason="result JSON parse error"
                    }
                }
            } else {
                $results += [PSCustomObject]@{
                    Ver=$ver; Loader=$p.Loader; Phase=$Phase; Scenario=$scenario; Result="FAIL"
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
    } else {
        # ===== 串行模式（默认）=====
        foreach ($loader in $activeLoadersForVer) {
            $sessionId = "${ver}_${loader}_${Phase}${sfx}"
            Write-Host ""
            Write-Host "--- $sessionId (ReuseWorld) ---"

            $r = Invoke-Session -Ver $ver -Loader $loader -Phase $Phase -Scenario $scenario -ServerPort $BasePort -MaxRetries $MaxRetries `
                -ServerReadyTimeoutSec $ServerReadyTimeoutSec -ClientTimeoutSec $ClientTimeoutSec `
                -DelayMs $DelayMs -ReconnectDelayMs $ReconnectDelayMs -ClientRenderDistance $ClientRenderDistance -SessionSuffix $SessionSuffix
            $results += $r

            # 杀残留 Minecraft java 进程（仅本工程 loom dev 实例：dli.config + env 标记；
            # 不杀 gradle daemon，不误杀其他项目/会话）
            $rootEsc = [regex]::Escape($projectRoot)
            $dliConfig = "-Dfabric\.dli\.config=$rootEsc"
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -eq "java.exe" -and $_.CommandLine -and $_.CommandLine -match $dliConfig -and
                $_.CommandLine -match "-Dfabric\.dli\.env=(server|client)"
            } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

            # 不调 gradlew --stop：全局停 daemon 会误杀并行会话/其他项目的构建（loom 锁问题由 --no-daemon 规避）
        }
    }
}

# 并行模式收尾：不再统一 gradlew --stop——全局停 daemon 会误杀其他项目/会话的构建；
# 残留 daemon 由后续构建自然复用。

# 最终汇总
Write-Host ""
Write-Host "=== BATCH SUMMARY ($Phase, parallel=$Parallel) ===" -ForegroundColor Cyan
$results | Format-Table Scenario,Ver,Loader,Result,Attempts -AutoSize
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
