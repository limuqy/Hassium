param(
    [Parameter(Mandatory=$true)][string]$Ver,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$SessionId,
    [string]$SourceWorld = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repo 'fabric/run/client'
if (-not $SourceWorld) { $SourceWorld = Join-Path $repo "fabric/run/server/parity_fabric_$($Ver.Replace('.', '_'))" }
if (-not (Test-Path -LiteralPath (Join-Path $SourceWorld 'level.dat'))) {
    throw "Run the dedicated Fabric smoke for $Ver first, or pass -SourceWorld with its fixture world."
}
$worldName = "isolation_$SessionId"
$world = Join-Path $runDir "saves/$worldName"
if (Test-Path -LiteralPath $world) { throw "Test world already exists: $world. Use a new SessionId." }
New-Item -ItemType Directory -Path (Split-Path -Parent $world) -Force | Out-Null
Copy-Item -LiteralPath $SourceWorld -Destination $world -Recurse
$outputDir = Join-Path $repo "build/smoke-test/probe/$SessionId"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$log = Join-Path $repo "build/smoke-test/logs/client_$SessionId.log"
New-Item -ItemType Directory -Path (Split-Path -Parent $log) -Force | Out-Null
$backups = @{}
function Set-TestConfig([string]$file, [string]$section, [string]$key, [string]$value) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing generated config: $file" }
    if (-not $backups.ContainsKey($file)) { $backups[$file] = [IO.File]::ReadAllBytes($file) }
    $inSection = $false
    $found = $false
    $lines = foreach ($line in [IO.File]::ReadAllLines($file)) {
        if ($line -match '^\s*\[([^]]+)\]') { $inSection = $Matches[1] -eq $section }
        if ($inSection -and $line -match "^\s*$([regex]::Escape($key))\s*=") {
            "$key = $value"; $found = $true
        } else { $line }
    }
    if (-not $found) { throw "Missing config key [$section] $key in $file" }
    [IO.File]::WriteAllLines($file, $lines, [Text.UTF8Encoding]::new($false))
}
$clientConfig = Join-Path $runDir 'config/hassium/hassium-client.toml'
$serverConfig = Join-Path $runDir 'config/hassium/hassium-server.toml'
try {
    # Deliberately enable the features whose dedicated-only/real-world guards are under test.
    Set-TestConfig $clientConfig 'chunk' 'enabled' 'true'
    Set-TestConfig $serverConfig 'storage' 'enabled' 'true'
    Push-Location $repo
    try {
        & .\gradlew.bat --no-daemon fabric:runClient "-Pmc_ver=$Ver" '-PhassiumSmokeTest=true' `
            '-PhassiumSmokeScenario=integrated' "-PhassiumSmokeSingleplayer=$worldName" `
            "-PhassiumSmokeProbeDir=$outputDir" *> $log
        $gameExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    foreach ($file in $backups.Keys) { [IO.File]::WriteAllBytes($file, $backups[$file]) }
}
$probePath = Join-Path $outputDir 'round1.json'
$probe = if (Test-Path -LiteralPath $probePath) { Get-Content -LiteralPath $probePath -Raw | ConvertFrom-Json } else { $null }
$types = @{}
$columns = 0
foreach ($region in Get-ChildItem -LiteralPath $world -Recurse -Filter '*.mca' -File) {
    if ($region.Length -eq 0) { continue } # Vanilla may leave newly opened empty POI/entity regions.
    if ($region.Length -lt 8192) { throw "Truncated region: $($region.FullName)" }
    $stream = [IO.File]::OpenRead($region.FullName)
    try {
        $header = [byte[]]::new(4096)
        $stream.ReadExactly($header)
        for ($i=0; $i -lt 1024; $i++) {
            $offset = ([int]$header[$i*4] -shl 16) -bor ([int]$header[$i*4+1] -shl 8) -bor [int]$header[$i*4+2]
            if ($offset -eq 0) { continue }
            $stream.Position = [long]$offset * 4096 + 4
            $type = $stream.ReadByte() -band 127
            $types[[string]$type] = 1 + $types[[string]$type]
            $columns++
        }
    } finally { $stream.Dispose() }
}
$text = Get-Content -LiteralPath $log -Raw
$pass = $gameExit -eq 0 -and $text.Contains('HassiumSmokeTest:PASS') -and $probe `
    -and $probe.isolation.integrated -and -not $probe.isolation.shadowRunning `
    -and $probe.isolation.processShadowFlag -and $probe.isolation.scheduledChecks -eq 2 `
    -and $probe.clientCache.loadedChunks -gt 64 -and $columns -gt 0 -and -not $types.ContainsKey('126')
$result = [ordered]@{ SessionId=$SessionId; Ver=$Ver; Scenario='integrated'; Pass=[bool]$pass;
    ClientExit=$gameExit; World=$world; Probe=$probePath; Columns=$columns; CompressionTypes=$types; Log=$log }
$resultDir = Join-Path $repo 'build/smoke-test/results'
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $resultDir "result_$SessionId.json") -Encoding utf8NoBOM
Write-Output "[$SessionId] columns=$columns types=$($types | ConvertTo-Json -Compress) log=$log"
Write-Output "[$SessionId] === RESULT: $(if ($pass) {'PASS'} else {'FAIL'}) ==="
if (-not $pass) { Get-Content -LiteralPath $log -Tail 60; exit 2 }
