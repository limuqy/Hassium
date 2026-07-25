# 临时调整服务端 hassium 配置以做压缩参数对照测试。
param(
    [Parameter(Mandatory=$true)][ValidateSet("off","level6","restore")][string]$Mode
)

$cfg = Join-Path $PSScriptRoot "..\fabric\run\server\config\hassium\hassium-server.toml"
$bak = $cfg + ".bak"

if ($Mode -eq "restore") {
    if (Test-Path $bak) {
        Copy-Item $bak $cfg -Force
        Write-Host "restored from backup"
    } else {
        Write-Host "no backup found"
    }
    return
}

if (-not (Test-Path $bak)) {
    Copy-Item $cfg $bak
}

$lines = Get-Content $cfg

if ($Mode -eq "off") {
    $lines = $lines -replace '^\s*globalPacketCompression\s*=.*$', "    globalPacketCompression = false"
    Write-Host "globalPacketCompression -> false"
} elseif ($Mode -eq "level6") {
    $lines = $lines -replace '^\s*globalCompressionLevel\s*=.*$', "    globalCompressionLevel = 6"
    Write-Host "globalCompressionLevel -> 6"
}

Set-Content -Path $cfg -Value $lines -Encoding UTF8
Get-Content $cfg | Select-String 'globalPacketCompression|globalCompressionLevel' | Out-Host
