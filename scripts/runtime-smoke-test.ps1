# 运行时冒烟测试 — 单次会话脚本（两轮连服版）
# 用法: .\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
# 流程: 服务端启动 VD=20 → 客户端连服 → 进世界后等 DelayMs → ROUND1 统计 → 主动断开 → 服务端切 VD=8 → 等 ReconnectDelayMs → 重连 → 进世界后等 DelayMs → ROUND2 统计 → 退出
# 关键真相源：Loom runDir 在子项目目录下（fabric/run/client、neoforge/run/server 等），不是根目录 run/
# 退出码: 0=PASS / 2=FAIL / 3=server_not_ready
# 端口: 默认 25565；并行模式由 batch 脚本传 -ServerPort 25566 等避免冲突
# 向后兼容: -SmokeHost 仍可用，但 -ServerPort 优先（若同时指定 -SmokeHost 则 -SmokeHost 完整地址优先）
param(
    [Parameter(Mandatory=$true)][string]$Ver,
    [Parameter(Mandatory=$true)][ValidateSet("fabric","neoforge")][string]$Loader,
    [Parameter(Mandatory=$true)][ValidateSet("I","R")][string]$Phase,
    [Parameter(Mandatory=$true)][string]$SessionId,
    [switch]$CleanWorld,
    [string]$SmokeHost = "",
    [int]$ServerPort = 25565,
    [int]$DelayMs = 15000,
    [int]$ReconnectDelayMs = 3000,
    [int]$ServerReadyTimeoutSec = 60,
    [int]$ClientTimeoutSec = 180
)

$ErrorActionPreference = "Continue"

# 解析最终连服地址：若显式指定 -SmokeHost 则优先；否则用 127.0.0.1:$ServerPort
if ($SmokeHost -and $SmokeHost -ne "") {
    $effectiveHost = $SmokeHost
} else {
    $effectiveHost = "127.0.0.1:$ServerPort"
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

# 确保输出目录存在
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $statsDir | Out-Null
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

Set-Location $projectRoot

# 1. 清理客户端缓存（整个 hassium_cache 目录 + config/hassium 整个目录 + crash-reports）
Write-Host "[$SessionId] [1/9] 清理客户端缓存 ($Loader/run/client/)..."
Remove-Item -Recurse -Force (Join-Path $clientRunDir "hassium_cache") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $clientRunDir "config\hassium") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $clientRunDir "crash-reports") -ErrorAction SilentlyContinue
# 兼容清理根目录 run/（旧配置遗留）
Remove-Item -Recurse -Force (Join-Path $projectRoot "run\client\hassium_cache") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $projectRoot "run\client\config\hassium") -ErrorAction SilentlyContinue

# 2. 配置服务端（view-distance 由 ServerSmokeTest 控制，这里设基础值）
Write-Host "[$SessionId] [2/9] 配置服务端 ($Loader/run/server/)..."
New-Item -ItemType Directory -Force -Path $serverRunDir -ErrorAction SilentlyContinue | Out-Null
Set-Content -Path (Join-Path $serverRunDir "eula.txt") -Value "eula=true" -NoNewline
$props = @"
server-port=$ServerPort
view-distance=20
online-mode=false
level-type=minecraft\:normal
motd=Hassium Smoke Test
max-players=20
white-list=false
enforce-whitelist=false
spawn-protection=0
"@
Set-Content -Path (Join-Path $serverRunDir "server.properties") -Value $props

# 创建 world\serverconfig 目录（部分 neoforge 版本不会自动创建）
New-Item -ItemType Directory -Force -Path (Join-Path $serverRunDir "world\serverconfig") -ErrorAction SilentlyContinue | Out-Null

# 3. 清理存档（默认开启；避免跨版本/跨加载器 world 不兼容）
if ($CleanWorld) {
    Write-Host "[$SessionId] [3/9] 清理服务端存档 ($Loader/run/server/world/)..."
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world_nether") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "world_the_end") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "cache") -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $serverRunDir "world\serverconfig") -ErrorAction SilentlyContinue | Out-Null
} else {
    Write-Host "[$SessionId] [3/9] 跳过存档清理 (-CleanWorld 未指定)"
}

# 4. 释放 $ServerPort 端口（可能被上次会话残留进程占用）
$conns = Get-NetTCPConnection -LocalPort $ServerPort -ErrorAction SilentlyContinue
if ($conns) {
    Write-Host "[$SessionId] 端口 $ServerPort 占用，尝试释放..."
    $conns | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}

# 5. 启动服务端（后台，启用 ServerSmokeTest）
Write-Host "[$SessionId] [4/9] 启动服务端 ($Loader / $Ver)..."
$gradlew = Join-Path $projectRoot "gradlew.bat"
$serverArgs = @(":${Loader}:runServer", "-PhassiumSmokeTest=true", "-Pmc_ver=${Ver}")
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
    Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
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

# 7. 启动客户端（前台阻塞，自动两轮连服）
Write-Host "[$SessionId] [6/9] 启动客户端连服（两轮自动）..."
$clientArgs = @(
    ":${Loader}:runClient",
    "-PhassiumSmokeTest=true",
    "-PhassiumSmokeHost=$effectiveHost",
    "-PhassiumSmokeDelayMs=$DelayMs",
    "-PhassiumSmokeReconnectDelayMs=$ReconnectDelayMs",
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
while (-not $clientProc.HasExited -and (Get-Date) -lt $clientDeadline) {
    Start-Sleep -Seconds 5
}
if (-not $clientProc.HasExited) {
    Write-Host "[$SessionId] 客户端超时未退出，强制结束"
    Stop-Process -Id $clientProc.Id -Force -ErrorAction SilentlyContinue
}
$clientExit = if ($clientProc.ExitCode) { $clientProc.ExitCode } else { 0 }

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

$result = if ($hasPass -and $clientExit -eq 0) { "PASS" } else { "FAIL" }

# 10. 停止服务端 + 残留 java
Write-Host "[$SessionId] [9/9] 停止服务端..."
if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

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
return $result
