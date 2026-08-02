# 运行时冒烟测试 — 单次会话脚本（两轮连服版）
# 用法: .\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
#       .\scripts\runtime-smoke-test.ps1 -Ver 1.20.6 -Loader forge -Phase I -SessionId "1.20.6_forge_I"   # forge 支持范围见 versionProperties/{Ver}.properties 的 builds_for
# 流程: 服务端启动 VD=20 → 客户端连服 → 进世界后等 DelayMs → ROUND1 统计 → 主动断开 → 服务端切 VD=8 → 等 ReconnectDelayMs → 重连 → 进世界后等 DelayMs → ROUND2 统计 → 退出
# 关键真相源：Loom runDir 在子项目目录下（fabric/run/client、neoforge/run/server 等），不是根目录 run/
# 退出码: 0=PASS / 2=FAIL / 3=server_not_ready
# 端口: 默认 25565；并行模式由 batch 脚本传 -ServerPort 25566 等避免冲突
# 向后兼容: -SmokeHost 仍可用，但 -ServerPort 优先（若同时指定 -SmokeHost 则 -SmokeHost 完整地址优先）
param(
    [Parameter(Mandatory=$true)][string]$Ver,
    [Parameter(Mandatory=$true)][ValidateSet("fabric","forge","neoforge")][string]$Loader,
    [Parameter(Mandatory=$true)][ValidateSet("I","R","UdpFailover")][string]$Phase,
    [Parameter(Mandatory=$true)][string]$SessionId,
    [switch]$CleanWorld,
    [string]$SmokeHost = "",
    [int]$ServerPort = 25565,
    [int]$DelayMs = 15000,
    [int]$ReconnectDelayMs = 3000,
    [int]$ServerReadyTimeoutSec = 160,
    [int]$ClientTimeoutSec = 240,
    [string]$SmokePhases = "classic",
    # -Phase UdpFailover：经 Nginx stream 代理 TCP 主控；UDP 仍直连 server。
    # 默认 nginx 1.31.3 在 D:\app；行内可传 -NginxExePath 覆盖。$ProxyPort=0 时
    # 由 phase 触发自动采用 $ServerPort+5，避免与 server 端口冲突。
    [string]$NginxExePath = "D:\app\nginx-1.31.3\nginx.exe",
    [int]$ProxyPort = 0,
    # -DryRun：UdpFailover phase 时只起 nginx + 验 listen，跳过 server/client；
    # 用于 Pester 验证 harness 启停序列而不起游戏。其他 phase 下 -DryRun 无效。
    [switch]$DryRun,
    # -InjectTcpClose：UdpFailover phase 时在 Round1 之后通过 nginx -s quit 注入
    # 真实 TCP close 触发 client channelInactive→orchestrator，验证 production 在
    # 真实 RST 下仍工作。默认 false：plan §2.3 走 client 内部模拟 disconnect，
    # production onPrimaryDisconnected 由 ClientSmokeTest.disconnect 间接触发。
    [switch]$InjectTcpClose,
    # -SeamlessMode：客户端 recoveryFreeze=false 跑无感切换冒烟（世界不冻结、无切换 UI、
    # 恢复窗口吞 C2S）。与 -Phase UdpFailover 组合验证无感恢复链路；§6 前 patch 客户端
    # hassium-client.toml（追加 [network.dataPlane] 段），客户端退出后立即恢复默认（true）。
    [switch]$SeamlessMode
)

$ErrorActionPreference = "Continue"

# 解析最终连服地址：若显式指定 -SmokeHost 则优先；否则用 127.0.0.1:$ServerPort
if ($SmokeHost -and $SmokeHost -ne "") {
    $effectiveHost = $SmokeHost
} else {
    $effectiveHost = "127.0.0.1:$ServerPort"
}

# §2.3 -Phase UdpFailover：未显式 -SmokePhases 时强制 udp-failover。udp-failover 经典两轮
# classic 状态机驱动断开→重连 cycle，但比 classic 多出 recovery 窗口（最多 60s），故
# 默认 ClientTimeoutSec 不够时由 phase 触发扩容。
if ($Phase -eq "UdpFailover") {
    if ($SmokePhases -eq "classic") { $SmokePhases = "udp-failover" }
    if ($ClientTimeoutSec -lt 300) { $ClientTimeoutSec = 300 }
}

# §3.1 -Phase UdpFailover：经 Nginx stream 代理 TCP 主控；UDP 仍直连 server。
# 当 ProxyPort 未显式指定时默认 $ServerPort + 5；避免与本会话 Minecraft server 端口冲突。
# -SmokeHost 若显式给出优先被使用；仅在 -Phase UdpFailover 且 -SmokeHost 空时由 nginx
# ProxyPort 替代 effectiveHost。classic / R / I phases 不受影响。
if ($Phase -eq "UdpFailover") {
    if ($ProxyPort -eq 0) { $ProxyPort = $ServerPort + 5 }
    if (-not ($SmokeHost -and $SmokeHost -ne "")) {
        $effectiveHost = "127.0.0.1:$ProxyPort"
    }
}

# 路径自推导（脚本位于 <repo>/scripts/，项目根是父目录）
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot "build\smoke-test"
$logDir = Join-Path $logRoot "logs"
$statsDir = Join-Path $logRoot "stats"
$resultsDir = Join-Path $logRoot "results"

$serverLog = Join-Path $logDir "server_${SessionId}.log"
$serverErr = Join-Path $logDir "server_${SessionId}_err.log"
$clientLog = Join-Path $logDir "client_${SessionId}.log"
$clientErr = Join-Path $logDir "client_${SessionId}_err.log"

# Loom runDir 在子项目目录下（fabric/run/client、neoforge/run/server 等）
$loaderRunDir = Join-Path $projectRoot "$Loader\run"
$clientRunDir = Join-Path $loaderRunDir "client"
$serverRunDir = Join-Path $loaderRunDir "server"

# Loader × MC 版本支持校验：真相源是 versionProperties/{Ver}.properties 的 builds_for
# （settings.gradle 从这里 include 模块）。提前报错，避免 gradle "project not found" 模糊失败。
$versionProps = Join-Path $projectRoot "versionProperties\${Ver}.properties"
$supportedLoaders = @()
if (Test-Path $versionProps) {
    $line = Get-Content $versionProps | Where-Object { $_ -match '^\s*builds_for\s*=' } | Select-Object -First 1
    if ($line) {
        $supportedLoaders = (($line -split '=', 2)[1].Trim()) -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
    }
}
if ($supportedLoaders.Count -gt 0 -and ($supportedLoaders -notcontains $Loader)) {
    $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = $Phase; Result = "FAIL"; Reason = "loader_not_supported"; SupportedLoaders = ($supportedLoaders -join ","); ClientExitCode = -1 }
    $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
    Write-Host "[$SessionId] === RESULT: FAIL === $Loader 不支持 MC $Ver（builds_for=$($supportedLoaders -join ',' )）；可用: $($supportedLoaders -join ' / ')"
    exit 2
}

# 确保输出目录存在
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $statsDir | Out-Null
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

Set-Location $projectRoot

# 仅清理本会话相关的 java 进程，避免在并行模式下误杀另一会话。
# 服务端通过端口定位；客户端/兜底服务端通过 $Loader\run\{client,server} 目录在命令行中的出现来定位。
# 不会杀掉 gradle daemon（其命令行不含 run/server 或 run/client）。
function Stop-SessionJava {
    param(
        [int]$ServerPort,
        [string]$Loader
    )

    # 1. 通过端口定位服务端 java 进程（最精确）
    if ($ServerPort -gt 0) {
        $serverConns = Get-NetTCPConnection -LocalPort $ServerPort -ErrorAction SilentlyContinue
        if ($serverConns) {
            $serverConns | ForEach-Object {
                Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
            }
            Start-Sleep -Milliseconds 500
        }
    }

    # 2. 通过 run 目录定位本 loader 的客户端/服务端 java 进程（兜底）
    # 匹配命令行中包含 "<loader>\run\client" 或 "<loader>\run\server" 的 java 进程
    $clientPattern = "$([regex]::Escape($Loader))[\\/]+run[\\/]+client"
    $serverPattern = "$([regex]::Escape($Loader))[\\/]+run[\\/]+server"
    $sessionJava = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -eq "java.exe" -and (
            ($_.CommandLine -and $_.CommandLine -match $clientPattern) -or
            ($_.CommandLine -and $_.CommandLine -match $serverPattern)
        )
    }
    foreach ($proc in $sessionJava) {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

# Nginx helpers for UdpFailover phase. Module lives under scripts/smoke/.
# Logged via HassiumSmokeTest:UDP_FAILOVER_HARNESS <event> at=<unix-ms> lines so the
# reviewer can correlate harness-driven Nginx reloads with production markers.
$smokeModulePath = Join-Path $PSScriptRoot "smoke\UdpFailoverSmoke.psm1"
if ($Phase -eq "UdpFailover" -and (Test-Path $smokeModulePath)) {
    Import-Module -Name $smokeModulePath -Force -ErrorAction Stop
}

# Per-session Nginx prefix/work dirs (kept under build/smoke-test/nginx/<SessionId>/).
function Get-FailoverNginxDirs {
    param([string]$SessId)
    $base = Join-Path $logRoot "nginx\$SessId"
    New-Item -ItemType Directory -Force -Path $base | Out-Null
    $conf    = Join-Path $base "nginx.conf"
    $prefix  = Join-Path $base "prefix"
    $logFile = Join-Path $base "harness_timeline.log"
    New-Item -ItemType Directory -Force -Path $prefix  | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $prefix "logs") | Out-Null
    return [pscustomobject]@{ Conf = $conf; Prefix = $prefix; LogFile = $logFile }
}

# Write a harness timeline line for later Get-UdpFailoverHarnessTimeline parsing.
function Write-HarnessEvent {
    param([Parameter(Mandatory=$true)][string]$Event, [Parameter(Mandatory=$true)][string]$LogFile)
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $line = "HassiumSmokeTest:UDP_FAILOVER_HARNESS $Event at=$now"
    Add-Content -Path $LogFile -Value $line -ErrorAction SilentlyContinue
    Write-Host "[$SessionId] $line"
}

# Write the nginx stream config from the helper module and launch nginx.exe with
# `-c <conf> -p <prefix>`. Spawn-only; subsequent Wait-NginxListen validates listen.
function Start-FailoverNginxProxy {
    param(
        [Parameter(Mandatory=$true)][string]$NginxExe,
        [Parameter(Mandatory=$true)][int]$ListenPort,
        [Parameter(Mandatory=$true)][int]$PrimaryPort,
        [Parameter(Mandatory=$true)][string]$ConfPath,
        [Parameter(Mandatory=$true)][string]$PrefixPath
    )
    if (-not (Test-Path $NginxExe)) { throw "NginxExe not found: $NginxExe" }
    $conf = New-UdpFailoverNginxConfig -ListenPort $ListenPort -PrimaryPort $PrimaryPort
    Set-Content -Path $ConfPath -Value $conf -Encoding ASCII
    # Start nginx detached (Windows nginx runs foreground by default, so use
    # Start-Process with -WindowStyle Hidden to return immediately).
    Start-Process -FilePath $NginxExe `
        -ArgumentList @("-c", $ConfPath, "-p", $PrefixPath) `
        -WindowStyle Hidden -PassThru -ErrorAction Stop | Out-Null
}
# Wait up to $TimeoutSec for the nginx stream listen port to accept TCP.
function Wait-NginxListen {
    param(
        [Parameter(Mandatory=$true)][int]$ListenPort,
        [int]$TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $conn = Get-NetTCPConnection -LocalPort $ListenPort -State Listen -ErrorAction SilentlyContinue
        if ($conn) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

# Stop the nginx master bound to $PrefixPath. `-s quit` lets in-flight stream
# connections finish; `-s stop` drops them. Use quit by default (graceful).
function Stop-FailoverNginxProxy {
    param(
        [Parameter(Mandatory=$true)][string]$NginxExe,
        [Parameter(Mandatory=$true)][string]$ConfPath,
        [Parameter(Mandatory=$true)][string]$PrefixPath,
        [string]$Signal = "quit"
    )
    if (-not (Test-Path $NginxExe)) { return }
    try { & $NginxExe -s $Signal -c $ConfPath -p $PrefixPath 2>&1 | Out-Null } catch { }
    Start-Sleep -Milliseconds 500
    # 兜底：若 nginx master 已死但 worker 进程残留（其 PID 不在 prefix 里），按名强杀。
    # 在 smoke harness 内本会话独立 prefix/独立 conf，全命名杀是安全的。
    try { Stop-Process -Name "nginx" -Force -ErrorAction SilentlyContinue } catch { }
    Start-Sleep -Milliseconds 200
}

# 1. 清理客户端缓存（整个 hassium_cache 目录 + config/hassium 整个目录 + crash-reports）
Write-Host "[$SessionId] [1/9] 清理客户端缓存 ($Loader/run/client/)..."
Remove-Item -Recurse -Force (Join-Path $clientRunDir "hassium_cache") -ErrorAction SilentlyContinue
# Remove-Item -Recurse -Force (Join-Path $clientRunDir "config\hassium") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $clientRunDir "crash-reports") -ErrorAction SilentlyContinue

# 2. 配置服务端（view-distance 由 ServerSmokeTest 控制，这里设基础值）
Write-Host "[$SessionId] [2/9] 配置服务端 ($Loader/run/server/)..."
New-Item -ItemType Directory -Force -Path $serverRunDir -ErrorAction SilentlyContinue | Out-Null
Set-Content -Path (Join-Path $serverRunDir "eula.txt") -Value "eula=true" -NoNewline
$props = @"
server-port=$ServerPort
view-distance=16
online-mode=false
gamemode=creative
level-type=minecraft\:normal
motd=Hassium Smoke Test
max-players=20
white-list=false
enforce-whitelist=false
spawn-protection=0
"@
Set-Content -Path (Join-Path $serverRunDir "server.properties") -Value $props

# 创建 world\serverconfig 目录（部分 neoforge / forge 50 版本不会自动创建）
New-Item -ItemType Directory -Force -Path (Join-Path $serverRunDir "world\serverconfig") -ErrorAction SilentlyContinue | Out-Null
# NeoForge 21.5+ 与 Forge 50 (1.20.6) 都经 FML ConfigTracker.writeConfig 写 world/serverconfig/hassium/；
# 残留 *.toml.bak 时会把整套 parent 路径重复拼到 tmp 文件名前，导致 java.nio.file.NoSuchFileException
# (\.\world\serverconfig\hassium\.\world\serverconfig\hassium\hassium-server.new.tmp.toml)。
# server 启动前清空 Hassium own serverconfig 残留，让 ConfigTracker 从干净状态写入。
# Forge 1.20.1 用老 ForgeConfigSpec，serverconfig 路径不同且无此 bug，不在此分支清理。
$needConfigTrackerClean = ($Loader -eq "neoforge") -or ($Loader -eq "forge" -and $Ver -ge "1.20.6")
if ($needConfigTrackerClean) {
    $hassiumServerConfig = Join-Path $serverRunDir "world\serverconfig\hassium"
    if (Test-Path $hassiumServerConfig) {
        Get-ChildItem -Path $hassiumServerConfig -File -Filter "*.toml*" -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "[$SessionId] 清理 $Loader world/serverconfig/hassium 残留 toml（绕过 FML ConfigTracker 路径拼接 bug）"
    }
}

# 3. 清理存档（batch：loader 首轮 / 退版本 / 失败重试 会传 -CleanWorld；单会话默认不清理）
if ($CleanWorld) {
    Write-Host "[$SessionId] [3/9] 清理服务端存档 ($Loader/run/server/world/)..."
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world_nether") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world_the_end") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "cache") -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $serverRunDir "world\serverconfig") -ErrorAction SilentlyContinue | Out-Null
} else {
    Write-Host "[$SessionId] [3/9] 跳过存档清理（复用已有 world）"
}

# 4. 释放 $ServerPort 端口 + UdpFailover phase 时也释放 $ProxyPort (可能被上次会话残留 nginx 占用)
$portsToFree = @($ServerPort)
if ($Phase -eq "UdpFailover" -and $ProxyPort -gt 0) { $portsToFree += $ProxyPort }
foreach ($p in $portsToFree) {
    $conns = Get-NetTCPConnection -LocalPort $p -ErrorAction SilentlyContinue
    if ($conns) {
        Write-Host "[$SessionId] 端口 $p 占用，尝试释放..."
        $conns | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
}

# §3.2 UdpFailover phase：在 §5 server 启动前先起 nginx stream proxy。
# UDP 数据面仍直连 server。client 连的就是 effectiveHost=127.0.0.1:$ProxyPort。
$nginxDirHandle = $null
$nginxReady = $false
if ($Phase -eq "UdpFailover") {
    if (-not (Test-Path $NginxExePath)) {
        Write-Host "[$SessionId] NginxExePath 不存在: $NginxExePath；UdpFailover phase 缺不可降级，标记 FAIL"
        $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = $Phase; Result = "FAIL"; Reason = "nginx_exe_missing"; ClientExitCode = -1; UdpFailoverCorePass = $false }
        $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
        exit 4
    }
    $nginxDirHandle = Get-FailoverNginxDirs -SessId $SessionId
    Write-Host "[$SessionId] [4.5/9] 启动 nginx stream proxy listen=127.0.0.1:$ProxyPort upstream=127.0.0.1:$ServerPort..."
    try {
        Start-FailoverNginxProxy -NginxExe $NginxExePath -ListenPort $ProxyPort -PrimaryPort $ServerPort `
                                 -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix
        $nginxReady = Wait-NginxListen -ListenPort $ProxyPort -TimeoutSec 15
        Write-HarnessEvent -Event "nginxStarted" -LogFile $nginxDirHandle.LogFile
    } catch {
        Write-Host "[$SessionId] nginx 启动失败: $($_.Exception.Message)"
    }
    if (-not $nginxReady) {
        Write-Host "[$SessionId] nginx listen 未就绪；标记 FAIL"
        if ($nginxDirHandle) { Stop-FailoverNginxProxy -NginxExe $NginxExePath -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix -Signal stop }
        $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = $Phase; Result = "FAIL"; Reason = "nginx_listen_failed"; ClientExitCode = -1; UdpFailoverCorePass = $false }
        $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
        exit 5
    }
    Write-Host "[$SessionId] nginx 已 listen on 127.0.0.1:$ProxyPort"

    # -DryRun：仅起 nginx + 验 listen + stop + exit；不起 server/client。
    if ($DryRun) {
        Write-Host "[$SessionId] -DryRun：nginx 启停序列已验证；不起 server/client。"
        Stop-FailoverNginxProxy -NginxExe $NginxExePath -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix -Signal stop
        $resultObj = @{
            SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = $Phase; Result = "PASS"
            DryRun = $true; NginxProxyPort = $ProxyPort; NginxListenReady = $true
            HarnessTimeline = (Get-UdpFailoverHarnessTimeline -HarnessLog $nginxDirHandle.LogFile)
        }
        $resultObj | ConvertTo-Json -Depth 4 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
        Write-Host "[$SessionId] === RESULT: PASS (DryRun) ==="
        exit 0
    }
}
# 5. 启动服务端（后台，启用 ServerSmokeTest）
$gradlew = Join-Path $projectRoot "gradlew.bat"
# 5.0 预备 gradle daemon：先停掉残留 daemon，避免 reuse 一个卡住 / 正在 init 的旧 daemon
# （残留 daemon 经常卡 wrapper，runServer/runClient 反复 wating ready 直至超时）。
& $gradlew --stop *> $null 2>&1
# 5.1 再起服务端 —— 显式 --no-daemon 确保不复用任何 daemon
Write-Host "[$SessionId] [4/9] 启动服务端 ($Loader / $Ver)..."
$serverArgs = @("--no-daemon", ":${Loader}:runServer", "-PhassiumSmokeTest=true", "-PhassiumSmokePhases=${SmokePhases}", "-Pmc_ver=${Ver}")
$server = Start-Process -FilePath $gradlew `
    -ArgumentList $serverArgs `
    -RedirectStandardOutput $serverLog `
    -RedirectStandardError $serverErr `
    -PassThru -WindowStyle Hidden

# 6. 等待服务端就绪（Done! 行出现）
Write-Host "[$SessionId] [5/9] 等待服务端 Done! (超时 ${ServerReadyTimeoutSec}s)..."
$deadline = (Get-Date).AddSeconds($ServerReadyTimeoutSec)
$serverReady = $false
while ((Get-Date) -lt $deadline) {
    if (-not $server.HasExited) {
        if (Test-Path $serverLog) {
            if (Select-String -Path $serverLog -Pattern 'Done \(' -Quiet -ErrorAction SilentlyContinue) {
                $serverReady = $true
                break
            }
        }
    } else {
        Write-Host "[$SessionId] 服务端进程提前退出，退出码: $($server.ExitCode)"
        break
    }
    Start-Sleep -Seconds 3
}

if (-not $serverReady) {
    Write-Host "[$SessionId] 服务端未就绪，标记失败 (exit 3)"
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Stop-SessionJava -ServerPort $ServerPort -Loader $Loader
    $resultObj = @{
        SessionId = $SessionId
        Ver = $Ver
        Loader = $Loader
        Phase = $Phase
        Result = "FAIL"
        Reason = "server_not_ready"
        Round1Stats = $false
        Round1Pass = $false
        Round2Stats = $false
        Round2Pass = $false
        ServerSwitched = $false
        ClientExitCode = -1
    }
    $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
    exit 3
}


# 6.5 -SeamlessMode：客户端 recoveryFreeze=false（无感切换冒烟）
# 客户端 toml 由各加载器 run 目录共享；追加 [network.dataPlane] 段（TOML 表格位置无关），
# 带 #SeamlessMode-smoke-injected 标记供退出后精确回滚（不触碰用户真实配置段）。
$seamlessClientToml = Join-Path $clientRunDir "config\hassium\hassium-client.toml"
if ($SeamlessMode) {
    if (Test-Path $seamlessClientToml) {
        # PS5.1 的 -Encoding UTF8 会写 BOM，tomlj 解析会失败 → 用无 BOM UTF-8 追加
        $seamlessBlock = @(
            "",
            "[network.dataPlane]",
            "`t#主控恢复时定格画面（false=无感切换；SeamlessMode 冒烟注入）",
            "`trecoveryFreeze = false",
            "`t#SeamlessMode-smoke-injected",
            ""
        ) -join [Environment]::NewLine
        [System.IO.File]::AppendAllText($seamlessClientToml, $seamlessBlock,
            (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "[$SessionId] SeamlessMode: 客户端 hassium-client.toml 已设 recoveryFreeze=false"
    } else {
        Write-Host "[$SessionId] [WARN] SeamlessMode: 找不到 $seamlessClientToml，跳过 patch"
    }
}

# 7. 启动客户端（前台阻塞，自动两轮连服）
Write-Host "[$SessionId] [6/9] 启动客户端连服（两轮自动）..."
$clientArgs = @(
    "--no-daemon",
    ":${Loader}:runClient",
    "-PhassiumSmokeTest=true",
    "-PhassiumSmokeHost=$effectiveHost",
    "-PhassiumSmokeDelayMs=$DelayMs",
    "-PhassiumSmokeReconnectDelayMs=$ReconnectDelayMs",
    "-PhassiumSmokePhases=$SmokePhases",
    "-Pmc_ver=${Ver}"
)
$clientProc = Start-Process -FilePath $gradlew `
    -ArgumentList $clientArgs `
    -RedirectStandardOutput $clientLog `
    -RedirectStandardError $clientErr `
    -PassThru -WindowStyle Hidden

# 8. 等待客户端退出（最长 ClientTimeoutSec 秒）
Write-Host "[$SessionId] [7/9] 等待客户端退出 (超时 ${ClientTimeoutSec}s)..."
$clientDeadline = (Get-Date).AddSeconds($ClientTimeoutSec)
# -InjectTcpClose：仅 UdpFailover + nginx 已就绪时使用。
# 在客户进入 Round1 后等待约 DelayMs*1.2 + 5s（保证进世界稳定），再 nginx -s quit
# 真实关闭 client 已建立的 stream 连接 → 触发 channelInactive → orchestrator →
# FAILOVER_PERMIT/RECONNECT marker；重启 nginx 等待 listen 重开后再继续等待 client。
# 默认 false 时 harness 不触发外部断链，由 ClientSmokeTest 内部 disconnect 与 vanilla
# 重连流程触发（plan §2.3 已说明：mono-JVM 不可真断 socket，内部模拟即真实断开语义）。
$tcpCloseInjected = $false
while (-not $clientProc.HasExited -and (Get-Date) -lt $clientDeadline) {
    if ($Phase -eq "UdpFailover" -and $InjectTcpClose -and $nginxReady -and -not $tcpCloseInjected) {
        $injectAtMs = ($DelayMs * 1.2 + 5000)
        $waitDeadline = (Get-Date).AddMilliseconds($injectAtMs)
        while (-not $clientProc.HasExited -and (Get-Date) -lt $waitDeadline -and (Get-Date) -lt $clientDeadline) {
            Start-Sleep -Milliseconds 500
        }
        if ($clientProc.HasExited) { break }
        Write-Host "[$SessionId] InjectTcpClose: nginx -s quit 注入主控 TCP 关闭"
        Write-HarnessEvent -Event "primaryCloseInjected" -LogFile $nginxDirHandle.LogFile
        Stop-FailoverNginxProxy -NginxExe $NginxExePath -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix -Signal stop
        Start-Sleep -Milliseconds 500
        Write-HarnessEvent -Event "nginxQuit" -LogFile $nginxDirHandle.LogFile
        # 重启 nginx（同一 conf 仍指向 primary）
        Start-FailoverNginxProxy -NginxExe $NginxExePath -ListenPort $ProxyPort -PrimaryPort $ServerPort `
                                 -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix
        $restartReady = Wait-NginxListen -ListenPort $ProxyPort -TimeoutSec 15
        if ($restartReady) {
            Write-HarnessEvent -Event "primaryRestored" -LogFile $nginxDirHandle.LogFile
            Write-Host "[$SessionId] nginx 已恢复 listen on 127.0.0.1:$ProxyPort"
        } else {
            Write-Host "[$SessionId] 注：nginx 重启后 listen 未及时就绪；client 可能 round2 重连失败"
        }
        $tcpCloseInjected = $true
    }
    Start-Sleep -Seconds 5
}
if (-not $clientProc.HasExited) {
    Write-Host "[$SessionId] 客户端超时未退出，强制结束"
    Stop-Process -Id $clientProc.Id -Force -ErrorAction SilentlyContinue
}
$clientExit = if ($clientProc.ExitCode) { $clientProc.ExitCode } else { 0 }

# §7.5 -SeamlessMode 回滚：恢复客户端 toml（移除注入段），任何客户端退出路径都经过这里。
if ($SeamlessMode -and (Test-Path $seamlessClientToml)) {
    $seamlessLines = @(Get-Content $seamlessClientToml)
    $injectIdx = -1
    for ($i = 0; $i -lt $seamlessLines.Count; $i++) {
        if ($seamlessLines[$i] -match '^\[network\.dataPlane\]$') {
            $followEnd = [Math]::Min($seamlessLines.Count - 1, $i + 3)
            if (($seamlessLines[($i + 1)..$followEnd] -join "`n") -match "SeamlessMode-smoke-injected") {
                $injectIdx = $i
                break
            }
        }
    }
    if ($injectIdx -ge 0) {
        $trimmed = @($seamlessLines[0..($injectIdx - 1)])
        while ($trimmed.Count -gt 1 -and [string]::IsNullOrWhiteSpace($trimmed[$trimmed.Count - 1])) {
            $trimmed = @($trimmed[0..($trimmed.Count - 2)])
        }
        [System.IO.File]::WriteAllText($seamlessClientToml, ($trimmed -join [Environment]::NewLine) + [Environment]::NewLine,
            (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "[$SessionId] SeamlessMode: 客户端 hassium-client.toml 已恢复（移除注入段）"
    } else {
        Write-Host "[$SessionId] [WARN] SeamlessMode: 未在 toml 尾部找到注入段，跳过回滚"
    }
}

# 9. 解析结果 + 提取统计
Write-Host "[$SessionId] [8/9] 解析结果 (客户端退出码: $clientExit)..."
$clientContent = if (Test-Path $clientLog) { Get-Content $clientLog -Raw } else { "" }

# 提取 ROUND1 统计（begin 到 end 之间的行）
$round1Match = [regex]::Match($clientContent, "HassiumSmokeTest:CLIENT_STATS ROUND1 begin(.+?)HassiumSmokeTest:CLIENT_STATS ROUND1 end", [System.Text.RegularExpressions.RegexOptions]::Singleline)
if ($round1Match.Success) {
    $round1Stats = $round1Match.Groups[1].Value.Trim()
    $round1Stats | Out-File (Join-Path $statsDir "${SessionId}_round1_VD20.txt") -Encoding UTF8
    Write-Host "[$SessionId] ROUND1 统计已保存到 stats/${SessionId}_round1_VD20.txt"
}

# 提取 ROUND2 统计
$round2Match = [regex]::Match($clientContent, "HassiumSmokeTest:CLIENT_STATS ROUND2 begin(.+?)HassiumSmokeTest:CLIENT_STATS ROUND2 end", [System.Text.RegularExpressions.RegexOptions]::Singleline)
if ($round2Match.Success) {
    $round2Stats = $round2Match.Groups[1].Value.Trim()
    $round2Stats | Out-File (Join-Path $statsDir "${SessionId}_round2_VD8.txt") -Encoding UTF8
    Write-Host "[$SessionId] ROUND2 统计已保存到 stats/${SessionId}_round2_VD8.txt"
}

# 提取服务端视距切换日志
if (Test-Path $serverLog) {
    $serverSwitchLog = Get-Content $serverLog -Raw
    $serverSwitchMatch = [regex]::Match($serverSwitchLog, "(HassiumSmokeTest:SERVER.+)")
    if ($serverSwitchMatch.Success) {
        $serverSwitchMatch.Groups[1].Value | Out-File (Join-Path $statsDir "${SessionId}_server.txt") -Encoding UTF8
    }
}

# 检查两轮统计
$round1StatsFound = $round1Match.Success
$round1Pass = $clientContent -match "ROUND1 stats OK"
$round2StatsFound = $round2Match.Success
$round2Pass = $clientContent -match "ROUND2 stats OK"
$hasPass = $clientContent -match "HassiumSmokeTest:PASS"
$hasFail = $clientContent -match "HassiumSmokeTest:FAIL"

# 服务端视距切换检查
$serverSwitched = if (Test-Path $serverLog) {
    (Get-Content $serverLog -Raw) -match "view-distance switched to 8"
} else { $false }

# §2.3 UDP_FAILOVER marker 提取（聚合 server/client 双端日志，跨进程替代直接断主控 TCP）
$udpFailoverIsPhase = ($Phase -eq "UdpFailover")
$serverContentForUdp = if ($udpFailoverIsPhase -and (Test-Path $serverLog)) {
    Get-Content $serverLog -Raw
} else { "" }
$udpFailoverMarkers = @(
    "UDP_BIND_OK",
    "UDP_WRR_OK",
    "FAILOVER_PERMIT_OK",
    "FAILOVER_RECONNECT_OK",
    "FAILOVER_TERMINAL_OK",
    "CACHE_RESUME_HIT"
)
$udpFailoverFound = @{}
foreach ($m in $udpFailoverMarkers) {
    $pat = "HassiumSmokeTest:UDP_FAILOVER\s+$m"
    $hitClient = $clientContent -match $pat
    $hitServer = $serverContentForUdp -match $pat
    $udpFailoverFound[$m] = ($hitClient -or $hitServer)
}
# 关键 PASS markers：UDP_BIND_OK + CACHE_RESUME_HIT 须同时出现（数据面建立且缓存命中）
$udpFailoverCorePass = $udpFailoverFound["UDP_BIND_OK"] -and $udpFailoverFound["CACHE_RESUME_HIT"]
# 恢复表现模式证据：客户端实际 recoveryFreeze 值（-SeamlessMode 时必须为 false，
# 否则 toml patch 失效 → 无感链路未被验证 → FAIL）。函数来自 UdpFailoverSmoke 模块，
# 仅 UdpFailover 阶段导入，其它阶段直接置 unknown。
$clientRecoveryFreeze = if ($udpFailoverIsPhase) {
    Get-UdpFailoverClientMode -ClientLog $clientContent
} else { "unknown" }
$seamlessModeVerified = (-not $SeamlessMode) -or ($clientRecoveryFreeze -eq "false")
# Pass 决策：udp-failover 阶段不要求 client 两轮 PASS（仅要求关键 markers + client 退出 0）。
# classic / R / I 阶段沿袭原 hasPass+exit==0 逻辑。

if ($udpFailoverIsPhase) {
    $result = if ($udpFailoverCorePass -and $clientExit -eq 0 -and $seamlessModeVerified) { "PASS" } else { "FAIL" }
} else {
    $result = if ($hasPass -and $clientExit -eq 0) { "PASS" } else { "FAIL" }
}

# 清理 nginx stream proxy（仅 UdpFailover phase 启动过）
if ($Phase -eq "UdpFailover" -and $nginxDirHandle) {
    Write-Host "[$SessionId] 停止 nginx stream proxy..."
    Stop-FailoverNginxProxy -NginxExe $NginxExePath -ConfPath $nginxDirHandle.Conf -PrefixPath $nginxDirHandle.Prefix -Signal stop
}

# 10. 停止服务端 + 残留 java
Write-Host "[$SessionId] [9/9] 停止服务端..."
if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
if (-not $clientProc.HasExited) { Stop-Process -Id $clientProc.Id -Force -ErrorAction SilentlyContinue }
# 仅杀本会话相关的 java 进程（通过端口和 run 目录定位），避免影响并行会话
Stop-SessionJava -ServerPort $ServerPort -Loader $Loader

# 输出结果 JSON
$resultObj = @{
    SessionId = $SessionId
    Ver = $Ver
    Loader = $Loader
    Phase = $Phase
    Result = $result
    ClientExitCode = $clientExit
    Round1Stats = $round1StatsFound
    Round1Pass = $round1Pass
    Round2Stats = $round2StatsFound
    Round2Pass = $round2Pass
    ServerSwitched = $serverSwitched
    HasPass = $hasPass
    HasFail = $hasFail
    UdpFailoverMarkers = $udpFailoverFound
    UdpFailoverCorePass = $udpFailoverCorePass
    SeamlessMode = $SeamlessMode
    ClientRecoveryFreeze = $clientRecoveryFreeze
    StatsFiles = @(
        if ($round1StatsFound) { "build/smoke-test/stats/${SessionId}_round1_VD20.txt" }
        if ($round2StatsFound) { "build/smoke-test/stats/${SessionId}_round2_VD8.txt" }
    )
}
$resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")

Write-Host "[$SessionId] === RESULT: $result ==="
Write-Host "[$SessionId] Round1: stats=$round1StatsFound pass=$round1Pass"
Write-Host "[$SessionId] Round2: stats=$round2StatsFound pass=$round2Pass"
Write-Host "[$SessionId] ServerSwitched: $serverSwitched Exit: $clientExit"
if ($udpFailoverIsPhase) {
    Write-Host "[$SessionId] UdpFailover markers: UDP_BIND_OK=$($udpFailoverFound['UDP_BIND_OK']) UDP_WRR_OK=$($udpFailoverFound['UDP_WRR_OK']) FAILOVER_PERMIT_OK=$($udpFailoverFound['FAILOVER_PERMIT_OK']) FAILOVER_RECONNECT_OK=$($udpFailoverFound['FAILOVER_RECONNECT_OK']) FAILOVER_TERMINAL_OK=$($udpFailoverFound['FAILOVER_TERMINAL_OK']) CACHE_RESUME_HIT=$($udpFailoverFound['CACHE_RESUME_HIT'])"
}
Write-Host "[$SessionId] Client recoveryFreeze=$clientRecoveryFreeze (SeamlessMode=$SeamlessMode)"
return $result
