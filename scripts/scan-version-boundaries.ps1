# 合法 #if 分界常量（与 docs/version-segments.md 同步）
# 修改白名单时必须同步更新该文档。
$AllowedBoundaries = @(
    'MC_1_20_1',  # 基准（注释 / 偶发比较）
    'MC_1_20_2',
    'MC_1_20_4',  # NeoForge 网络技术断层：1.20.4 移除 SimpleChannel
    'MC_1_20_5',
    'MC_1_21_1',
    'MC_1_21_2',
    'MC_1_21_5',
    'MC_1_21_6',
    'MC_1_21_9',
    'MC_1_21_11'
)

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root 'versionProperties'))) {
    Write-Error "Cannot find versionProperties under $Root"
    exit 1
}

$Pattern = [regex]'MC_1_\d+_\d+'
$Illegal = @()

$Dirs = @('common', 'fabric', 'forge', 'neoforge') | ForEach-Object { Join-Path $Root $_ }
foreach ($dir in $Dirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Recurse -Filter '*.java' | ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length + 1)
        $lineNo = 0
        foreach ($line in Get-Content $_.FullName) {
            $lineNo++
            foreach ($m in $Pattern.Matches($line)) {
                $token = $m.Value
                if ($AllowedBoundaries -notcontains $token) {
                    $Illegal += "${rel}:${lineNo}: $token"
                }
            }
        }
    }
}

if ($Illegal.Count -gt 0) {
    Write-Host "Illegal version boundaries found (not in docs/version-segments.md whitelist):" -ForegroundColor Red
    $Illegal | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "scanVersionBoundaries: OK ($($AllowedBoundaries.Count) allowed constants)"
exit 0
