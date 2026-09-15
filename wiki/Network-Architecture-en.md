# Network architecture (direct topology)

---

> **English** · 中文: [Network-Architecture](Network-Architecture)

> **Since 2.0.0 Hassium is back on the direct topology**: the historical in-process gateway (network core) / seamless master migration / L1 load balancing / UDP data plane / client failover have all been retired (decision record: [`docs/handoff/handoff-2026-09-04-vanilla-direct-network.md`](https://github.com/limuqy/Hassium/blob/master/docs/handoff/handoff-2026-09-04-vanilla-direct-network.md)).

## Current topology: a single vanilla TCP connection

There is **exactly one vanilla TCP connection** between client and server (the game port):

| Phase | Carrier | Notes |
| --- | --- | --- |
| Login (1.20.1) | vanilla login custom query (`hassium:login_hello`) | The server sends the query after LoginCompression and before GameProfile; the client answers with its capability bits. Vanilla clients always answer empty → server takes the vanilla path, **no timeout dependency and zero interference** |
| Configuration (1.21.1+) | vanilla configuration payloads (S2C `hassium:prehandshake_hello_s2c` / C2S `PreHandshakePayload`) | NeoForge / Forge send the hello from a server configuration task and the client answers synchronously in its handler; Fabric clients announce proactively on configuration START |
| Play | `hassium:*` custom payloads over the vanilla channel | Chunks and business data all travel the vanilla channel; the vanilla compression layer is never touched |

### Capability negotiation and activation chain

1. Capability bits are negotiated bitwise during the login/configuration phase (agg/delta/seed/light/pull/shadow_pull/pull_mode); the result is stored in `PlayerCompressionTracker`
2. `ServerPlayer <init>` TAIL consumes the negotiated bits (suppressing the vanilla chunk window)
3. Tick pump activation: dictionary_sync/index_sync → aggregation PENDING (falls back to direct send if no ACK within 5s) → `play_init_s2c` (negotiated bits + SeedGen seed)
4. After the client's index_sync it returns an activation ACK → aggregation ENABLED

### Channel compression

- **Only two places, and neither touches the vanilla compression layer**: dictionary ZSTD inside aggregated packets (the EventLoop threshold folds to avoid double compression) + the chunk-push's own compression
- Control-plane packets (handshake, index sync, chunkHash, etc.) are blacklisted and never enter the PENDING aggregation buffer

### Pull mode

Once negotiated (`pull_mode` capability) the server stops pushing full chunk payloads to that player (forget / metadata continue as usual); all chunk data is fetched by the unified Compare+Pull driven by the client shadow virtual player's vanilla tracking (`ShadowPull`: UNCHANGED / DELTA / FULL / ERROR). See [Features](Features-en).

### Disconnect / reconnect

Disconnect → vanilla reconnect flow → the shadow-side world cache (`hassium_cache/<serverId>/world`) is reused by chunkHash: unchanged chunks hit the cache (UNCHANGED), changed chunks use section delta (DELTA), and only missing chunks are fetched in full.

---

## Configuration

Current network-related keys (full table in [Configuration](Configuration-en)):

| Key | Default | Description |
| --- | --- | --- |
| `master.enabled` | `true` | Server network-channel master switch (gate for the login handshake / aggregation) |
| `chunk.enabled` | `true` | Client chunk-core master switch (gate for Pull mode / shadow server / cache) |
| `master.enablePacketAggregation` | `true` | Packet aggregation |
| `master.aggregationMaxWaitTimeMs` | `50` | Aggregation max wait (ms; ACK timeout 5s auto-downgrades to direct send) |
| `master.compressionBlacklist` | `[]` | Third-party packet compression / aggregation exclusion (Hassium control-plane is always hard-coded excluded) |

Public deployments only need to open the game port (vanilla TCP); there are no gateway / UDP ports.

---

## Related pages

[← Support-Matrix](Support-Matrix-en) · [Home](Home-en) · [→ FAQ](FAQ-en)
