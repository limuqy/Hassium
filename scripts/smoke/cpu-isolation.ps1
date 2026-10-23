# Functions only: dot-sourcing must preserve the caller's parameters and scope.
function New-SmokeCpuPlan {
    param(
        [ValidateSet('Auto','Required','Off')][string]$Policy = 'Auto',
        [int]$ServerCcd = 0,
        [int]$ClientCcd = 1
    )
    $plan = [ordered]@{
        policy = $Policy; enabled = $false; source = 'Windows RelationCache L3';
        cpuModel = $null; domains = @(); server = $null; client = $null; reason = $null
    }
    if ($Policy -eq 'Off') { $plan.reason = 'disabled by request'; return [pscustomobject]$plan }
    try {
        if (-not $IsWindows) { throw 'CPU isolation currently requires Windows.' }
        if (-not ('SmokeWindowsCpuTopology' -as [type])) {
            Add-Type -Path (Join-Path $PSScriptRoot 'WindowsCpuTopology.cs') -ErrorAction Stop
        }
        $plan.cpuModel = (Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1).Name.Trim()
        $current = [System.Diagnostics.Process]::GetCurrentProcess()
        try { $allowed = [BitConverter]::ToUInt64([BitConverter]::GetBytes($current.ProcessorAffinity.ToInt64()), 0) } finally { $current.Dispose() }
        $domains = @([SmokeWindowsCpuTopology]::ReadL3Domains())
        $plan.domains = @(for ($index = 0; $index -lt $domains.Count; $index++) {
            $domain = $domains[$index]
            [pscustomobject]@{
                index = $index; group = $domain.Group; mask = ('0x{0:X}' -f $domain.Mask)
                logicalProcessors = @(for ($bit = 0; $bit -lt 64; $bit++) {
                    if (($domain.Mask -shr $bit) -band 1) { $bit }
                })
                l3Bytes = $domain.CacheBytes
            }
        })
        if ($ServerCcd -eq $ClientCcd) { throw 'Server and client must select different L3 domains.' }
        if ($ServerCcd -lt 0 -or $ClientCcd -lt 0 -or
                $ServerCcd -ge $domains.Count -or $ClientCcd -ge $domains.Count) {
            throw "Requested CCD/L3 indices $ServerCcd/$ClientCcd unavailable ($($domains.Count) domains)."
        }
        $serverMask = $domains[$ServerCcd].Mask
        $clientMask = $domains[$ClientCcd].Mask
        if (($serverMask -band $clientMask) -ne 0) { throw 'Selected L3 domains overlap.' }
        if (($serverMask -band $allowed) -ne $serverMask -or ($clientMask -band $allowed) -ne $clientMask) {
            throw 'Selected domains are outside the launcher process affinity.'
        }
        $plan.server = $plan.domains[$ServerCcd]
        $plan.client = $plan.domains[$ClientCcd]
        $plan.enabled = $true
    } catch {
        if ($Policy -eq 'Required') { throw }
        $plan.reason = $_.Exception.Message
    }
    return [pscustomobject]$plan
}

function Start-SmokePinnedProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [string]$RedirectStandardOutput,
        [string]$RedirectStandardError,
        [string]$WorkingDirectory,
        [string]$AffinityMask
    )
    $launch = @{ FilePath = $FilePath; ArgumentList = $ArgumentList; PassThru = $true; WindowStyle = 'Hidden'; ErrorAction = 'Stop' }
    foreach ($key in @('RedirectStandardOutput','RedirectStandardError','WorkingDirectory')) {
        if ($PSBoundParameters[$key]) { $launch[$key] = $PSBoundParameters[$key] }
    }
    # Set the parent before spawn: Windows children inherit affinity, including
    # gradlew -> its unique single-use daemon -> game JVM and the JVM's workers.
    # Restoring this launcher never widens a previously created child's mask.
    $launcher = [System.Diagnostics.Process]::GetCurrentProcess()
    $original = $launcher.ProcessorAffinity
    try {
        if ($AffinityMask) { $launcher.ProcessorAffinity = [IntPtr][Convert]::ToInt64($AffinityMask, 16) }
        $child = Start-Process @launch
    } finally {
        $launcher.ProcessorAffinity = $original
        $launcher.Dispose()
    }
    return $child
}

function Assert-SmokeProcessAffinity {
    param([Parameter(Mandatory)][int]$ProcessId, [Parameter(Mandatory)][string]$AffinityMask)
    $process = [System.Diagnostics.Process]::GetProcessById($ProcessId)
    try {
        $actual = '0x{0:X}' -f $process.ProcessorAffinity.ToInt64()
        if ($actual -ne $AffinityMask) { throw "PID $ProcessId affinity $actual differs from requested $AffinityMask" }
        return [pscustomobject]@{ pid = $ProcessId; mask = $actual; verified = $true }
    } finally { $process.Dispose() }
}

function Get-SmokeJavaAffinityEvidence {
    param([Parameter(Mandatory)][int]$LauncherId, [Parameter(Mandatory)][string]$AffinityMask)
    $tree = @{}
    foreach ($item in Get-CimInstance Win32_Process -ErrorAction Stop) { $tree[[int]$item.ProcessId] = $item }
    foreach ($item in $tree.Values) {
        if ($item.Name -ne 'java.exe' -or $item.CommandLine -notmatch '-Dhassium\.(serverSmokeTest|smokeTest)=true') { continue }
        $ancestor = [int]$item.ParentProcessId
        $visited = @{}
        while ($ancestor -ne $LauncherId -and $tree.ContainsKey($ancestor) -and -not $visited.ContainsKey($ancestor)) {
            $visited[$ancestor] = $true
            $ancestor = [int]$tree[$ancestor].ParentProcessId
        }
        if ($ancestor -eq $LauncherId) {
            Assert-SmokeProcessAffinity -ProcessId $item.ProcessId -AffinityMask $AffinityMask
        }
    }
}
