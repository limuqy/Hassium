# profile-client.ps1 — 一键抓取 Hassium 客户端 JVM 性能采样
#
# 用法：
#   .\scripts\profile-client.ps1 -Duration 60                      # 客户端已在运行
#   .\scripts\profile-client.ps1 -Duration 60 -Wait                # 等客户端出现再抓（冒烟场景）
#   .\scripts\profile-client.ps1 -Duration 60 -Out build\smoke-test\stats\my-profile
#   .\scripts\profile-client.ps1 -Duration 60 -AsprofPath D:\tools\asprof\bin\asprof.exe
#
# 说明：
#   - 用 jcmd -l 定位客户端 JVM（匹配 devlaunchinjector，毫秒级；CIM 查询在部分环境会卡）。
#   - 优先用 async-profiler（asprof）抓 html 火焰图（需先下载，见末尾提示）；
#     未安装时自动回退 JDK 自带 JFR（零依赖，输出 .jfr，用 JMC 查看火焰图）。
#   - 抓取期间保持游戏在前台复现卡顿场景（进服/移动/看区块加载）。
#
# async-profiler 下载（Windows x64，解压后 bin\asprof.exe）：
#   https://github.com/async-profiler/async-profiler/releases

param(
    [int]$Duration = 60,
    [string]$Out = "",
    [string]$AsprofPath = "",
    [switch]$JfrOnly,
    [switch]$Wait,
    [int]$WaitTimeout = 120
)

$ErrorActionPreference = "Stop"

# --- 1. 定位客户端 JVM（jcmd -l + VM.command_line；-Wait 时轮询等待出现） ---
# 服务端（runServer）同样走 devlaunchinjector.Main，必须用 -Dfabric.dli.env=client 区分。
function Find-ClientJvm {
    $out = & jcmd -l 2>$null
    foreach ($line in $out) {
        if ($line -match '^\s*(\d+)\s+.*devlaunchinjector') {
            $candidate = [int]$Matches[1]
            $cmd = (& jcmd $candidate VM.command_line 2>$null) -join ' '
            if ($cmd -match 'fabric\.dli\.env=client') {
                return $candidate
            }
        }
    }
    return $null
}

$targetId = Find-ClientJvm
if (-not $targetId -and $Wait) {
    $deadline = (Get-Date).AddSeconds($WaitTimeout)
    Write-Host "[profile-client] 等待客户端 JVM 出现（最多 ${WaitTimeout}s）..."
    while (-not $targetId -and (Get-Date) -lt $deadline) {
        Start-Sleep 2
        $targetId = Find-ClientJvm
    }
}
if (-not $targetId) {
    Write-Host "[profile-client] 未找到客户端 JVM。"
    if (-not $Wait) {
        Write-Host "             请先启动客户端（runClient）并进服，再运行本脚本；"
        Write-Host "             或加 -Wait 等待冒烟客户端出现。"
    }
    Write-Host "             当前 JVM："
    & jcmd -l 2>$null
    exit 1
}
Write-Host "[profile-client] 目标进程 PID=$targetId 时长=${Duration}s"

# --- 2. 输出路径 ---
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
if (-not $Out) {
    $Out = "build/smoke-test/stats/profile-$stamp"
}
if (-not $Out.StartsWith([System.IO.Path]::GetFullPath(".")) -and -not [System.IO.Path]::IsPathRooted($Out)) {
    $Out = Join-Path (Get-Location) $Out
}

# --- 3. asprof 优先（html 火焰图） ---
$asprof = $AsprofPath
if (-not $asprof) {
    $cmd = Get-Command asprof -ErrorAction SilentlyContinue
    if ($cmd) { $asprof = $cmd.Source }
}
if ($asprof -and -not $JfrOnly) {
    $outHtml = "$Out.html"
    Write-Host "[profile-client] async-profiler: $asprof"
    Write-Host "[profile-client] 抓取中...（游戏保持在前台复现场景）"
    & $asprof -d $Duration -o html -f $outHtml $targetId
    if ($LASTEXITCODE -eq 0 -and (Test-Path $outHtml)) {
        Write-Host "[profile-client] 完成: $((Resolve-Path $outHtml).Path)"
        exit 0
    }
    Write-Host "[profile-client] asprof 失败（exit=$LASTEXITCODE），回退 JFR..."
}

# --- 4. JFR 回退（JDK 自带，零依赖） ---
$outJfr = "$Out.jfr"
Write-Host "[profile-client] JFR 抓取中...（游戏保持在前台复现场景）"
& jcmd $targetId JFR.start name=hassium-profile duration=${Duration}s filename=$outJfr settings=profile
if ($LASTEXITCODE -ne 0) {
    Write-Host "[profile-client] JFR.start 失败（JFR 可能不可用），exit=$LASTEXITCODE"
    exit 1
}
# duration 到期自动停止；stop 仅作确认（recording 已结束/进程退出时忽略错误）
Start-Sleep -Seconds ($Duration + 2)
try {
    & jcmd $targetId JFR.stop name=hassium-profile 2>$null | Out-Null
} catch {
    # 客户端已退出：recording 未落盘，文件可能不存在
}
if (Test-Path $outJfr) {
    Write-Host "[profile-client] 完成: $outJfr"
    Write-Host "[profile-client] 查看方式：Java Mission Control（JMC）打开后选 Flame Graph；"
    Write-Host "             或安装 asprof 后转换：asprof -f out.html --jfr $outJfr"
} else {
    Write-Host "[profile-client] 未生成 JFR 文件（客户端可能在抓取期间退出了）。"
    Write-Host "             手动进服保持客户端运行，再运行本脚本。"
    exit 1
}
