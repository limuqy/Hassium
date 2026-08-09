# 并行光照引擎冒烟 toml 注入/回滚辅助
# 【已退役 · 2026-08-09 config-restructure】注入键 parallelLightEngineEnabled / parallelLightEngineThreads
# 已不在 ConfigSchema（2.0.0 schema 零命中，对应功能已移除）；锚点 clientCache.mainThreadChunkBudgetMs
# 已重排为 chunk.mainThreadChunkBudgetMs。本脚本整体失效，不再维护；如需冒烟注入请按新键名改写。
# 用法（历史）:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/light-smoke-toml.ps1 -Loader fabric -Action Inject
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/light-smoke-toml.ps1 -Loader fabric -Action Rollback
#
# 注意：nightconfig 的 TomlParser 拒绝重复表头（"Table with path [clientCache] has been declared twice"），
# 追加 [clientCache]/[debug] 新表会让整个 toml 解析失败 → 全部配置回退默认。因此必须把键插入
# 已存在的单次声明表内（锚点 = 表内最后一个键），且每个注入行都带标记注释以便回滚。
param(
    [ValidateSet("fabric","forge","neoforge")][string]$Loader,
    [ValidateSet("Inject","Rollback")][string]$Action
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$toml = Join-Path $projectRoot "$Loader\run\client\config\hassium\hassium-client.toml"
$marker = "ParallelLight-smoke-injected"

if (-not (Test-Path $toml)) {
    Write-Host "[light-smoke-toml] 找不到 $toml"
    exit 1
}

function Write-NoBomLines([System.Collections.Generic.List[string]]$content) {
    # PS5.1 -Encoding UTF8 会写 BOM，tomlj 解析失败 → 无 BOM UTF-8（同 SeamlessMode 惯例）
    [System.IO.File]::WriteAllLines($toml, $content, (New-Object System.Text.UTF8Encoding($false)))
}

$lines = [System.Collections.Generic.List[string]](Get-Content $toml)

if ($Action -eq "Inject") {
    if ($lines | Where-Object { $_ -match "#$marker" }) {
        Write-Host "[light-smoke-toml] ${Loader}: 已存在注入标记，跳过（先 Rollback）"
        exit 0
    }

    $tabs = "`t"

    # 1) [clientCache]：锚点 = mainThreadChunkBudgetMs（该表最后一个键）
    $budgetIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*mainThreadChunkBudgetMs\s*=') { $budgetIdx = $i; break }
    }
    if ($budgetIdx -lt 0) {
        Write-Host "[light-smoke-toml] ${Loader}: 未找到 clientCache.mainThreadChunkBudgetMs 锚点，无法注入"
        exit 1
    }
    $cacheLines = [System.Collections.Generic.List[string]]@(
        "${tabs}#${marker}: 并行光照引擎（冒烟注入）",
        "${tabs}parallelLightEngineEnabled = true #$marker",
        "${tabs}parallelLightEngineThreads = 4 #$marker"
    )
    # 已存在同名键则改值，否则在锚点后插入
    $cacheInsertIdx = $budgetIdx
    for ($j = $cacheLines.Count - 1; $j -ge 0; $j--) {
        $key = ($cacheLines[$j] -split '=')[0].Trim()
        $found = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") { $found = $i; break }
        }
        if ($found -ge 0) {
            $lines[$found] = $cacheLines[$j]
        } else {
            $lines.Insert($cacheInsertIdx + 1, $cacheLines[$j])
        }
    }

    # 2) [debug]：锚点 = dataplaneLogging（该表最后一个键）
    $debugIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*dataplaneLogging\s*=') { $debugIdx = $i; break }
    }
    if ($debugIdx -lt 0) {
        Write-Host "[light-smoke-toml] ${Loader}: 未找到 debug.dataplaneLogging 锚点，无法注入"
        exit 1
    }
    $debugLines = [System.Collections.Generic.List[string]]@(
        "${tabs}lightVerify = true #$marker"
    )
    $debugInsertIdx = $debugIdx
    for ($j = $debugLines.Count - 1; $j -ge 0; $j--) {
        $key = ($debugLines[$j] -split '=')[0].Trim()
        $found = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") { $found = $i; break }
        }
        if ($found -ge 0) {
            $lines[$found] = $debugLines[$j]
        } else {
            $lines.Insert($debugInsertIdx + 1, $debugLines[$j])
        }
    }

    Write-NoBomLines $lines
    Write-Host "[light-smoke-toml] ${Loader}: 已注入并行光照引擎配置（parallelLightEngineEnabled=true, lightVerify=true）"
} else {
    $remaining = [System.Collections.Generic.List[string]]::new()
    $removed = $false
    foreach ($line in $lines) {
        if ($line -match "#$marker") {
            $removed = $true
        } else {
            $remaining.Add($line)
        }
    }
    if ($removed) {
        Write-NoBomLines $remaining
        Write-Host "[light-smoke-toml] ${Loader}: 已回滚注入段"
    } else {
        Write-Host "[light-smoke-toml] ${Loader}: 未找到注入标记，跳过回滚"
    }
}
