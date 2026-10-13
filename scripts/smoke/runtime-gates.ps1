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
    return '\[main/ERROR\] (?:\(HkeyPerformanceDataUtil\)|\[(?:os\.dr\.wi\.re|oshi\.driver\.windows\.registry)\.HkeyPerformanceDataUtil/\]:) Unable to locate English counter names in registry Perflib 009\. Counters may need to be rebuilt:\s*$'
}

function Set-SmokeClientAutomationSettings {
    param([string]$ClientRunDir, [string]$Loader)
    # 仅修改 smoke 使用的开发运行目录。NeoForge 的 IN_GAME 按键上下文会被失焦暂停菜单屏蔽。
    $settings = @(@{ Path = (Join-Path $ClientRunDir 'options.txt'); Pattern = '^pauseOnLostFocus\s*:'; Line = 'pauseOnLostFocus:false' })
    if ($Loader -eq 'neoforge') {
        # 上游仍会将所有 loading warnings 写入日志；真正的加载错误仍由错误页/日志门禁拦截。
        $settings += @{ Path = (Join-Path $ClientRunDir 'config/neoforge-client.toml'); Pattern = '^\s*showLoadWarnings\s*='; Line = 'showLoadWarnings = false' }
    }
    foreach ($setting in $settings) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $setting.Path) | Out-Null
        $lines = if (Test-Path -LiteralPath $setting.Path) { @(Get-Content -LiteralPath $setting.Path) } else { @() }
        $found = $false
        $updated = @(foreach ($line in $lines) {
            if ($line -match $setting.Pattern) { $setting.Line; $found = $true } else { $line }
        })
        if (-not $found) { $updated += $setting.Line }
        Set-Content -LiteralPath $setting.Path -Value $updated -Encoding utf8NoBOM
    }
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
