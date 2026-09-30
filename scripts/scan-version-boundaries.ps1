# 合法 #if 分界常量（与 docs/version-segments.md 同步）
# 修改白名单时必须同步更新该文档。
$AllowedBoundaries = @(
    'MC_26_1_2',
    'MC_26_2',
    'MC_26_3'
)

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root 'versionProperties'))) {
    Write-Error "Cannot find versionProperties under $Root"
    exit 1
}

$Pattern = [regex]'MC_\d+(?:_\d+)+'
$Illegal = @()

$Dirs = @('common', 'fabric', 'neoforge') | ForEach-Object { Join-Path $Root $_ }
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
