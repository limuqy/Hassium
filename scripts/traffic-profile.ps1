param(
    [Parameter(Mandatory=$true)][ValidatePattern('^1\.(20\.1|21\.(?:[1-9]|10|11))$')][string]$Ver,
    [Parameter(Mandatory=$true)][ValidateSet('fabric','forge','neoforge')][string]$Loader,
    [Parameter(Mandatory=$true)][string]$SessionId,
    # Use the same saved dictionary for both runs; the original is restored afterwards.
    [Parameter(Mandatory=$true)][string]$Dictionary,
    [string]$WorldZip = "",
    [ValidateSet('','sleepdisabled','sleepauto','sleepresume','sleepmixed','sleepfactory','sleepwindowtraffic','cachenbt')][string]$ScenarioOverride = "",
    [switch]$Offline,
    [ValidateSet('Auto','Required','Off')][string]$CpuIsolation = 'Auto'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$versionProperties = Join-Path $root "versionProperties/$Ver.properties"
if (-not (Test-Path -LiteralPath $versionProperties -PathType Leaf)) { throw "Unsupported Minecraft version: $Ver" }
$buildsForLine = Get-Content -LiteralPath $versionProperties |
    Where-Object { $_ -match '^\s*builds_for\s*=' } | Select-Object -First 1
if (-not $buildsForLine) { throw "Missing builds_for in $versionProperties" }
$supportedLoaders = @(($buildsForLine -split '=',2)[1].Split(',') | ForEach-Object { $_.Trim() })
if ($supportedLoaders -notcontains $Loader) {
    throw "Minecraft $Ver does not support $Loader (builds_for=$($supportedLoaders -join ','))"
}
if ($WorldZip -and ($Ver -ne '1.21.1' -or $Loader -ne 'neoforge')) {
    throw 'The supplied factory save is only for 1.21.1 NeoForge'
}
$scenario = if ($WorldZip) { 'trafficfactory' } else { 'trafficprofile' }
if ($ScenarioOverride) {
    if ($WorldZip -and $ScenarioOverride -ne 'sleepfactory') { throw 'This smoke scenario uses its own generated fixture' }
    if ($ScenarioOverride -eq 'sleepfactory' -and (-not $WorldZip -or $Ver -ne '1.21.1')) { throw 'sleepfactory requires the native 1.21.1 world ZIP' }
    $scenario=$ScenarioOverride
}
if ($SessionId -notmatch '^[a-zA-Z0-9_.-]+$') { throw 'Invalid SessionId' }
if (-not (Test-Path -LiteralPath $Dictionary -PathType Leaf)) { throw "Dictionary not found: $Dictionary" }
$output = Join-Path $root "build/traffic-profile/$SessionId"
if (Test-Path -LiteralPath $output) { throw 'Use a new SessionId to preserve previous evidence' }
$resultPath = "$root/build/smoke-test/results/result_$SessionId.json"
if (Test-Path -LiteralPath $resultPath) { throw "Smoke result already exists for $SessionId; use a new SessionId" }
$runRoot = [IO.Path]::GetFullPath((Join-Path $root "$Loader/run"))
# Do not let the smoke harness delete historical crashes.
foreach ($side in @('server','client')) {
    if (Test-Path -LiteralPath (Join-Path $runRoot "$side/crash-reports")) {
        throw "Preserve $Loader/run/$side/crash-reports before running the profile"
    }
}
if (Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 25565 is occupied; finish that session before profiling'
}
New-Item -ItemType Directory -Path "$output/before" -Force | Out-Null
$files = @(
    'client/options.txt', 'client/config/hassium/hassium-client.toml',
    'client/config/hassium/hassium-server.toml', 'server/server.properties',
    'client/config/hassium/sleep-profiles.properties',
    'server/eula.txt', 'server/ops.json', 'server/config/hassium/hassium-server.toml',
    'server/config/hassium/hassium_aggregation_dict.bin',
    'server/config/hassium/aggregation_corpus/retrain.marker'
)
$manifest = foreach ($relative in $files) {
    $path = Join-Path $runRoot $relative
    $exists = Test-Path -LiteralPath $path -PathType Leaf
    if ($exists) {
        $backup = Join-Path "$output/before" $relative
        New-Item -ItemType Directory -Path (Split-Path $backup -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $path -Destination $backup
    }
    [pscustomobject]@{ relative=$relative; existed=$exists }
}
$manifest | ConvertTo-Json | Set-Content "$output/restore-manifest.json" -Encoding utf8NoBOM
Copy-Item -LiteralPath $Dictionary -Destination "$output/dictionary.bin"
Get-FileHash "$output/dictionary.bin" | ConvertTo-Json | Set-Content "$output/dictionary-sha256.json"
if ($WorldZip) {
    Get-FileHash -LiteralPath $WorldZip | ConvertTo-Json | Set-Content "$output/world-sha256.json"
}
$started = Get-Date
$cache = [IO.Path]::GetFullPath((Join-Path $runRoot 'client/hassium_cache'))
$cacheBackup = [IO.Path]::GetFullPath((Join-Path $output 'before/client/hassium_cache'))
$cacheEvidence = [IO.Path]::GetFullPath((Join-Path $output 'client-cache'))
foreach ($path in @($cacheBackup, $cacheEvidence)) {
    if (-not $path.StartsWith($output + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Cache archive outside profile evidence: $path"
    }
}
if ($cache -ne (Join-Path $runRoot 'client/hassium_cache')) { throw 'Unexpected cache path' }
$hadCache = Test-Path -LiteralPath $cache
$cacheMoved = $false
try {
    if ($hadCache) {
        Move-Item -LiteralPath $cache -Destination $cacheBackup -ErrorAction Stop
        $cacheMoved = $true
    }
    Copy-Item -LiteralPath "$output/dictionary.bin" -Destination "$runRoot/server/config/hassium/hassium_aggregation_dict.bin" -Force
    $options = Join-Path $runRoot 'client/options.txt'
    if (Test-Path -LiteralPath $options) {
        $text = Get-Content -LiteralPath $options -Raw
        if ($text -match '(?m)^pauseOnLostFocus:') {
            $text = $text -replace '(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false'
        } else { $text += "`npauseOnLostFocus:false`n" }
        Set-Content -LiteralPath $options -Value $text -Encoding utf8NoBOM
    }
    # Child shell is the harness itself, so its exit cannot bypass this wrapper's finally.
    $args_ = @('-File', "`"$PSScriptRoot/runtime-smoke-test.ps1`"",
        '-Ver', $Ver, '-Loader', $Loader, '-Phase', 'I', '-SessionId', $SessionId,
        '-Scenario', $scenario, '-WarmRepeat', '-Vd1', '10', '-Vd2', '10',
        '-ClientRenderDistance', '10', '-JoinTimeoutMs', '600000',
        '-ClientTimeoutSec', '1500', '-ServerReadyTimeoutSec', '300', '-HangSilenceSec', '180',
        '-ServerPort', '25565', '-CpuIsolation', $CpuIsolation)
    if ($WorldZip) { $args_ += @('-WorldZip', "`"$WorldZip`"") }
    if ($Offline) { $args_ += '-Offline' }
    $process = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList $args_ `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$output/harness.log" -RedirectStandardError "$output/harness.err.log"
    Write-Host "Profile running: PID=$($process.Id) evidence=$output"
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        Write-Error "Smoke harness did not write a result JSON for $SessionId" -ErrorAction Continue
        $exitCode = 2
    } else {
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        if ($result.Result -ne 'PASS' -or $result.Scenario -ne $scenario -or
            $result.Ver -ne $Ver -or $result.Loader -ne $Loader) {
            Write-Error "Smoke result mismatch for ${SessionId}: result=$($result.Result) scenario=$($result.Scenario) version=$($result.Ver) loader=$($result.Loader)" -ErrorAction Continue
            $exitCode = 2
        }
    }
    foreach ($relative in $files) {
        $path = Join-Path $runRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $dest = Join-Path "$output/effective" $relative
            New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $path -Destination $dest
        }
    }
    Get-ChildItem "$runRoot/server/logs/hassium-aggregated-packets/agg-*.jsonl" -ErrorAction SilentlyContinue |
        Where-Object LastWriteTime -ge $started |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $output }
    foreach ($evidence in @("$root/build/smoke-test/logs/server_$SessionId.log", "$root/build/smoke-test/logs/client_$SessionId.log",
        "$root/build/smoke-test/logs/observer_$SessionId.log",
        "$root/build/smoke-test/results/result_$SessionId.json")) {
        if (Test-Path -LiteralPath $evidence) { Copy-Item -LiteralPath $evidence -Destination $output }
    }
    Write-Host "Profile harness exit=$exitCode"
} finally {
    $restoreErrors = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $manifest) {
        try {
            $target = [IO.Path]::GetFullPath((Join-Path $runRoot $entry.relative))
            if (-not $target.StartsWith($runRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Restore target outside run directory: $target"
            }
            if ($entry.existed) {
                Copy-Item -LiteralPath (Join-Path "$output/before" $entry.relative) -Destination $target -Force
            } elseif (Test-Path -LiteralPath $target -PathType Leaf) {
                Remove-Item -LiteralPath $target -Force
            }
        } catch {
            $restoreErrors.Add("$($entry.relative): $($_.Exception.Message)")
        }
    }
    try {
        if ((-not $hadCache -or $cacheMoved) -and (Test-Path -LiteralPath $cache)) {
            Move-Item -LiteralPath $cache -Destination $cacheEvidence -ErrorAction Stop
        }
    } catch {
        $restoreErrors.Add("archive client/hassium_cache: $($_.Exception.Message)")
    }
    try {
        if ($cacheMoved) {
            if (Test-Path -LiteralPath $cache) { throw "New cache still occupies $cache; original remains at $cacheBackup" }
            Move-Item -LiteralPath $cacheBackup -Destination $cache -ErrorAction Stop
        }
    } catch {
        $restoreErrors.Add("restore client/hassium_cache: $($_.Exception.Message)")
    }
    if ($restoreErrors.Count) {
        throw "Profile restoration failed: $($restoreErrors -join '; ')"
    }
    Write-Host 'Original configuration, dictionary and options restored.'
}
exit $exitCode
