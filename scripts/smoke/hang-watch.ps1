# F17 挂起现场取证：被 runtime-smoke-test.ps1 dot-source；本文件只定义函数，单独执行无副作用。
#
# 背景（2026-09-13 判定）：客户端 JVM 偶尔在开局十几秒内**整进程停止推进** —— 所有线程在同一秒
# 一起不再写日志。Windows 随即判定「停止与 Windows 交互」并关闭进程，Gradle 报出的退出码是
# NTSTATUS 0xCFFFFFFF，且没有 hs_err、没有 Java 异常、没有 crash-report。
#
# 为什么 coredump/WER LocalDumps 抓不到：JVM 自带的崩溃处理器只覆盖 SEH 异常路径
# （实测：Unsafe 造 SEGV → exit 1 + 写出 hs_err），而这条路径根本不在异常上，
# 所以「无 hs_err」= 「没走异常路径」，本地转储不会产生任何东西。
# 唯一可行的取证窗口是「日志静默」到「进程被系统关闭」之间那几秒 —— 用 jcmd attach 进目标 JVM
# 取线程栈与 VM 状态。健康场次的日志最长静默实测 ≤3s（92s 会话），故静默阈值取 5s。

function Find-SmokeClientJvm {
    [CmdletBinding()]
    param()
    # 客户端游戏 JVM 的唯一指纹：-Dhassium.smokeTest=true。
    # 服务端是 -Dhassium.serverSmokeTest=true（不匹配），Gradle 侧只有 -DsmokeSession=。
    $procs = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -eq 'java.exe' -and $_.CommandLine -match '-Dhassium\.smokeTest=true'
        })
    if ($procs.Count -eq 0) { return $null }
    return $procs[0]
}

function Save-SmokeHangDump {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][int]$JvmPid,
        [Parameter(Mandatory)][string]$OutFile,
        [int]$TimeoutSec = 15
    )
    $jcmd = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jcmd.exe' } else { 'jcmd.exe' }
    if (-not (Test-Path $jcmd) -and -not (Get-Command $jcmd -ErrorAction SilentlyContinue)) { return $false }

    $dir = Split-Path -Parent $OutFile
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir -ErrorAction SilentlyContinue | Out-Null }
    @(
        "=== smoke hang dump pid=$JvmPid captured=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
        "# 判读：attach 自己超时 = VM 卡在 safepoint（本身就是证据）；"
        "#       线程栈全部堵在同一把锁上 = 锁持有者是元凶；"
        "#       Render thread 停在 native 帧 = 图形驱动（本机 nvoglv64.dll 有过栈溢出前科）。"
    ) | Set-Content -Path $OutFile -Encoding utf8NoBOM

    # 三条命令各自带超时：整进程冻结时 jcmd 可能永远不返回，不能让它拖住 harness。
    $captured = $false
    foreach ($cmd in @(@('Thread.print', '-l'), @('VM.info'), @('GC.heap_info'))) {
        $stdout = "$OutFile.$($cmd[0]).tmp"
        $stderr = "$stdout.err"
        $p = Start-Process -FilePath $jcmd -ArgumentList (@($JvmPid) + $cmd) `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
        if (-not $p.WaitForExit($TimeoutSec * 1000)) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            "--- jcmd $($cmd -join ' ') → TIMEOUT ${TimeoutSec}s（attach 无法完成） ---" |
                Add-Content -Path $OutFile -Encoding utf8NoBOM
        } else {
            # 只有 jcmd 真正成功才算抓到：不存在的 pid 也会正常退出（非 0），不能当成功。
            if ($p.ExitCode -eq 0) { $captured = $true }
            "--- jcmd $($cmd -join ' ') (exit=$($p.ExitCode)) ---" | Add-Content -Path $OutFile -Encoding utf8NoBOM
            if (Test-Path $stdout) { Get-Content $stdout -Raw | Add-Content -Path $OutFile -Encoding utf8NoBOM }
            if ((Test-Path $stderr) -and (Get-Item $stderr).Length -gt 0) {
                "# stderr:" | Add-Content -Path $OutFile -Encoding utf8NoBOM
                Get-Content $stderr -Raw | Add-Content -Path $OutFile -Encoding utf8NoBOM
            }
        }
        Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
    return $captured
}
