# 运行控制层辅助门禁：独立定义，便于不启动游戏就验证日志与磁盘采样。
function Test-SmokeLaunchFailure {
    param([object]$ScriptExit, $ResultObj)
    $reason = if ($ResultObj) { [string]$ResultObj.Reason } else { '' }
    if ($reason -match '^(loader_not_supported|mapping_precheck)') { return $false }
    # 场景可能在首次 dump 前失败；明确的业务 FAIL 不能按启动失败重试。
    if ($ResultObj -and [bool]$ResultObj.HasFail) { return $false }
    if ($ScriptExit -eq 3 -or $reason -eq 'server_not_ready' -or -not $ResultObj) { return $true }
    return -not [bool]$ResultObj.Round1Stats
}

function Get-SmokeEnvironmentAuditAllowPatterns {
    # OSHI 启动采集系统信息的单一已确认环境错误；不放行 FATAL 或其它 Perflib 错误。
    return '\[main/ERROR\] \(HkeyPerformanceDataUtil\) Unable to locate English counter names in registry Perflib 009\. Counters may need to be rebuilt:\s*$'
}

function Test-SmokeGracefulStopMarker {
    param([string]$LogText)
    $plain = $LogText -replace '\x1b\[[0-9;]*[A-Za-z]', ''
    return $plain -match '(?:\(Minecraft\)|\[minecraft/Minecraft\]:)\s+Stopping!'
}

function Get-SmokePostExitDiskSnapshot {
    param([string]$SessionId, [string]$ClientRunDir, $ProbeDisk)
    $snapshot = @{ sessionId = $SessionId; captured = $false; worldDir = $null; dimensions = @{};
        capturedAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
    $worldPath = [string]$ProbeDisk.worldDir
    if (-not $worldPath) { return $snapshot }
    try {
        # NeoForge 的 gameDirectory 可能是 '.'；相对路径属于游戏 cwd，不能按 harness cwd 解析。
        if (-not [IO.Path]::IsPathRooted($worldPath)) {
            $worldPath = Join-Path $ClientRunDir $worldPath
        }
        $worldPath = [IO.Path]::GetFullPath($worldPath)
        $cacheRoot = [IO.Path]::GetFullPath((Join-Path $ClientRunDir 'hassium_cache')) + [IO.Path]::DirectorySeparatorChar
        if (-not $worldPath.StartsWith($cacheRoot, [StringComparison]::OrdinalIgnoreCase)) {
            return $snapshot
        }
        $snapshot.worldDir = $worldPath
        foreach ($entry in @(@('overworld', 'overworld'), @('nether', 'the_nether'), @('end', 'the_end'))) {
            $region = Join-Path $worldPath "dimensions/minecraft/$($entry[1])/region"
            $count = if (Test-Path -LiteralPath $region -PathType Container) {
                @(Get-ChildItem -LiteralPath $region -Filter '*.mca' -File -ErrorAction Stop).Count
            } else { -1 }
            $snapshot.dimensions[$entry[0]] = @{ regionFileCount = $count }
        }
        $snapshot.captured = $true
    } catch {
        $snapshot.error = $_.Exception.Message
    }
    return $snapshot
}
