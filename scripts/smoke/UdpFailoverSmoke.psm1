# UdpFailoverSmoke.psm1 — Task 9 Nginx failover smoke harness helpers.
#
# Pure-text generation and marker extraction, side-effect-free:
#   - New-UdpFailoverNginxConfig generates a stream-block config suitable for
#     `nginx -c <conf> -p <prefix>`; never proxies UDP; never enables HTTP.
#   - Get-UdpFailoverMarkers aggregates server/client log content into a six-key
#     bool hashtable. The harness owns invocation; it never writes markers.
#
# Source: Plan docs/superpowers/plans/2026-07-27-unified-endpoint-model-and-nginx-smoke.md Task 9.
# Red lines: only call vanilla network entrypoints; never reflect login
# protocol; never assert write `HassiumSmokeTest:*` markers.

Set-StrictMode -Version 3.0

<#
.SYNOPSIS
  Build a minimal Nginx stream config that fronts the Minecraft primary TCP
  listener with a local proxy port for failover smoke injection.

.DESCRIPTION
  The config only contains a `stream { upstream minecraft_primary { server ... }
  server { listen ... proxy_pass ... } }` block. HTTP and any UDP proxy are
  explicitly NOT enabled (UDP data traffic stays direct client↔server). The
  upstream target is `127.0.0.1:<PrimaryPort>` and the listen address is
  `127.0.0.1:<ListenPort>`. The returned string has no trailing newline.

.PARAMETER ListenPort
  Local TCP port Nginx binds. Clients connect to this port for the smoke.
  Must be a positive int != PrimaryPort.

.PARAMETER PrimaryPort
  The Minecraft server's real TCP port that Nginx proxies to.

.PARAMETER PrimaryHost
  Optional upstream host; defaults to '127.0.0.1' (dev smoke only).

.PARAMETER ListenHost
  Optional listen host; defaults to '127.0.0.1'.

.OUTPUTS
  System.String — full Nginx config text.

.EXAMPLE
  $conf = New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565
  $conf | Should Match 'listen 127.0.0.1:25570'
  $conf | Should Match 'server 127.0.0.1:25565'
#>
function New-UdpFailoverNginxConfig {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$ListenPort,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$PrimaryPort,
        [string]$PrimaryHost = '127.0.0.1',
        [string]$ListenHost = '127.0.0.1'
    )
    if ($ListenPort -eq $PrimaryPort) {
        throw "ListenPort ($ListenPort) must differ from PrimaryPort ($PrimaryPort); Nginx cannot proxy a port to itself."
    }
    if ([string]::IsNullOrWhiteSpace($PrimaryHost)) { throw "PrimaryHost must not be empty." }
    if ([string]::IsNullOrWhiteSpace($ListenHost))  { throw "ListenHost must not be empty." }

    # No HTTP block, no UDP block, no SSL block — only `stream` for the TCP primary.
    # Use ${var}:${var} (not $var:$var) — PS errors on bare $var: inside a here-string
    # because ':' is parsed as a drive scope. Braces disambiguate.
    return @"
events {
    worker_connections 256;
}
stream {
    upstream minecraft_primary {
        server ${PrimaryHost}:${PrimaryPort};
    }
    server {
        listen ${ListenHost}:${ListenPort};
        proxy_pass minecraft_primary;
        proxy_connect_timeout 5s;
        proxy_timeout 600s;
    }
}
"@
}

<#
.SYNOPSIS
  Reconcile the six production failover markers across server and client logs.

.DESCRIPTION
  Each marker is produced by the production path only; this helper just scans
  both logs for `HassiumSmokeTest:UDP_FAILOVER <name>` occurrences. UDP_BIND_OK
  / UDP_WRR_OK appear in the server log; CACHE_RESUME_HIT appears in the client
  log; FAILOVER_PERMIT_OK / FAILOVER_RECONNECT_OK / FAILOVER_TERMINAL_OK may
  appear in either path depending on epoch and loader wire-up.

  Returns a hashtable with one bool per marker; never returns null/missing
  keys. A marker is True if it appears in either log. The helper does not
  write any markers and does not synthesise Pester-only shortcuts.

.PARAMETER ClientLog
  Client log path or pre-read content. Empty/missing logs contribute no hits.

.PARAMETER ServerLog
  Server log path or pre-read content. Empty/missing logs contribute no hits.

.OUTPUTS
  [Hashtable] — keys UDP_BIND_OK / UDP_WRR_OK / FAILOVER_PERMIT_OK /
  FAILOVER_RECONNECT_OK / FAILOVER_TERMINAL_OK / CACHE_RESUME_HIT (all bool).

.EXAMPLE
  $markers = Get-UdpFailoverMarkers -ClientLog $clientPath -ServerLog $serverPath
  $markers.UDP_BIND_OK | Should Be $true
#>
function Get-UdpFailoverMarkers {
    [CmdletBinding()]
    [OutputType([hashtable])]
    param(
        [Parameter(Mandatory = $true)]$ClientLog,
        [Parameter(Mandatory = $true)]$ServerLog
    )

    function ConvertTo-LogText {
        param($Source)
        if (-not $Source) { return '' }
        if ($Source -is [string] -and (Test-Path $Source -ErrorAction SilentlyContinue)) {
            return (Get-Content $Source -Raw -ErrorAction SilentlyContinue) -as [string]
        }
        if ($Source -is [string]) { return $Source }
        # Already an object/array of lines or text.
        return ($Source | Out-String)
    }

    $clientText = ConvertTo-LogText $ClientLog
    $serverText = ConvertTo-LogText $ServerLog

    $markerNames = @(
        'UDP_BIND_OK',
        'UDP_WRR_OK',
        'FAILOVER_PERMIT_OK',
        'FAILOVER_RECONNECT_OK',
        'FAILOVER_TERMINAL_OK',
        'CACHE_RESUME_HIT'
    )
    $result = @{}
    # Per-line match: split on CRLF/LF then anchored regex per line.
    # Must NOT silently let \s+ span the line break, otherwise the prefix-less
 # second hit (line `CACHE_RESUME_HIT ...`) would couple to the previous
    # line's `HassiumSmokeTest:UDP_FAILOVER <name>` via the inner separator.
    function TestMarkerPresent {
        param([string]$Text, [string]$Name)
        if ([string]::IsNullOrEmpty($Text)) { return $false }
        $anchored = '^HassiumSmokeTest:UDP_FAILOVER\s+' + [regex]::Escape($Name) + '\b'
        foreach ($line in ($Text -split "`r?`n")) {
            if ($line -match $anchored) { return $true }
        }
        return $false
    }
    foreach ($name in $markerNames) {
        $hitClient = TestMarkerPresent -Text $clientText -Name $name
        $hitServer = TestMarkerPresent -Text $serverText -Name $name
        $result[$name] = [bool]($hitClient -or $hitServer)
    }
    return $result
}

<#
.SYNOPSIS
  Compute the harness-managed timeline markers for the four sub-scenarios.

.DESCRIPTION
  The smoke harness emits its own `HassiumSmokeTest:UDP_FAILOVER_HARNESS <event>
  at=<unix-ms>` lines so the smoke reviewer can correlate Nginx reloads with
  marker production. The harness events are not production markers and must
  never appear in the server or Minecraft code; they only live in
  `runtime-smoke-test.ps1`.

  This helper exists for unit testing the timeline parsing logic in isolation
  from an actual run. The smoke phase script emits these lines itself.

.PARAMETER HarnessLog
  Harness-managed timeline log path or pre-read content.

.OUTPUTS
  [Hashtable] — keys: nginxStartedAt, nginxQuitAt, primaryCloseInjectedAt,
  primaryRestoredAt (each [long?] unix-millis or $null if absent).
#>
function Get-UdpFailoverHarnessTimeline {
    [CmdletBinding()]
    [OutputType([hashtable])]
    param(
        [Parameter(Mandatory = $true)]$HarnessLog
    )

    function ConvertTo-LogText {
        param($Source)
        if (-not $Source) { return '' }
        if ($Source -is [string] -and (Test-Path $Source -ErrorAction SilentlyContinue)) {
            return (Get-Content $Source -Raw -ErrorAction SilentlyContinue) -as [string]
        }
        if ($Source -is [string]) { return $Source }
        return ($Source | Out-String)
    }

    $text = ConvertTo-LogText $HarnessLog
    $events = @{
        nginxStartedAt         = $null
        nginxQuitAt            = $null
        primaryCloseInjectedAt = $null
        primaryRestoredAt      = $null
    }
    foreach ($event in @('nginxStarted', 'nginxQuit', 'primaryCloseInjected', 'primaryRestored')) {
        if ($text -match "HassiumSmokeTest:UDP_FAILOVER_HARNESS\s+$event\s+at=(\d+)") {
            $events[$event + 'At'] = [long]$matches[1]
        }
    }
    return $events
}

<#
.SYNOPSIS
  Extract the client recovery mode evidence from the smoke client log.

.DESCRIPTION
  The client emits `HassiumSmokeTest:CLIENT_MODE recoveryFreeze=<bool>` at
  init (production config path only). In seamless runs the harness requires
  the value to be `false` — otherwise the toml patch failed and the run did
  not exercise the seamless code path. Returns 'true' / 'false' / 'unknown'.

.PARAMETER ClientLog
  Client log path or pre-read content. Empty/missing logs yield 'unknown'.

.OUTPUTS
  [string] — 'true', 'false', or 'unknown'.
#>
function Get-UdpFailoverClientMode {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Position = 0)][AllowNull()]$ClientLog
    )
    $text = if ([string]::IsNullOrEmpty($ClientLog)) {
        ''
    } elseif ($ClientLog -is [string] -and (Test-Path $ClientLog -ErrorAction SilentlyContinue)) {
        (Get-Content $ClientLog -Raw -ErrorAction SilentlyContinue) -as [string]
    } elseif ($ClientLog -is [string]) {
        $ClientLog
    } else {
        ($ClientLog | Out-String)
    }
    if ([string]::IsNullOrEmpty($text)) { return 'unknown' }
    if ($text -match '^HassiumSmokeTest:CLIENT_MODE recoveryFreeze=(\w+)$' -or
        $text -match 'HassiumSmokeTest:CLIENT_MODE recoveryFreeze=(\w+)') {
        $value = $matches[1]
        if ($value -eq 'true' -or $value -eq 'false') { return $value }
    }
    return 'unknown'
}

Export-ModuleMember -Function New-UdpFailoverNginxConfig, Get-UdpFailoverMarkers, Get-UdpFailoverHarnessTimeline, Get-UdpFailoverClientMode
