# World Export

---

> **简体中文**: [World-Export](World-Export) · English

`/hassiumc export` writes the client-side cache to a vanilla Anvil singleplayer world. Client-only command, no permission required.

---

## Command

```
/hassiumc export [<serverIp>] [seed]
```

| Argument | Required | Notes |
| --- | --- | --- |
| `<serverIp>` | no | When given, exports that server's cache; defaults to the **currently connected** server. Format: `IP_port` or bare `IP` |
| `seed` | no | When omitted, a random seed is used in barrier-island mode |

- Output directory: `<gameDir>/saves/<worldName>/`
- Runs asynchronously (background thread); progress is reported in chat per dimension
- A single failing Region does not abort the whole export (failures are counted)
- Re-entrancy guard rejects new requests while an export is running

---

## Output structure

| Dimension | Target directory |
| --- | --- |
| `minecraft:overworld` | `region/` |
| `minecraft:the_nether` | `DIM-1/region/` |
| `minecraft:the_end` | `DIM1/region/` |
| Other | `dimensions/<ns>/<path>/region/` |

Each Region file follows the vanilla layout:

- Dual-sector header (offset table + timestamp)
- `[length(4)][type=2][zlib data]`

Transcode path: Hassium type 126 (ZSTD) → NBT → zlib type 2

`level.dat` and `level.dat_old`:

- Minimal singleplayer-world scaffold
- `DataVersion` = current client
- `GameType = SURVIVAL`
- `SpawnX/Y/Z` and `generatorName = default`

---

## Example

```
/hassiumc export 192.168.1.100_25565
```

Output:

```
saves/MyCacheWorld/
├── level.dat
├── level.dat_old
├── region/
│   ├── r.0.0.mca
│   └── r.0.-1.mca
├── DIM-1/region/
└── DIM1/region/
```

After completion the singleplayer menu shows `MyCacheWorld`; entering it lets you browse the chunks you have visited.

---

## Caveats

The chat report after export includes these caveats:

- **No entities, no inventory, no advancements** — the cache holds only block states and BE NBT
- **A snapshot of the chunks you have visited** — empty chunks are filled by the world generator
- **Modded blocks need the same mods and a close MC version** — otherwise they may render as unknown
- **DataVersion matches the current client** — cross-version save upgrade is left to vanilla
- **BE availability depends on cache contents** — Live-Unload snapshots include BE; warm-stash from inbound packets may be missing
- **Light is retained from the cache** — chunks with `is_light_on=1` carry `SkyLight` / `BlockLight`, so the singleplayer world opens with no recompute

---

[← Beyond-View-Render](Beyond-View-Render-en) · [Home](Home-en) · [→ Compatibility](Compatibility-en)
