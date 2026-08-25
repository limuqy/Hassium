# 运行时冒烟测试 — 单次会话脚本（两轮连服版）
# 用法: .\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
#       .\scripts\runtime-smoke-test.ps1 -Ver 1.20.6 -Loader forge -Phase I -SessionId "1.20.6_forge_I"   # forge 支持范围见 versionProperties/{Ver}.properties 的 builds_for
# 流程: 服务端启动 VD=20 → 客户端连服 → 进世界后等 DelayMs → ROUND1 统计 → 主动断开 → 服务端切 VD=10 → 等 ReconnectDelayMs → 重连 → 进世界后等 DelayMs → ROUND2 统计 → 退出
# 关键真相源：Loom runDir 在子项目目录下（fabric/run/client、neoforge/run/server 等），不是根目录 run/
# 退出码: 0=PASS / 2=FAIL / 3=server_not_ready
# 端口: 默认 25565；并行模式由 batch 脚本传 -ServerPort 25566 等避免冲突
# 向后兼容: -SmokeHost 仍可用，但 -ServerPort 优先（若同时指定 -SmokeHost 则 -SmokeHost 完整地址优先）
param(
    [Parameter(Mandatory=$true)][string]$Ver,
    [Parameter(Mandatory=$true)][ValidateSet("fabric","forge","neoforge")][string]$Loader,
    [Parameter(Mandatory=$true)][ValidateSet("I","R")][string]$Phase,
    [Parameter(Mandatory=$true)][string]$SessionId,
    # -CleanWorld：重置本 loader×ver 的隔离存档目录 $serverLevelName（parity_<loader>_<ver>，
    # 见路径推导段）；非 CleanWorld 时该目录跨轮持久复用。旧固定 world/ 目录不再使用、不主动删除。
    [switch]$CleanWorld,
    # -PregenOnly：只跑服务端预生成（SmokePhases=pregen，49×49 区域），
    # 等 PREGEN_DONE marker 后停服并把存档复制到 build/smoke-test/pregen-world/<Loader>-<Ver>/world，
    # 供后续冒烟 CleanWorld 时恢复（消除 worldgen 供给波动）。不启客户端。
    [switch]$PregenOnly,
    [string]$SmokeHost = "",
    [int]$ServerPort = 25565,
    # 20s 覆盖 maxChunksPerTick=4 的稳态批队列消费；10s 在 1.21+ 会稳定截断
    # 正常加载流，导致 landed 不能反映完整首屏覆盖。
    [int]$DelayMs = 20000,
    [int]$ReconnectDelayMs = 3000,
    # 客户端进服等待超时（0=不覆盖，用 ClientSmokeTest 默认 120s）。调长 -DelayMs
    # 时必须同步调大（classic 模式 ROUND1 等待 delayMs*2，超时从客户端启动算起）。
    [int]$JoinTimeoutMs = 0,
    # 客户端进服后飞行移动秒数（先爬升 2s 再平飞；0=不动）。仅验证用途：驱动
    # 「进服即移动」区块补给顺序场景，非标准冒烟默认行为。
    [int]$MoveSeconds = 0,
    # Server view-distance: ROUND1=Vd1, switch to Vd2 after first disconnect.
    [int]$Vd1 = 20,
    [int]$Vd2 = 10,
    [int]$ServerReadyTimeoutSec = 160,
    [int]$ClientTimeoutSec = 240,
    [string]$SmokePhases = "classic",
    # T8 场景引擎：-Scenario <name> 加载 common/src/main/resources/hassium/smoke/scenario/<name>.scenario。
    # 默认 classic 不注入 -Dhassium.smokeScenario（保持既有 ClientSmokeTest 经典路径零行为变化）；
    # 显式指定时经 loom 属性透传链（-PhassiumSmokeScenario → buildSrc 三端映射）注入。
    # seedgen/dimension 场景强制 -CleanWorld 语义；存在 scripts/smoke/profiles/<name>.profile.properties
    # 时按键值对 patch 双端 hassium toml（见 Invoke-SmokeProfilePatch）。
    [string]$Scenario = "classic",
    # -ManualLogout：ROUND1 断开改走真实手动登出路径（Minecraft.disconnect(Screen[,Z])/
    # clearLevel，MixinMinecraft HEAD 注入 dump 同步执行），验证「手动登出光照/方块落盘」。
    [switch]$ManualLogout,
    # 日志审计门禁追加豁免正则（默认清单见收尾段审计块）
    [string[]]$AllowErrorPatterns = @()
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

# 服务端存档按 loader×MC版本 隔离（消除跨版本世界污染：高版本存档的 trial_chambers/
# Unidentified mapping 等会卡低版本冒烟的日志审计门禁）。命名与 build 产物目录风格
# 一致（1.20.1 → 1_20_1）。旧固定 world/ 目录不主动删除，仅不再使用。
$serverLevelName = "parity_${Loader}_" + ($Ver -replace '\.', '_')
$serverLevelDir = Join-Path $serverRunDir $serverLevelName

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

# ===== 生产映射字段预检 =====
# loom runServer/runClient 运行在 named（mojmap 可读名）环境，永远测不出生产
# SRG/intermediary jar 下硬编码字段名反射失败的问题（1.20.1 Forge 世界不加载
# 的根因：Connection.channel / ServerGamePacketListenerImpl.connection 等字段在
# SRG 下叫 f_xxx_，按 mojmap 名 getDeclaredField 必抛 NoSuchFieldException）。
# 这里直接对 loom 缓存中的生产映射 jar（forge: minecraft-merged-srg.jar）做 javap
# 结构检查，验证 Hassium "按类型找字段"（common/compat/ReflectionCompat）反射点
# 在混淆名环境下可命中，防回归。预检失败即 FAIL 会话。
# 检查项：
#   Connection 存在 io.netty.channel.Channel 类型字段      → ZstdPipelineSwitcher.getConnectionChannel
#   ServerGamePacketListenerImpl(1.20.1) / ServerCommonPacketListenerImpl(1.20.2+)
#     存在 net.minecraft.network.Connection 类型字段       → PlayerCompat.findConnectionField
#   ClientChunkCache 存在 $Storage 成员类字段              → ViewDistanceExtensionService drop fallback
#   IdDispatchCodec(1.20.2+) 存在 java.util.List 类型字段  → PacketCodecCompat.byId
function Invoke-MappingFieldPrecheck {
    param([string]$PreVer, [string]$PreLoader)
    $check = [ordered]@{
        Version = $PreVer; Loader = $PreLoader; Checked = $false; Ok = $true
        Skipped = $false; Detail = ""; Failures = @()
    }
    # fabric 运行时为 intermediary：loom 缓存无生产 jar，且 intermediary 只改字段名
    # 不改类型，类型匹配天然免疫；SRG 检查已覆盖同一查找逻辑。跳过 fabric。
    if ($PreLoader -eq "fabric") {
        $check.Skipped = $true
        $check.Detail = "fabric=intermediary 仅改字段名，类型匹配免疫（SRG 已覆盖）"
        return [pscustomobject]$check
    }
    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\$PreVer\forge"
    if (-not (Test-Path $cacheRoot)) {
        $check.Skipped = $true
        $check.Detail = "未找到 loom forge 缓存: $cacheRoot"
        return [pscustomobject]$check
    }
    $srgJar = Get-ChildItem -Path $cacheRoot -Recurse -Filter "minecraft-merged-srg.jar" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $srgJar) {
        $check.Skipped = $true
        $check.Detail = "未找到 minecraft-merged-srg.jar: $cacheRoot"
        return [pscustomobject]$check
    }
    $check.Checked = $true
    $check.Detail = $srgJar.FullName
    function Get-JavapOutput([string]$Class) {
        (& javap -p -classpath $srgJar.FullName $Class 2>&1) -join "`n"
    }
    # 1. Connection.channel（ZstdPipelineSwitcher.getConnectionChannel）
    $connOut = Get-JavapOutput "net.minecraft.network.Connection"
    if ($connOut -notmatch "io\.netty\.channel\.Channel") {
        $check.Ok = $false
        $check.Failures += "Connection 无 Channel 类型字段（getConnectionChannel 会 NoSuchField）"
    }
    # 2. player connection 字段所在类：1.20.2+ 上移到 ServerCommonPacketListenerImpl
    $listenerClass = if ([version]$PreVer -ge [version]"1.20.2") { "net.minecraft.server.network.ServerCommonPacketListenerImpl" } else { "net.minecraft.server.network.ServerGamePacketListenerImpl" }
    $listenerOut = Get-JavapOutput $listenerClass
    if ($listenerOut -notmatch "net\.minecraft\.network\.Connection") {
        $check.Ok = $false
        $check.Failures += "$listenerClass 无 Connection 类型字段（findConnectionField 会 NoSuchField）"
    }
    # 3. ClientChunkCache.storage（成员类字段，ViewDistanceExtensionService drop fallback）
    $cccOut = Get-JavapOutput "net.minecraft.client.multiplayer.ClientChunkCache"
    if ($cccOut -notmatch 'ClientChunkCache\$Storage') {
        $check.Ok = $false
        $check.Failures += 'ClientChunkCache 无 $Storage 成员类字段（drop fallback 会 NoSuchField）'
    }
    # 4. IdDispatchCodec.byId（1.20.5+ 引入；1.20.1–1.20.4 无此类，跳过）
    if ([version]$PreVer -ge [version]"1.20.5") {
        $idcOut = Get-JavapOutput "net.minecraft.network.codec.IdDispatchCodec"
        if ($idcOut -notmatch "java\.util\.List") {
            $check.Ok = $false
            $check.Failures += "IdDispatchCodec 无 List 类型字段（PacketCodecCompat.byId 会 NoSuchField）"
        }
    }
    return [pscustomobject]$check
}

$precheck = Invoke-MappingFieldPrecheck -PreVer $Ver -PreLoader $Loader
if (-not $precheck.Skipped) {
    $precheckFile = Join-Path $logDir "precheck_${SessionId}.json"
    $precheck | ConvertTo-Json -Depth 3 | Out-File $precheckFile -Encoding UTF8
    if ($precheck.Ok) {
        Write-Host "[$SessionId] 生产映射预检 PASS（SRG 字段结构可命中全部反射点）"
    } else {
        Write-Host "[$SessionId] 生产映射预检 FAIL:" -ForegroundColor Red
        $precheck.Failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = $Phase; Result = "FAIL"; Reason = "mapping_precheck: $($precheck.Failures -join '; ')"; ClientExitCode = -1 }
        $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
        exit 2
    }
}

# 写服务端 server.properties（§2 配置 + §4.1 自动避让改端口后同步重写共用）。
# level-name 指向 $serverLevelName（loader×ver 隔离存档目录，见路径推导段）。
# T8：本脚本要求 PowerShell 7 运行——配置文件写出统一 -Encoding utf8NoBOM。背景：
# Windows PowerShell 5.1 的 -Encoding UTF8 带 BOM，night-config 对 BOM 敏感直接
# ParsingException → 双端配置整份回落默认（seedgen 三跑根因）。被 mod/服务端消费的
# 配置文件：hassium toml / server.properties / eula.txt。
function Write-SmokeServerProperties {
    param(
        [Parameter(Mandatory=$true)][string]$Dir,
        [Parameter(Mandatory=$true)][int]$Port,
        [Parameter(Mandatory=$true)][string]$LevelName,
        [int]$ViewDistance = 16
    )
    $props = @"
level-name=$LevelName
server-port=$Port
view-distance=$ViewDistance
online-mode=false
gamemode=creative
level-type=minecraft\:normal
level-seed=42
motd=Hassium Smoke Test
max-players=20
white-list=false
enforce-whitelist=false
spawn-protection=0
"@
    Set-Content -Path (Join-Path $Dir "server.properties") -Value $props -Encoding utf8NoBOM
}

# T8 场景配置档案落盘：读取 scripts/smoke/profiles/<Name>.profile.properties（行式
# key=value，# 注释；value 须为合法 TOML 字面量，字符串自带引号），按键值对 patch 双端
# hassium toml（客户端 run/client/config/hassium/hassium-client.toml、服务端
# run/server/config/hassium/hassium-server.toml）。键路径写法与 smoke-config-patch.ps1
# 既有机制一致：按行正则 ^\s*<key>\s*= 匹配并保留原行缩进替换。仅 patch 文件中已存在的
# 键；toml 文件或键缺失时告警跳过，不阻断冒烟。profile 文件不存在时整体 no-op。
function Invoke-SmokeProfilePatch {
    param(
        [string]$Name,
        [string]$ClientRunDir,
        [string]$ServerRunDir,
        [string]$SessionTag
    )
    $profilePath = Join-Path $PSScriptRoot "smoke\profiles\${Name}.profile.properties"
    if (-not (Test-Path $profilePath)) { return }
    $kvPairs = @()
    foreach ($line in (Get-Content $profilePath)) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith("#")) { continue }
        $idx = $t.IndexOf("=")
        if ($idx -lt 1) { continue }
        $kvPairs += @{ Key = $t.Substring(0, $idx).Trim(); Value = $t.Substring($idx + 1).Trim() }
    }
    if ($kvPairs.Count -eq 0) {
        Write-Host "[$SessionTag] profile '$Name' 无有效键值对，跳过 patch"
        return
    }
    foreach ($toml in @(
        (Join-Path $ClientRunDir "config\hassium\hassium-client.toml"),
        (Join-Path $ServerRunDir "config\hassium\hassium-server.toml")
    )) {
        if (-not (Test-Path $toml)) {
            Write-Host "[$SessionTag] profile '$Name' 跳过 ${toml}：文件不存在（全新 run 目录由 mod 首启生成默认值）" -ForegroundColor Yellow
            continue
        }
        $lines = Get-Content $toml
        foreach ($kv in $kvPairs) {
            $keyEsc = [regex]::Escape($kv.Key)
            $patched = $false
            $newLines = foreach ($l in $lines) {
                if ($l -match "^(\s*)${keyEsc}\s*=.*$") {
                    $patched = $true
                    "$($Matches[1])$($kv.Key) = $($kv.Value)"
                } else {
                    $l
                }
            }
            $lines = @($newLines)
            $leaf = Split-Path -Leaf $toml
            if ($patched) {
                Write-Host "[$SessionTag] profile '$Name': $($kv.Key) = $($kv.Value) -> $leaf"
            } else {
                Write-Host "[$SessionTag] profile '$Name': 键 $($kv.Key) 在 $leaf 中不存在，跳过" -ForegroundColor Yellow
            }
        }
        Set-Content -Path $toml -Value $lines -Encoding utf8NoBom
    }
}

# 仅清理本会话相关的 java 进程，避免在并行模式下误杀另一会话 / 另一项目。
# 服务端通过端口定位（只杀 Listen 连接，且验证命令行属本工程）；客户端/兜底服务端
# 通过命令行中「本工程根 + $Loader\run\{client,server}」路径的出现来定位。
# 不会杀掉 gradle daemon（其命令行不含 run/server 或 run/client）。
function Stop-SessionJava {
    param(
        [int]$ServerPort,
        [string]$Loader
    )

    $rootEsc = [regex]::Escape($projectRoot)

    # 本工程 loom dev 实例判定：devlaunchinjector 命令行含本工程 dli.config + env 标记
    # （fabric/forge/neoforge 通用）；GradleMain/老路径作为兜底。
    $dliAny = "-Dfabric\.dli\.config=$rootEsc"
    $isOursServer = {
        param($cmd)
        return $cmd -and (($cmd -match $dliAny -and $cmd -match "-Dfabric\.dli\.env=server") -or
                          ($cmd -match ":${Loader}:runServer") -or
                          ($cmd -match "$rootEsc[\\/]+$([regex]::Escape($Loader))[\\/]+run[\\/]+server"))
    }
    $isOursClient = {
        param($cmd)
        return $cmd -and (($cmd -match $dliAny -and $cmd -match "-Dfabric\.dli\.env=client") -or
                          ($cmd -match ":${Loader}:runClient") -or
                          ($cmd -match "$rootEsc[\\/]+$([regex]::Escape($Loader))[\\/]+run[\\/]+client"))
    }

    # 1. 通过端口定位服务端 java 进程：只杀 Listen 连接（= 服务端本尊），
    #    且命令行必须命中本工程特征，否则视为他人进程不动。
    if ($ServerPort -gt 0) {
        $serverConns = Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue
        if ($serverConns) {
            foreach ($conn in $serverConns) {
                $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue
                if (-not $owner) { continue }
                $cmd = $owner.CommandLine
                if (& $isOursServer $cmd) {
                    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
                } else {
                    Write-Host "[$SessionId] 端口 $ServerPort 占用者非本工程服务端（PID $($conn.OwningProcess)），跳过不动"
                }
            }
            Start-Sleep -Milliseconds 500
        }
    }

    # 2. 通过本工程 loom dev 实例特征定位客户端/服务端 java 进程（兜底，杀残留）。
    $sessionJava = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -eq "java.exe" -and $_.CommandLine -and (
            (& $isOursServer $_.CommandLine) -or (& $isOursClient $_.CommandLine)
        )
    }
    foreach ($proc in $sessionJava) {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    }
}


# 1. 清理客户端缓存（整个 hassium_cache 目录 + config/hassium 整个目录 + crash-reports）
Write-Host "[$SessionId] [1/9] 清理客户端缓存 ($Loader/run/client/)..."
Remove-Item -Recurse -Force (Join-Path $clientRunDir "hassium_cache") -ErrorAction SilentlyContinue
# Remove-Item -Recurse -Force (Join-Path $clientRunDir "config\hassium") -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $clientRunDir "crash-reports") -ErrorAction SilentlyContinue
# 服务端 crash-reports 同步清理（日志审计门禁以「会话内非空」为失败信号，历史残留会误报）
Remove-Item -Recurse -Force (Join-Path $serverRunDir "crash-reports") -ErrorAction SilentlyContinue
Set-Content -Path (Join-Path $serverRunDir "eula.txt") -Value "eula=true" -NoNewline -Encoding utf8NoBOM
# 2. 配置服务端（view-distance 由 ServerSmokeTest 控制，这里设基础值）
Write-Host "[$SessionId] [2/9] 配置服务端 ($Loader/run/server/)..."
New-Item -ItemType Directory -Force -Path $serverRunDir -ErrorAction SilentlyContinue | Out-Null
Write-SmokeServerProperties -Dir $serverRunDir -Port $ServerPort -LevelName $serverLevelName -ViewDistance $Vd1

# 创建 <level>\serverconfig 目录（部分 neoforge / forge 50 版本不会自动创建）
New-Item -ItemType Directory -Force -Path (Join-Path $serverLevelDir "serverconfig") -ErrorAction SilentlyContinue | Out-Null
# 防御性清理（仅旧存档）：Hassium 服务端配置早已迁移 COMMON 级（config/hassium/，不写 world/serverconfig/），
# 但历史存档可能残留 world/serverconfig/hassium/*.toml.bak——FML ConfigTracker 会把整套 parent 路径
# 重复拼到 tmp 文件名前导致起服崩溃。此处仅清理旧存档残留，新存档不会触发。
$needConfigTrackerClean = ($Loader -eq "neoforge") -or ($Loader -eq "forge" -and $Ver -ge "1.20.6")
if ($needConfigTrackerClean) {
    $hassiumServerConfig = Join-Path $serverLevelDir "serverconfig\hassium"
    if (Test-Path $hassiumServerConfig) {
        Get-ChildItem -Path $hassiumServerConfig -File -Filter "*.toml*" -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "[$SessionId] 清理 $Loader 存档 serverconfig/hassium 残留 toml（绕过 FML ConfigTracker 路径拼接 bug）"
    }
}

if ($Scenario -in @("seedgen", "dimension")) {
    if (-not $CleanWorld) {
        Write-Host "[$SessionId] 场景 '$Scenario' 强制 -CleanWorld（重置 ${Loader}/${Ver} 存档目录）"
    }
    $CleanWorld = $true
}

# T8 harness 卫生（等价清缓存，不改产品默认）：中和历史演练遗留的
# master.controlReachableEndpoints——残留端点表会让网关绑定非默认端口（如 25567）而客户端
# 仍找默认 25566，握手永不成立。双端 toml 该键统一写空列表，两端一致回落默认；
# 随后 Invoke-SmokeProfilePatch 可按 profile 显式覆盖（profile 为准）。
function Reset-SmokeControlEndpoints {
    param([string]$ClientRunDir, [string]$ServerRunDir, [string]$SessionTag)
    foreach ($toml in @(
        (Join-Path $ClientRunDir "config\hassium\hassium-client.toml"),
        (Join-Path $ServerRunDir "config\hassium\hassium-server.toml")
    )) {
        if (-not (Test-Path $toml)) { continue }
        $lines = Get-Content $toml
        $newLines = @(
            foreach ($l in $lines) {
                if ($l -match "^(\s*)controlReachableEndpoints\s*=.*$") {
                    "$($Matches[1])controlReachableEndpoints = []"
                } else {
                    $l
                }
            }
        )
        if (($newLines -join "`n") -ne ($lines -join "`n")) {
            Set-Content -Path $toml -Value $newLines -Encoding utf8NoBom
            Write-Host "[$SessionTag] harness 卫生: controlReachableEndpoints 中和为 [] -> $(Split-Path -Leaf $toml)"
        }
    }
}
Reset-SmokeControlEndpoints -ClientRunDir $clientRunDir -ServerRunDir $serverRunDir -SessionTag $SessionId

# T8 场景配置档案落盘：存在 scripts/smoke/profiles/<Scenario>.profile.properties 时，
# 按键值对 patch 双端 hassium toml（须在服务端/客户端启动前完成）。文件不存在则 no-op。
Invoke-SmokeProfilePatch -Name $Scenario -ClientRunDir $clientRunDir -ServerRunDir $serverRunDir -SessionTag $SessionId

# 3. 清理存档（batch：loader 首轮 / 退版本 / 失败重试 会传 -CleanWorld；单会话默认不清理）
#    CleanWorld = 重置本 loader×ver 的 $serverLevelName 目录：优先从预生成存档恢复
#    （build/smoke-test/pregen-world/<Loader>-<Ver>/，源布局不变），消除 worldgen 供给波动；
#    无预生成存档则删空从头生成。非 CleanWorld 时该目录跨轮持久复用；旧固定 world/ 不动。
if ($CleanWorld) {
    $pregenRoot = Join-Path $logRoot "pregen-world"
    $pregenSrc = Join-Path $pregenRoot "${Loader}-${Ver}\world"
    if (-not $PregenOnly -and (Test-Path $pregenSrc)) {
        Write-Host "[$SessionId] [3/9] 恢复预生成存档 ($pregenSrc)..."
        Remove-Item -Recurse -Force $serverLevelDir -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $serverRunDir "cache") -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $serverLevelDir | Out-Null
        Copy-Item -Path $pregenSrc -Destination $serverLevelDir -Recurse -Force
        # serverconfig 不随预生成存档复制（Hassium 配置在 config/hassium/；
        # NeoForge/Forge 自身的 serverconfig 由服务端启动自动重建，复制反而带旧配置）
        Remove-Item -Recurse -Force (Join-Path $serverLevelDir "serverconfig") -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path (Join-Path $serverLevelDir "serverconfig") -ErrorAction SilentlyContinue | Out-Null
    } else {
        Write-Host "[$SessionId] [3/9] 清理服务端存档 ($Loader/run/server/$serverLevelName/)..."
        Remove-Item -Recurse -Force $serverLevelDir -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $serverRunDir "cache") -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path (Join-Path $serverLevelDir "serverconfig") -ErrorAction SilentlyContinue | Out-Null
    }
} else {
    Write-Host "[$SessionId] [3/9] 跳过存档清理（复用已有 $serverLevelName）"
}

# 4. 释放 $ServerPort 端口：只杀「本工程 loom 服务端」占用者；他人进程（其他会话/项目）
#    占用时仅告警不杀，避免误杀并行会话。
$rootEsc = [regex]::Escape($projectRoot)
$dliConfig = "-Dfabric\.dli\.config=$rootEsc"
$portsToFree = @($ServerPort)
foreach ($p in $portsToFree) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        $ours = @()
        $theirs = @()
        foreach ($c in $conns) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($c.OwningProcess)" -ErrorAction SilentlyContinue
            $cmd = $owner.CommandLine
            if ($cmd -and $cmd -match $dliConfig -and $cmd -match "-Dfabric\.dli\.env=server") { $ours += $c.OwningProcess; continue }
            if ($cmd -and $cmd -match "$rootEsc.*run[\\/]+server") { $ours += $c.OwningProcess; continue }
            $theirs += $c.OwningProcess
        }
        foreach ($pid_ in ($ours | Select-Object -Unique)) {
            Write-Host "[$SessionId] 端口 $p 由本工程进程 PID $pid_ 占用，释放..."
            Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
        }
        foreach ($pid_ in ($theirs | Select-Object -Unique)) {
            Write-Host "[$SessionId] 端口 $p 被非本工程进程 PID $pid_ 占用，跳过不动（可能是并行会话）"
        }
        if ($ours) { Start-Sleep -Seconds 2 }
    }
}

# §4.1 端口自动避让（用户指示）：默认端口被占用（非本工程残留——§4 已释放本工程进程）
# 时自动改空闲端口，避免并行会话/其他项目占 25565 导致启动失败。
# 显式直传 -ServerPort 时不自动改（尊重指定端口，被占则后续服务端启动失败如实报错）。
# 改后全链跟随：server.properties、客户端连接、Stop-SessionJava 均引用 $ServerPort。
if (-not $PSBoundParameters.ContainsKey('ServerPort')) {
    $probe = Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue
    if ($probe) {
        $newPort = $ServerPort
        for ($try = 0; $try -lt 100; $try++) {
            $newPort++
            $busy = Get-NetTCPConnection -LocalPort $newPort -State Listen -ErrorAction SilentlyContinue
            if (-not $busy) { break }
        }
        if ($newPort -ne $ServerPort) {
            Write-Host "[$SessionId] 端口 $ServerPort 被占用（PID $($probe[0].OwningProcess)），自动改用 $newPort"
            $ServerPort = $newPort
            # §2 已写 properties（旧端口），同步重写
            Write-SmokeServerProperties -Dir $serverRunDir -Port $ServerPort -LevelName $serverLevelName -ViewDistance $Vd1
            # effectiveHost 在脚本开头按旧端口快照，同步重建（SmokeHost 显式优先语义保持）
            if (-not ($SmokeHost -and $SmokeHost -ne "")) {
                $effectiveHost = "127.0.0.1:$ServerPort"
            }
        } else {
            Write-Host "[$SessionId] 端口 $ServerPort 被占用，且未找到空闲避让端口；按原端口继续（启动将失败）"
        }
    }
}

# 4.5 -PregenOnly：只跑服务端预生成（49×49 区域），不启客户端。
# 等 PREGEN_DONE marker 后停服并把 $serverLevelName 存档复制到 build/smoke-test/pregen-world/<Loader>-<Ver>/world。
if ($PregenOnly) {
    Write-Host "[$SessionId] [PregenOnly] 预生成模式：起服 → 等 PREGEN_DONE → 停服 → 复制存档"
    # 全新世界（预生成一次后存档复用）
    Remove-Item -Recurse -Force $serverLevelDir -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $serverRunDir "cache") -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $serverLevelDir "serverconfig") -ErrorAction SilentlyContinue | Out-Null

    $pregenGradlew = Join-Path $projectRoot "gradlew.bat"
    # 不调 gradlew --stop：全局停 daemon 会误杀并行会话/其他项目的构建；--no-daemon 不依赖 daemon。
    Write-Host "[$SessionId] [PregenOnly] 启动服务端 ($Loader / $Ver)..."
    $pregenArgs = @("--no-daemon", "-Dorg.gradle.jvmargs=-Xmx2G -DsmokeSession=${SessionId}", ":${Loader}:runServer", "-PhassiumSmokeTest=true", "-PhassiumSmokePhases=pregen", "-Pmc_ver=${Ver}")
    $server = Start-Process -FilePath $pregenGradlew `
        -ArgumentList $pregenArgs `
        -RedirectStandardOutput $serverLog `
        -RedirectStandardError $serverErr `
        -PassThru -WindowStyle Hidden

    Write-Host "[$SessionId] [PregenOnly] 等待 PREGEN_DONE (超时 ${ServerReadyTimeoutSec}s)..."
    $pregenDeadline = (Get-Date).AddSeconds($ServerReadyTimeoutSec)
    $pregenOk = $false
    while ((Get-Date) -lt $pregenDeadline) {
        if ($server.HasExited) {
            Write-Host "[$SessionId] [PregenOnly] 服务端提前退出，退出码: $($server.ExitCode)"
            break
        }
        if (Test-Path $serverLog) {
            if (Select-String -Path $serverLog -Pattern 'PREGEN_DONE' -Quiet -ErrorAction SilentlyContinue) {
                $pregenOk = $true
                break
            }
        }
        Start-Sleep -Seconds 3
    }

    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Stop-SessionJava -ServerPort $ServerPort -Loader $Loader

    if ($pregenOk) {
        $pregenRoot = Join-Path $logRoot "pregen-world"
        $pregenDest = Join-Path $pregenRoot "${Loader}-${Ver}"
        Remove-Item -Recurse -Force $pregenDest -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $pregenDest | Out-Null
        Copy-Item -Path $serverLevelDir -Destination (Join-Path $pregenDest "world") -Recurse -Force
        # serverconfig 不随预生成存档复制（Hassium 配置在 config/hassium/；
        # NeoForge/Forge 自身的 serverconfig 由服务端启动自动重建，复制反而带旧配置）
        Remove-Item -Recurse -Force (Join-Path $pregenDest "world\serverconfig") -ErrorAction SilentlyContinue
        Write-Host "[$SessionId] [PregenOnly] 预生成完成，存档已保存到 $pregenDest"
        $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = "pregen"; Result = "PASS"; Reason = "pregen_done" }
    } else {
        Write-Host "[$SessionId] [PregenOnly] 预生成超时或失败" -ForegroundColor Red
        $resultObj = @{ SessionId = $SessionId; Ver = $Ver; Loader = $Loader; Phase = "pregen"; Result = "FAIL"; Reason = "pregen_timeout" }
    }
    $resultObj | ConvertTo-Json -Depth 3 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")
    if ($pregenOk) { exit 0 } else { exit 2 }
}

# 5. 启动服务端（后台，启用 ServerSmokeTest）
$gradlew = Join-Path $projectRoot "gradlew.bat"
# 5.0 不再调 gradlew --stop：该命令全局停所有 daemon，会误杀并行会话 / 其他项目正在跑的构建。
#    runServer/runClient 均显式 --no-daemon（见 5.1），不依赖 daemon；残留 daemon 由下次构建自然复用。
# 5.1 再起服务端 —— 显式 --no-daemon 确保不复用任何 daemon
Write-Host "[$SessionId] [4/9] 启动服务端 ($Loader / $Ver)..."
$serverArgs = @("--no-daemon", "-Dorg.gradle.jvmargs=-Xmx2G -DsmokeSession=${SessionId}", ":${Loader}:runServer", "-PhassiumSmokeTest=true", "-PhassiumSmokePhases=${SmokePhases}", "-PhassiumSmokeVd1=$Vd1", "-PhassiumSmokeVd2=$Vd2", "-Pmc_ver=${Ver}")
if ($PSBoundParameters.ContainsKey('Scenario')) {
    # T7/T8：显式 -Scenario 时向服务端注入场景（buildSrc 三端映射为 -Dhassium.serverSmokeScenario，
    # 配合 ScenarioEngine 服务端 op 逻辑；仅 serverSide 注入，客户端走 -PhassiumSmokeScenario）
    $serverArgs += "-PhassiumServerSmokeScenario=$Scenario"
}
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


# T2 PROBE JSON v1：客户端探针输出目录（JVM 属性 hassium.smokeTest.probeDir 指向）。
# 启动客户端前创建；ClientSmokeTest 每轮写 roundN.json，harness 解析结果时优先读这里。
$probeDir = Join-Path $logRoot "probe\$SessionId"
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
# 7. 启动客户端（前台阻塞，自动两轮连服）
# 冒烟 gradle 调用统一加 daemon 组隔离（org.gradle.jvmargs 唯一化）：gradle 8.x 的
# --no-daemon 仍是单次 daemon，且 daemon 按 JVM 参数分组复用——并行构建（其他会话/
# 其他 agent）会复用并互相 stop（"Daemon is stopping immediately"）把 runClient 杀掉。
# 隔离后冒烟 daemon 自成一组，与本仓库其他构建互不干扰；单次构建用完即停不泄漏。
Write-Host "[$SessionId] [6/9] 启动客户端连服（两轮自动）..."
$clientArgs = @(
    "--no-daemon",
    "-Dorg.gradle.jvmargs=-Xmx2G -DsmokeSession=$SessionId",
    ":${Loader}:runClient",
    "-PhassiumSmokeTest=true",
    "-PhassiumSmokeHost=$effectiveHost",
    "-PhassiumSmokeDelayMs=$DelayMs",
    "-PhassiumSmokeReconnectDelayMs=$ReconnectDelayMs",
    "-PhassiumSmokeMoveSeconds=$MoveSeconds",
    "-PhassiumSmokePhases=$SmokePhases",
    "-Pmc_ver=${Ver}",
    "-PhassiumSmokeProbeDir=$probeDir"
)
if ($PSBoundParameters.ContainsKey('Scenario')) {
    # T8：显式 -Scenario 时经 loom 属性透传链注入 -Dhassium.smokeScenario=<name>
    $clientArgs += "-PhassiumSmokeScenario=$Scenario"
}
if ($ManualLogout) {
    $clientArgs += "-PhassiumSmokeManualLogout=true"
}
if ($JoinTimeoutMs -gt 0) {
    $clientArgs += "-PhassiumSmokeJoinTimeoutMs=$JoinTimeoutMs"
}
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
    $round1Stats | Out-File (Join-Path $statsDir "${SessionId}_round1_VD${Vd1}.txt") -Encoding UTF8
    Write-Host "[$SessionId] ROUND1 统计已保存到 stats/${SessionId}_round1_VD${Vd1}.txt"
}

# 提取 ROUND2 统计
$round2Match = [regex]::Match($clientContent, "HassiumSmokeTest:CLIENT_STATS ROUND2 begin(.+?)HassiumSmokeTest:CLIENT_STATS ROUND2 end", [System.Text.RegularExpressions.RegexOptions]::Singleline)
if ($round2Match.Success) {
    $round2Stats = $round2Match.Groups[1].Value.Trim()
    $round2Stats | Out-File (Join-Path $statsDir "${SessionId}_round2_VD${Vd2}.txt") -Encoding UTF8
    Write-Host "[$SessionId] ROUND2 统计已保存到 stats/${SessionId}_round2_VD${Vd2}.txt"
}

# T2 PROBE JSON v1：优先读客户端写入的 roundN.json（结构化 counters/gateway/disk）；
# 缺失或解析失败时回退上方中文 stats 正则路径（兼容期）。字段契约：字段可增不可改名。
function Read-SmokeRoundProbe {
    param([string]$Dir, [int]$RoundNum)
    $p = Join-Path $Dir "round${RoundNum}.json"
    if (-not (Test-Path $p)) { return $null }
    try {
        $obj = Get-Content $p -Raw | ConvertFrom-Json
        if ($null -eq $obj.round) { throw "missing 'round' key" }
        return $obj
    } catch {
        Write-Host "[$SessionId] round${RoundNum}.json 解析失败，回退日志正则: $($_.Exception.Message)"
        return $null
    }
}
$probeRound1 = Read-SmokeRoundProbe -Dir $probeDir -RoundNum 1
$probeRound2 = Read-SmokeRoundProbe -Dir $probeDir -RoundNum 2
if ($probeRound1) {
    ($probeRound1 | ConvertTo-Json -Depth 5) | Out-File (Join-Path $statsDir "${SessionId}_round1_probe.json") -Encoding UTF8
}
if ($probeRound2) {
    ($probeRound2 | ConvertTo-Json -Depth 5) | Out-File (Join-Path $statsDir "${SessionId}_round2_probe.json") -Encoding UTF8
}
Write-Host "[$SessionId] PROBE JSON: round1=$($null -ne $probeRound1) round2=$($null -ne $probeRound2)"

# T3 P0 门禁（基于 PROBE JSON v1，作用于 ROUND2 探针数据）——仅 classic 场景评估：
#   G1 counters.ovdLoaded > 0
#   G2 sectionDeltaApplied > 0 或 lightSegRecalc > 0
#   G3 disk.shadowRegionExists 且 disk.regionFileCount > 0
#   G4 counters.locallyGenerated == 0（影子端全量命中，不允许本地补生成）
# 任一不满足 → Round2Pass=false 并把失败门禁名记入 result JSON（ProbeGateFailures）；
# probe 缺失或对应字段缺失（旧客户端）时跳过该门禁保持兼容。
# 非 classic 场景（seedgen/dimension 等）跳过这四条门禁：其探针语义不同
# （如 dimension 切维度轮无 ovd/影子区），套用 classic 门禁会误判 FAIL。
$probeGateFailures = @()
if ($Scenario -ne "classic") {
    # 非 classic 场景：四条 P0 门禁整体跳过，ProbeGateFailures 保持空数组（scenario-gated）
    Write-Host "[$SessionId] T3 P0 门禁: 非 classic 场景（$Scenario）跳过（scenario-gated）"
} elseif ($probeRound2) {
    $c2 = $probeRound2.counters
    $d2 = $probeRound2.disk
    if ($c2 -and $null -ne $c2.ovdLoaded -and -not ($c2.ovdLoaded -gt 0)) {
        $probeGateFailures += "ovdLoaded_not_positive"
    }
    if ($c2 -and ($null -ne $c2.sectionDeltaApplied -or $null -ne $c2.lightSegRecalc)) {
        $sda = if ($null -ne $c2.sectionDeltaApplied) { [long]$c2.sectionDeltaApplied } else { 0 }
        $lsr = if ($null -ne $c2.lightSegRecalc) { [long]$c2.lightSegRecalc } else { 0 }
        if (-not ($sda -gt 0 -or $lsr -gt 0)) {
            $probeGateFailures += "section_delta_or_light_recalc_absent"
        }
    }
    if ($d2 -and $null -ne $d2.shadowRegionExists -and $null -ne $d2.regionFileCount) {
        if (-not ($d2.shadowRegionExists -and [long]$d2.regionFileCount -gt 0)) {
            $probeGateFailures += "shadow_region_missing"
        }
    }
    if ($c2 -and $null -ne $c2.locallyGenerated -and [long]$c2.locallyGenerated -ne 0) {
        $probeGateFailures += "locally_generated_nonzero"
    }
    if ($probeGateFailures.Count -gt 0) {
        Write-Host "[$SessionId] T3 P0 门禁失败: $($probeGateFailures -join ', ')" -ForegroundColor Red
    } else {
        Write-Host "[$SessionId] T3 P0 门禁: 全部通过"
    }
}

# T7 dimension 磁盘门禁（post-exit）：维度切换不断连，shadow 世界只在断连/退出时落盘，
# dump 时刻 region 尚未 flush（probe disk.dimensions 全为 -1 属预期语义，Java 侧不改）。
# 客户端正常退出后，按本会话 probe roundN.json 记录的 disk.cacheDir（.../world/region）
# 定位影子世界根，校验 world/region（主世界）、world/DIM-1/region（下界）、world/DIM1/region
# （末地）三处均存在且 ≥1 个 .mca；失败记入 DimensionGateFailures 并判 FAIL。
# 遗留：主世界同坐标未被覆写的深度比对本轮不做。
$dimensionGateFailures = @()
if ($Scenario -eq "dimension") {
    if ($clientExit -ne 0) {
        $dimensionGateFailures += "client_exit_nonzero"
    } else {
        $cacheRegionDir = $null
        foreach ($pj in @(Get-ChildItem $probeDir -Filter "round*.json" -ErrorAction SilentlyContinue | Sort-Object Name)) {
            try {
                $pd = Get-Content $pj.FullName -Raw | ConvertFrom-Json
                if ($pd.disk -and $pd.disk.cacheDir) { $cacheRegionDir = $pd.disk.cacheDir }
            } catch { }
        }
        if (-not $cacheRegionDir) {
            # 回退：hassium_cache 下最近修改的 <serverId>\world\region（probe 缺失时兜底）
            $cacheRegionDir = Get-ChildItem (Join-Path $clientRunDir "hassium_cache") -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "world\region" } |
                Where-Object { Test-Path $_ } |
                Sort-Object { (Get-Item $_).LastWriteTime } -Descending |
                Select-Object -First 1
        }
        if (-not $cacheRegionDir -or -not (Test-Path $cacheRegionDir)) {
            $dimensionGateFailures += "shadow_world_not_found"
        } else {
            $dimWorldRoot = Split-Path -Parent $cacheRegionDir
            foreach ($dimEntry in @(@("overworld", "region"), @("nether", "DIM-1\region"), @("end", "DIM1\region"))) {
                $dimRegionDir = Join-Path $dimWorldRoot $dimEntry[1]
                $mcaCount = @(Get-ChildItem $dimRegionDir -Filter "*.mca" -File -ErrorAction SilentlyContinue).Count
                if ($mcaCount -lt 1) {
                    $dimensionGateFailures += "$($dimEntry[0])_region_missing"
                }
            }
        }
    }
    if ($dimensionGateFailures.Count -gt 0) {
        Write-Host "[$SessionId] dimension 磁盘门禁失败: $($dimensionGateFailures -join ', ')" -ForegroundColor Red
    } else {
        Write-Host "[$SessionId] dimension 磁盘门禁: 三维度 region 均有 .mca"
    }
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
$round1StatsFound = $round1Match.Success -or ($null -ne $probeRound1)
$round1Pass = $clientContent -match "ROUND1 stats OK"
$round2StatsFound = $round2Match.Success -or ($null -ne $probeRound2)
$round2Pass = ($clientContent -match "ROUND2 stats OK") -and ($probeGateFailures.Count -eq 0)
$hasPass = $clientContent -match "HassiumSmokeTest:PASS"
$hasFail = $clientContent -match "HassiumSmokeTest:FAIL"
# 非 classic 场景（seedgen/dimension 等）：不套 classic ROUND stats 正则预期——场景引擎的
# 轮次语义不同，Round1/Round2 结论直接取客户端 marker（HassiumSmokeTest:PASS 且无 FAIL），
# 由 HasFail 兜底；会话判定相应改走 HasPass/HasFail 路径（见下方 $result）。
if ($Scenario -ne "classic") {
    $round1Pass = $hasPass -and (-not $hasFail)
    $round2Pass = $hasPass -and (-not $hasFail)
}

# T7 V0 网关断言（网络核心路径）：解析 ClientSmokeTest 每轮 dump 的 GATEWAY_CLIENT marker。
# marker 格式：HassiumSmokeTest:GATEWAY_CLIENT ROUND<1|2> state=<NetworkCoreState> s2c=<n> c2s=<n> resume=<bool>
# 门禁：ROUND1/2 均须 state=ACTIVE 且 s2c>0（网络核心路径真实工作）；任一缺失/非 ACTIVE/s2c=0 → FAIL。
# 当前已知状态：客户端 outbound 初始地址源未接（T12 交接项 5，Wave 1b T1/B1 修复），
# 修复前 smoke 中 state 停留在 HANDSHAKING → 门禁 FAIL，即「无网络核心路径时 FAIL」语义。
$gatewayRe = "HassiumSmokeTest:GATEWAY_CLIENT\s+ROUND(\d) state=(\w+) s2c=(\d+) c2s=(\d+) resume=(true|false)"
$gatewayByRound = @{}
foreach ($gm in [regex]::Matches($clientContent, $gatewayRe)) {
    $round = "ROUND" + $gm.Groups[1].Value
    $gatewayByRound[$round] = @{
        gatewayState  = $gm.Groups[2].Value
        gatewayS2c    = [long]$gm.Groups[3].Value
        gatewayC2s    = [long]$gm.Groups[4].Value
        gatewayResume = ($gm.Groups[5].Value -eq "true")
    }
}
$gatewayGate = $false
if ($Scenario -ne "classic") {
    # 非 classic 场景（seedgen/dimension 等）：网关门禁只要求出现过的 GATEWAY_CLIENT marker
    # 全部 state=ACTIVE（R2 缺失不算失败——场景引擎提前退出时最后一轮可能无 R2 dump）；
    # 不要求两轮齐备、不查 c2s>0。零 marker 视为网络核心路径缺失 → FAIL。
    $presentGateways = @($gatewayByRound.Values)
    $gatewayGate = ($presentGateways.Count -gt 0) -and
                   (@($presentGateways | Where-Object { $_.gatewayState -ne "ACTIVE" }).Count -eq 0)
} elseif ($gatewayByRound.ContainsKey("ROUND1") -and $gatewayByRound.ContainsKey("ROUND2")) {
    $g1 = $gatewayByRound["ROUND1"]
    $g2 = $gatewayByRound["ROUND2"]
    # T9v3 gate 修正（Main 裁决）：标准 vanilla 登录路径 S2C 主通道 = vanilla TCP 壳连接，
    # 帧 S2C 通道仅登录桥/续流物化路径启用（REQ A1「S2C 镜像未做」）→ 标准会话 s2c 恒为 0，
    # 原 gate（两轮 s2c>0）不可达。放宽为两轮 state=ACTIVE 且 c2s>0（网关 C2S 路径真实工作）；
    # s2c>0 作为续流断言归 T10（迁移演练后帧 S2C 计数增长）。
    $gatewayGate = ($g1.gatewayState -eq "ACTIVE" -and $g1.gatewayC2s -gt 0) -and
                   ($g2.gatewayState -eq "ACTIVE" -and $g2.gatewayC2s -gt 0)
}
$gatewayRound1 = if ($gatewayByRound.ContainsKey("ROUND1")) {
    $gatewayByRound["ROUND1"]
} else {
    @{ gatewayState = "MISSING"; gatewayS2c = 0; gatewayC2s = 0; gatewayResume = $false }
}
$gatewayRound2 = if ($gatewayByRound.ContainsKey("ROUND2")) {
    $gatewayByRound["ROUND2"]
} else {
    @{ gatewayState = "MISSING"; gatewayS2c = 0; gatewayC2s = 0; gatewayResume = $false }
}

# 服务端视距切换检查（仅信息性输出/JSON 记录，不参与任何场景的 Result 判定；
# dimension 等非 classic 场景无 VD 切换离线窗口，False 属预期）
$serverSwitched = if (Test-Path $serverLog) {
    (Get-Content $serverLog -Raw) -match "view-distance switched to 10"
} else { $false }
# PASS 判定：
#   classic/R：client 两轮 PASS + 退出码 0 + T7 V0 网关断言门禁（两轮 ACTIVE 且 c2s>0）
#     + T3 P0 probe 门禁（经 Round2Pass 生效），无网络核心路径时 FAIL。
#   非 classic 场景：HasPass 且无 HasFail + 退出码 0 + 网关门禁；不套 classic stats/Round2Pass 预期。
#     dimension 场景另加 post-exit 磁盘门禁（DimensionGateFailures 恒空才 PASS）。
$result = if ($Scenario -ne "classic") {
    if ($hasPass -and (-not $hasFail) -and $clientExit -eq 0 -and $gatewayGate -and ($dimensionGateFailures.Count -eq 0)) { "PASS" } else { "FAIL" }
} elseif ($hasPass -and $clientExit -eq 0 -and $gatewayGate -and $round2Pass) { "PASS" } else { "FAIL" }

# 10. 停止服务端 + 残留 java
Write-Host "[$SessionId] [9/9] 停止服务端..."
if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
if (-not $clientProc.HasExited) { Stop-Process -Id $clientProc.Id -Force -ErrorAction SilentlyContinue }
# 仅杀本会话相关的 java 进程（通过端口和 run 目录定位），避免影响并行会话
Stop-SessionJava -ServerPort $ServerPort -Loader $Loader

# 11. 日志审计门禁：双端主日志的 ERROR/FATAL 行（豁免清单外）+ crash-reports 非空 → FAIL。
# 基线：fabric PASS 会话 0 报错；neoforge dev 仅 ClassTransformStatistics/DistCleaner 两类良性提示。
$logAuditAllow = @(
    # NeoForge dev 环境良性：unprotect 处理器转换率提示（非 mod 错误）
    "ClassTransformStatistics.*suspiciously high",
    # NeoForge dev dist 清理器：客户端类在 dedicated server 侧被拦截的提示
    "DistCleaner|not present on the dedicated server",
    # 原版 Realms 启动联网探测失败（离线/防火墙环境噪音，与 mod 无关）
    "mojang/RealmsClient\]: Failed to fetch Realms feature flags",
    # 原版 profile key pair 联网获取失败（离线环境噪音，与 mod 无关）
    "\(Minecraft\) Failed to retrieve profile key pair"
) + @($AllowErrorPatterns)
# 退出窗口限定豁免（仅 halt/closeStorage 之后生效，非全局）：客户端 Stopping! →
# vanilla Util.shutdownExecutors() 关停共享 ioPool 后，引擎/存档内部对半死池的零星
# 提交触发 vanilla REE 行（含原版日志的真实拼写错误 "Cound not schedule mailbox"）。
# 触发源已由 SeedGenLevelCompat ioPool 门控消除，此处兜底 halt 内部残留（报告 §7-4）。
$logAuditExitWindowAllow = @(
    "ProcessorMailbox\.registerForExecution",
    "Cound not schedule mailbox"
)
# 进入退出窗口的日志标记：vanilla 双端停机横幅 + Hassium exit-window / storage close 提示。
$logAuditExitWindowMarker = "Stopping!|Stopping server|client exit window|storage manager close"
$logAuditFailures = @()
foreach ($auditLog in @($serverLog, $clientLog)) {
    if (-not (Test-Path $auditLog)) { continue }
    $tag = Split-Path -Leaf $auditLog
    $inExitWindow = $false
    foreach ($line in Get-Content $auditLog) {
        # 客户端日志含 ANSI 颜色码（\x1b[...m），先剥离再做门禁匹配
        $line = $line -replace '\x1b\[[0-9;]*[A-Za-z]', ''
        if ($line -match $logAuditExitWindowMarker) { $inExitWindow = $true }
        if ($line -notmatch "/(ERROR|FATAL)\]|FATAL") { continue }
        $allowHit = $false
        foreach ($pat in $logAuditAllow) {
            if ($pat -and $line -match $pat) { $allowHit = $true; break }
        }
        if (-not $allowHit -and $inExitWindow) {
            foreach ($pat in $logAuditExitWindowAllow) {
                if ($pat -and $line -match $pat) { $allowHit = $true; break }
            }
        }
        if (-not $allowHit) { $logAuditFailures += "${tag}: $line" }
    }
}
foreach ($crashDir in @((Join-Path $clientRunDir "crash-reports"), (Join-Path $serverRunDir "crash-reports"))) {
    if (Test-Path $crashDir) {
        $crashes = @(Get-ChildItem $crashDir -File -ErrorAction SilentlyContinue)
        if ($crashes.Count -gt 0) {
            $logAuditFailures += "crash-reports 非空 ($(Split-Path -Leaf $crashDir)): $($crashes.Count) 个"
        }
    }
}
if ($logAuditFailures.Count -gt 0) {
    Write-Host "[$SessionId] 日志审计门禁失败（$($logAuditFailures.Count) 条未豁免报错）："
    $logAuditFailures | Select-Object -First 10 | ForEach-Object { Write-Host "[$SessionId]   $_" }
    $result = "FAIL"
}

$resultObj = @{
    SessionId = $SessionId
    Ver = $Ver
    Loader = $Loader
    Phase = $Phase
    Scenario = $Scenario
    Result = $result
    ClientExitCode = $clientExit
    Round1Stats = $round1StatsFound
    Round1Pass = $round1Pass
    Round2Stats = $round2StatsFound
    Round2Pass = $round2Pass
    # T3 P0 probe 门禁失败名单（空数组 = 全过或 probe 缺失跳过）；
    # 非 classic 场景四条 P0 门禁整体跳过（scenario-gated），ProbeGateFailures 恒为空
    ProbeGateScenarioGated = ($Scenario -ne "classic")
    ProbeGateFailures = @($probeGateFailures)
    ServerSwitched = $serverSwitched
    HasPass = $hasPass
    HasFail = $hasFail
    # T7 V0 网关断言字段（稳定命名供 T9 消费）：
    #   GatewayRound1.gatewayState/gatewayS2c/gatewayC2s/gatewayResume
    #   GatewayRound2.gatewayState/gatewayS2c/gatewayC2s/gatewayResume
    #   GatewayGatePass（ROUND1/2 均 ACTIVE 且 c2s>0；classic 阶段并入 Result 判定。T9v3 由 s2c>0 放宽）
    GatewayRound1 = $gatewayRound1
    GatewayRound2 = $gatewayRound2
    GatewayGatePass = $gatewayGate
    # T7 dimension post-exit 磁盘门禁失败名单（仅 dimension 场景评估；空数组 = 全过或不适用）
    DimensionGateFailures = @($dimensionGateFailures)
    # T2 PROBE JSON v1：roundN.json 原值透传（counters/gateway/disk 等），缺失为 $null
    Probe = @{
        Round1 = $probeRound1
        Round2 = $probeRound2
    }
    StatsFiles = @(
        if ($round1StatsFound) { "build/smoke-test/stats/${SessionId}_round1_VD${Vd1}.txt" }
        if ($round2StatsFound) { "build/smoke-test/stats/${SessionId}_round2_VD${Vd2}.txt" }
    )
    # T11 日志审计门禁失败名单（双端 ERROR/FATAL 未豁免行 + crash-reports 非空；空数组 = 全过）
    LogAuditFailures = @($logAuditFailures)
}
$resultObj | ConvertTo-Json -Depth 5 | Out-File (Join-Path $resultsDir "result_${SessionId}.json")

Write-Host "[$SessionId] === RESULT: $result ==="
Write-Host "[$SessionId] Round1: stats=$round1StatsFound pass=$round1Pass"
Write-Host "[$SessionId] LogAudit gate: $($logAuditFailures.Count -eq 0) (未豁免报错 $($logAuditFailures.Count) 条)"
Write-Host "[$SessionId] Round2: stats=$round2StatsFound pass=$round2Pass"
Write-Host "[$SessionId] ServerSwitched: $serverSwitched Exit: $clientExit"
Write-Host "[$SessionId] Gateway gate: $gatewayGate (R1=$($gatewayRound1.gatewayState)/c2s=$($gatewayRound1.gatewayC2s) R2=$($gatewayRound2.gatewayState)/c2s=$($gatewayRound2.gatewayC2s))"
return $result
