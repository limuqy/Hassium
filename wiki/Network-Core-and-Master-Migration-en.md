# Network Architecture (Direct Topology)

---

> **English**: [Network-Core-and-Master-Migration](Network-Core-and-Master-Migration) · English

> **This page was rewritten for the 2.0.0 direct-connection topology.** The historical "in-process gateway / seamless master migration / L1 load balancing / UDP data plane" were retired in 2.0.0 (decision record: repo [`docs/handoff/handoff-2026-09-04-vanilla-direct-network.md`](https://github.com/limuqy/Hassium/blob/master/docs/handoff/handoff-2026-09-04-vanilla-direct-network.md); archived research: [`docs/archive/multi-channel_network_research.md`](https://github.com/limuqy/Hassium/blob/master/docs/archive/multi-channel_network_research.md)).

## Current topology: a single vanilla TCP

The client and server share **exactly one vanilla TCP connection** (the game port):

| Phase | Carrier | Notes |
| --- | --- | --- |
| Login | vanilla login custom query (1.20.1 `hassium:login_hello`) | The server sends the query; the client answers with capability bits; a vanilla client always answers empty → vanilla path, **no timeout dependency, zero interference** |
| Configuration (1.20.2+) | vanilla configuration payload (`PreHandshakePayload`) | Pre-handshake after authentication, replacing the login query whose answer body vanilla codecs drop on 1.20.2+ |
| Play | `hassium:*` custom payloads over the vanilla channel | Chunks/entities/business all ride the vanilla channel; the vanilla compression layer is never touched |

### Capability negotiation and activation

1. Login/config-phase capability bits are ANDed (agg/delta/seed/light/pull/shadow_pull/pull_mode) and recorded in `PlayerCompressionTracker`
2. `ServerPlayer <init>` TAIL consumes the negotiated caps (suppresses the vanilla chunk window)
3. Tick-pump activation: dictionary_sync/index_sync → aggregation PENDING (5s ACK timeout downgrades to direct send) → `play_init_s2c` (caps + SeedGen seed)
4. Client ACKs after index_sync → aggregation ENABLED

### Channel compression

- **Exactly two sites, both off the vanilla compression layer**: dictionary ZSTD inside aggregated packets (EventLoop threshold flip prevents double compression) + chunk-push native compression
- Pipeline-level global packet compression (`master.globalPacketCompression` etc., 4 keys) retired
- Control plane (handshake, index sync, chunkHash, …) sits on the compression blacklist and bypasses the PENDING aggregation buffer

### Pull mode

After negotiation (`pull_mode` capability) the server stops pushing full chunk payloads to that player (forget/metadata/SeedRef continue); chunk data is fetched by the unified Compare+Pull driven by the client shadow virtual player's vanilla tracking (`ShadowPull`: UNCHANGED / DELTA / FULL / ERROR). See [Features](Features-en).

---

## Retired capabilities (historical reference)

| Capability | Status | Notes |
| --- | --- | --- |
| In-process gateway (NetworkCore, `network/core/`) | **retired** | The shell-connection + private-channel architecture is gone; I/O returned to the vanilla Netty path |
| Seamless master migration (ResumeTicket / MigrationEngine) | **retired** | Resume tickets, warmup, idle windows, all four triggers gone; disconnects use vanilla reconnect + shadow-cache reuse |
| L1 load balancing (`master.migration*`, 7 keys) | **retired** | Keys deleted |
| UDP data plane (`network/dataplane/`) | **retired** | `dataplane.*` keys deleted |
| Client failover (candidate endpoints / recovery UI) | **retired** (`729d92e`) | Disconnect/reconnect uses the vanilla path |
| `/hassium migrate` command | **removed** | Retired with the migration engine |
| Gateway listeners/auth (`controlReachableEndpoints` / `bindHost` / `authToken`) | **removed** | No gateway/UDP ports to open |

**Disconnect/reconnect today**: client disconnect → vanilla reconnect → the shadow world cache (`hassium_cache/<serverId>/world`) is reused by chunkHash — unchanged chunks hit the cache (UNCHANGED), changed chunks arrive as section delta (DELTA); no full re-download.

---

## Configuration

Current network-related keys (full table: [Configuration](Configuration-en)):

| Key | Default | Description |
| --- | --- | --- |
| `master.enabled` | `true` | Server network-channel master switch (login handshake/aggregation gate) |
| `chunk.enabled` | `true` | Client chunk-core master switch (Pull mode / shadow / cache gate) |
| `master.enablePacketAggregation` | `true` | Packet aggregation |
| `master.aggregationMaxWaitTimeMs` | `50` | Aggregation max wait (ms; ACK timeout 5s downgrades to direct send) |
| `master.compressionBlacklist` | control-plane keys | Compression/aggregation blacklist |

Public deployments only need the game port (vanilla TCP); no gateway/UDP ports.

---

## Related pages

[← Support-Matrix](Support-Matrix-en) · [Home](Home-en) · [→ FAQ](FAQ-en)
