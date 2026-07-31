# Support Matrix

---

> **简体中文**: [Support-Matrix](Support-Matrix) · English

Hassium covers Minecraft **1.20.1–1.21.11**, adapted as **9 version segments × loaders**. One segment per version; intra-segment versions are represented by an anchor compile.

---

## MC version × loader

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2 | ✅ | — | ✅ |
| 1.20.3 | ✅ | — | ✅ |
| 1.20.4 | ✅ | — | ✅ |
| 1.20.5 | ✅ | — | ✅ |
| 1.20.6 | ✅ | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3 | ✅ | ✅ | ✅ |
| 1.21.4 | ✅ | ✅ | ✅ |
| 1.21.5 | ✅ | ✅ | ✅ |
| 1.21.6 | ✅ | ✅ | ✅ |
| 1.21.7 | ✅ | ✅ | ✅ |
| 1.21.8 | ✅ | ✅ | ✅ |
| 1.21.9 | ✅ | ✅ | ✅ |
| 1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

- **Forge is available on 1.20.1 / 1.20.6 / 1.21.1 / 1.21.3–1.21.10**; 1.21.2 is skipped upstream (no official Forge userdev), **1.21.11 is sunset** — use NeoForge for 1.21.x
- Forge support on 1.20.1 is provided by the neoforge subproject (`loom.platform=forge`); the standalone forge subproject builds only 1.20.6 / 1.21.x
- Forge 1.20.6 keeps a one-off Forge Config API Port bridge because it shares `ModConfigSpec` with NeoForge

---

## Nine-segment anchors (compile matrix)

Each segment is represented by one anchor that participates in compile and self-checks:

| Segment | Anchor | Other versions in segment | Key change (summary) |
| --- | --- | --- | --- |
| A | **1.20.1** | — | Baseline: legacy networking + all legacy APIs |
| B | **1.20.2** | 1.20.3 | CustomPayload plumbing; NeoForge package rename |
| C | **1.20.5** | 1.20.6 | StreamCodec; `Packet.write` etc. removed |
| D | **1.21.1** | — | `DisconnectionDetails`; RL constructor privatized |
| E | **1.21.2** | 1.21.3, 1.21.4 | `SerializableChunkData`, `lookupOrThrow` |
| F | **1.21.5** | — | CompoundTag API; ProtocolInfo Unbound split; client cache **not cross-MC-version compatible** |
| G | **1.21.6** | 1.21.7, 1.21.8 | `serverLevel()`→`level()`; Connection.send listener; NeoForge EBS bus removed |
| H | **1.21.9** | 1.21.10 | LevelChunkSection; `getServer()` removed; `setLevel` one-arg |
| I | **1.21.11** | — | `ResourceLocation` → `Identifier` |

Each anchor participates in compile and self-checks; the rest of the segment ships as releases, segmented via Manifold `#if MC_VER` in a single source tree.

---

## Client cache cross-version policy

From segment F (1.21.5) onward, the client chunk cache **is not guaranteed to be cross-MC-version compatible**:

- Within the same MC version (incl. Fabric ↔ NeoForge) hits and overwrites work normally
- After a version bump, the old cover is lazily overwritten (MISS → refetch → persist); **no full invalidate on start**
- No cross-version migration / format negotiation is implemented

See [Compatibility](Compatibility-en).

---

## Repo and build

- Repo: `https://github.com/limuqy/Hassium`
- Build system: Architectury Loom + Manifold; `versionProperties/<ver>.properties` controls per-version dependencies
- Anchor compile (developer): `./gradlew compileAnchors`
- Version boundary scan: `./gradlew scanVersionBoundaries`

---

[← Compatibility](Compatibility-en) · [Home](Home-en) · [→ Data-Plane-and-Failover](Data-Plane-and-Failover-en)
