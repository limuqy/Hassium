# runtime-smoke-test.Tests.ps1 — Pester tests for Task 9 Nginx failover smoke helpers.
#
# Source: Plan §1, §4 Step 4 (docs/superpowers/plans/2026-07-27-unived-endpoint-model-and-nginx-smoke.md).
# Covers `New-UdpFailoverNginxConfig` and `Get-UdpFailoverMarkers` from the
# `UdpFailoverSmoke.psm1` module. Pure-function tests, no Nginx binary required.

# Pester v3/v5 dual-compatible Describe/It; prefer v3 (`Should Be`/`Should Match`)
# over `Should -BeTrue`/`Should -Match` since the host only ships Pester 3.4.0.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$modulePath = Join-Path $here 'UdpFailoverSmoke.psm1'
if (-not (Test-Path $modulePath)) {
    throw "Module not found at $modulePath (Task 9 §1 rollout incomplete)."
}

# Import the helper module into the test scope so all functions are accessible.
if (Get-Module UdpFailoverSmoke) { Remove-Module UdpFailoverSmoke -ErrorAction SilentlyContinue }
Import-Module $modulePath -Force -ErrorAction Stop

Describe 'UdpFailoverSmoke.New-UdpFailoverNginxConfig' {
    It 'produces a config containing the listen line for the proxy port' {
        $conf = New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565
        ($conf -match 'listen 127\.0\.0\.1:25570') | Should Be $true
        ($conf -match 'server 127\.0\.0\.1:25565')   | Should Be $true
    }

    It 'only contains a stream block (no http, no udp, no ssl directives)' {
        $conf = New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565
        ($conf -split "`n" | Where-Object { $_ -match '^\s*(http|udp|ssl)\s*\{' }).Count | Should Be 0
        ($conf -split "`n" | Where-Object { $_ -match '^\s*stream\s*\{' }).Count | Should Be 1
    }

    It 'respects -PrimaryHost and -ListenHost overrides' {
        $conf = New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565 -PrimaryHost '10.0.0.7' -ListenHost '0.0.0.0'
        ($conf -match 'listen 0\.0\.0\.0:25570')    | Should Be $true
        ($conf -match 'server 10\.0\.0\.7:25565') | Should Be $true
    }

    It 'throws when listen and primary share the same port (self-proxy forbidden)' {
        { New-UdpFailoverNginxConfig -ListenPort 25565 -PrimaryPort 25565 } |
            Should Throw
    }

    It 'throws when either host is empty' {
        { New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565 -PrimaryHost '' } |
            Should Throw
        { New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565 -ListenHost '' } |
            Should Throw
    }

    It 'accepts ports at valid 1-65535 range bounds and rejects out-of-range' {
        { New-UdpFailoverNginxConfig -ListenPort 1 -PrimaryPort 65535 } |
            Should Not Throw
        { New-UdpFailoverNginxConfig -ListenPort 0 -PrimaryPort 65535 } |
            Should Throw
        { New-UdpFailoverNginxConfig -ListenPort 70000 -PrimaryPort 65535 } |
            Should Throw
    }
}

Describe 'UdpFailoverSmoke.Get-UdpFailoverMarkers' {
    $serverSample = @"
HassiumSmokeTest:UDP_FAILOVER UDP_BIND_OK endpoints=2
HassiumSmokeTest:UDP_FAILOVER UDP_WRR_OK endpointId=0
HassiumSmokeTest:UDP_FAILOVER FAILOVER_PERMIT_OK epoch=7 expiryMs=10000
"@
    $clientSample = @"
HassiumSmokeTest:UDP_FAILOVER CACHE_RESUME_HIT hitChunks=12
HassiumSmokeTest:UDP_FAILOVER FAILOVER_RECONNECT_OK epoch=7
"@

    It 'returns a 6-key hashtable with all keys present even for empty logs' {
        $markers = Get-UdpFailoverMarkers -ClientLog '' -ServerLog ''
        $markers.ContainsKey('UDP_BIND_OK')           | Should Be $true
        $markers.ContainsKey('UDP_WRR_OK')            | Should Be $true
        $markers.ContainsKey('FAILOVER_PERMIT_OK')    | Should Be $true
        $markers.ContainsKey('FAILOVER_RECONNECT_OK') | Should Be $true
        $markers.ContainsKey('FAILOVER_TERMINAL_OK')  | Should Be $true
        $markers.ContainsKey('CACHE_RESUME_HIT')      | Should Be $true
        @($markers.Keys).Count | Should Be 6
    }

    It 'reports all-false for empty logs without crashing' {
        $markers = Get-UdpFailoverMarkers -ClientLog '' -ServerLog ''
        foreach ($v in $markers.Values) { $v | Should Be $false }
    }

    It 'flags UDP_BIND_OK / UDP_WRR_OK / FAILOVER_PERMIT_OK from the server log' {
        $markers = Get-UdpFailoverMarkers -ClientLog "" -ServerLog $serverSample
        $markers.UDP_BIND_OK         | Should Be $true
        $markers.UDP_WRR_OK          | Should Be $true
        $markers.FAILOVER_PERMIT_OK  | Should Be $true
        $markers.CACHE_RESUME_HIT    | Should Be $false
        $markers.FAILOVER_RECONNECT_OK | Should Be $false
    }

    It 'flags CACHE_RESUME_HIT / FAILOVER_RECONNECT_OK from the client log' {
        $markers = Get-UdpFailoverMarkers -ClientLog $clientSample -ServerLog ""
        $markers.CACHE_RESUME_HIT      | Should Be $true
        $markers.FAILOVER_RECONNECT_OK | Should Be $true
        $markers.UDP_BIND_OK           | Should Be $false
    }

    It 'accepts a marker appearing in either log (Reconnect lives on client here, Permit on server)' {
        $markers = Get-UdpFailoverMarkers -ClientLog $clientSample -ServerLog $serverSample
        $markers.UDP_BIND_OK         | Should Be $true
        $markers.UDP_WRR_OK          | Should Be $true
        $markers.FAILOVER_PERMIT_OK  | Should Be $true
        $markers.FAILOVER_RECONNECT_OK | Should Be $true
        $markers.CACHE_RESUME_HIT    | Should Be $true
        $markers.FAILOVER_TERMINAL_OK | Should Be $false
    }

    It 'does not match markers when the HassiumSmokeTest:UDP_FAILOVER prefix is absent' {
        # Loose substring without prefix must not count.
        $wrongServer = "UDP_BIND_OK endpoints=2"
        $wrongClient = "CACHE_RESUME_HIT hitChunks=12"
        $markers = Get-UdpFailoverMarkers -ClientLog $wrongClient -ServerLog $wrongServer
        foreach ($v in $markers.Values) { $v | Should Be $false }
    }
}

Describe 'UdpFailoverSmoke.Get-UdpFailoverHarnessTimeline' {
    $harnessSample = @"
HassiumSmokeTest:UDP_FAILOVER_HARNESS nginxStarted at=1750000000000
HassiumSmokeTest:UDP_FAILOVER_HARNESS primaryCloseInjected at=1750000005000
HassiumSmokeTest:UDP_FAILOVER_HARNESS primaryRestored at=1750000010000
HassiumSmokeTest:UDP_FAILOVER_HARNESS nginxQuit at=1750000015000
"@
    It 'parses the four harness timeline events into long millis' {
        $tl = Get-UdpFailoverHarnessTimeline -HarnessLog $harnessSample
        $tl.nginxStartedAt         | Should Be 1750000000000
        $tl.primaryCloseInjectedAt | Should Be 1750000005000
        $tl.primaryRestoredAt      | Should Be 1750000010000
        $tl.nginxQuitAt            | Should Be 1750000015000
    }

    It 'returns nulls for missing events instead of crashing' {
        $tl = Get-UdpFailoverHarnessTimeline -HarnessLog "no harness log content"
        $tl.nginxStartedAt         | Should Be $null
        $tl.primaryCloseInjectedAt | Should Be $null
        $tl.primaryRestoredAt      | Should Be $null
        $tl.nginxQuitAt            | Should Be $null
    }
}

Describe 'UdpFailoverSmoke.DryRun-and-default-invariants' {
    It 'parses a minimal DryRun-only timeline: nginxStarted set, no close/quit/restored' {
        # DryRun 模式：仅 Start → Stop-FailoverNginxProxy，不进入 InjectTcpClose 流程
        $dryRunSample = @"
HassiumSmokeTest:UDP_FAILOVER_HARNESS nginxStarted at=1750000100000
"@
        $tl = Get-UdpFailoverHarnessTimeline -HarnessLog $dryRunSample
        $tl.nginxStartedAt         | Should Be 1750000100000
        $tl.primaryCloseInjectedAt | Should Be $null
        $tl.nginxQuitAt            | Should Be $null
        $tl.primaryRestoredAt      | Should Be $null
    }

    It 'parses an InjectTcpClose timeline preserving temporal order (close before quit before restored)' {
        # 真实 RST 注入序列：closeInjected → nginxQuit → primaryRestored
        $injectSample = @"
HassiumSmokeTest:UDP_FAILOVER_HARNESS nginxStarted at=1750000200000
HassiumSmokeTest:UDP_FAILOVER_HARNESS primaryCloseInjected at=1750000205000
HassiumSmokeTest:UDP_FAILOVER_HARNESS nginxQuit at=1750000205500
HassiumSmokeTest:UDP_FAILOVER_HARNESS primaryRestored at=1750000210000
"@
        $tl = Get-UdpFailoverHarnessTimeline -HarnessLog $injectSample
        $tl.nginxStartedAt         | Should Be 1750000200000
        $tl.primaryCloseInjectedAt | Should Be 1750000205000
        $tl.nginxQuitAt            | Should Be 1750000205500
        $tl.primaryRestoredAt      | Should Be 1750000210000
        # Temporal invariant: close < quit < restored
        ($tl.primaryCloseInjectedAt -lt $tl.nginxQuitAt)        | Should Be $true
        ($tl.nginxQuitAt -lt $tl.primaryRestoredAt)             | Should Be $true
    }
}
