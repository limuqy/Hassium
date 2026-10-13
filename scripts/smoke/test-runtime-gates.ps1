$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-gates.ps1')
if (Test-SmokeLaunchFailure 2 @{ HasFail = $true; Round1Stats = $false }) { throw 'pre-dump assertion failure must not retry' }
if (Test-SmokeLaunchFailure 2 @{ HasFail = $false; Round1Stats = $true }) { throw 'entered-world failure must not retry' }
if (-not (Test-SmokeLaunchFailure 3 @{ Reason = 'server_not_ready' })) { throw 'server startup failure should retry' }
if (Test-SmokeLaunchFailure 3 @{ Reason = 'loader_not_supported' }) { throw 'unsupported loader must not retry' }

$allow = Get-SmokeEnvironmentAuditAllowPatterns
$message = '[20:01:21] [main/ERROR] (HkeyPerformanceDataUtil) Unable to locate English counter names in registry Perflib 009. Counters may need to be rebuilt: '
foreach ($logger in @('(HkeyPerformanceDataUtil)', '[os.dr.wi.re.HkeyPerformanceDataUtil/]:', '[oshi.driver.windows.registry.HkeyPerformanceDataUtil/]:')) {
    $sample = $message.Replace('(HkeyPerformanceDataUtil)', $logger)
    if ($sample -notmatch $allow) { throw "known OSHI startup error was not matched: $logger" }
    if (($sample -replace '/ERROR', '/FATAL') -match $allow) { throw 'FATAL must not be exempted' }
    if (($sample -replace 'Perflib 009', 'Perflib 010') -match $allow) { throw 'another registry error must not be exempted' }
    if (($sample -replace 'main/ERROR', 'Render thread/ERROR') -match $allow) { throw 'runtime error must not be exempted' }
    if (($sample -replace 'HkeyPerformanceDataUtil', 'OtherLogger') -match $allow) { throw 'unrelated logger must not be exempted' }
}
if (Test-SmokeGracefulStopMarker 'HassiumSmokeTest:PASS') { throw 'PASS alone must not disable the watchdog' }
if (-not (Test-SmokeGracefulStopMarker '[Render thread/INFO] (Minecraft) Stopping!')) { throw 'Fabric stop marker missing' }
if (-not (Test-SmokeGracefulStopMarker '[Render thread/INFO] [minecraft/Minecraft]: Stopping!')) { throw 'NeoForge stop marker missing' }

$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../build/smoke-test'))
$fixture = Join-Path $fixtureRoot ('gate-fixtures-' + [guid]::NewGuid().ToString('N'))
try {
    $clientRun = Join-Path $fixture 'client'
    Set-SmokeClientAutomationSettings $clientRun 'neoforge'
    $options = Join-Path $clientRun 'options.txt'
    $neoConfig = Join-Path $clientRun 'config/neoforge-client.toml'
    if ((Get-Content $options -Raw).Trim() -ne 'pauseOnLostFocus:false') { throw 'fresh smoke options missing' }
    Set-Content $options @('renderDistance:20', 'pauseOnLostFocus:true', 'toggleSprint:false')
    Set-Content $neoConfig @('# retained', 'showLoadWarnings = true', 'enhancedLighting = true')
    Set-SmokeClientAutomationSettings $clientRun 'neoforge'
    Set-SmokeClientAutomationSettings $clientRun 'neoforge'
    if (@(Get-Content $options).Count -ne 3 -or (Get-Content $options -Raw) -notmatch 'pauseOnLostFocus:false') { throw 'options patch must be idempotent' }
    if ((Get-Content $neoConfig -Raw) -notmatch 'showLoadWarnings = false' -or (Get-Content $neoConfig -Raw) -notmatch 'enhancedLighting = true') { throw 'NeoForge config patch must preserve unrelated settings' }
    if ([IO.File]::ReadAllBytes($neoConfig)[0] -eq 0xEF) { throw 'NeoForge config must not have a BOM' }
    $fabricRun = Join-Path $fixture 'fabric-client'
    Set-SmokeClientAutomationSettings $fabricRun 'fabric'
    if (Test-Path (Join-Path $fabricRun 'config/neoforge-client.toml')) { throw 'Fabric must not acquire NeoForge config' }
    $world = Join-Path $clientRun 'hassium_cache/server/world'
    foreach ($dim in @('overworld', 'the_nether', 'the_end')) {
        $region = Join-Path $world "dimensions/minecraft/$dim/region"
        New-Item -ItemType Directory -Force -Path $region | Out-Null
        Set-Content (Join-Path $region 'r.0.0.mca') 'fixture'
        Set-Content (Join-Path $region 'unrelated.txt') 'ignore'
    }
    $snapshot = Get-SmokePostExitDiskSnapshot 'fixture' $clientRun @{ worldDir = $world }
    if (-not $snapshot.captured) { throw '26.x layout was not captured' }
    foreach ($dim in @('overworld', 'nether', 'end')) {
        if ($snapshot.dimensions[$dim].regionFileCount -ne 1) { throw "incorrect mca count for $dim" }
    }
    $relative = Get-SmokePostExitDiskSnapshot 'fixture' $clientRun @{ worldDir = './hassium_cache/server/world' }
    if (-not $relative.captured -or $relative.worldDir -ne $snapshot.worldDir) { throw 'NeoForge relative game path must resolve against ClientRunDir' }
    $escaped = Get-SmokePostExitDiskSnapshot 'fixture' $clientRun @{ worldDir = '../other/world' }
    if ($escaped.captured) { throw 'relative path outside cache must fail closed' }
    $outside = Get-SmokePostExitDiskSnapshot 'fixture' $clientRun @{ worldDir = $fixtureRoot }
    if ($outside.captured) { throw 'another directory must not satisfy the disk gate' }
    $missing = Get-SmokePostExitDiskSnapshot 'fixture' $clientRun @{}
    if ($missing.captured) { throw 'missing probe path must fail closed' }
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'fixture cleanup escaped smoke-test workspace'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
Write-Output 'runtime-gates: PASS'
