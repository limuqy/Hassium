# World Export

---

> **简体中文**: [World-Export](World-Export) · English

`/hassiumc export` copies the shadow-side world directory of the current (or a given) server as an export. Client-only command, no permission required.

---

## Command

```
/hassiumc export [<serverIp>] [seed]
```

| Argument | Required | Notes |
| --- | --- | --- |
| `<serverIp>` | no | When given, exports that server's cache; defaults to the **currently connected** server. Format: `IP_port` or bare `IP` |
| `seed` | no | Retained argument (a directory copy does not involve the seed) |

- Output directory: `<gameDir>/hassium_exports/<cacheId>/` (`cacheId` = `server_<IP>_<port>`, or the current server's serverId)
- Source: wholesale copy of the shadow-side world directory `hassium_cache/<serverId>/world`
- Runs asynchronously; chat reports "export finished / export failed" on completion; "not connected to a server" when offline, "shadow-side world directory not found" when the source is missing

---

## What the export contains

- **Source**: the shadow-side world directory `hassium_cache/<serverId>/world` of the current (or given) server, copied wholesale to `hassium_exports/<cacheId>/`
- **Format kept**: type 126 + chunkHash on-disk format, unchanged (same as the shadow-side storage write path)
- **Vanilla translation** (type 126 → vanilla format) is planned later; until then the export cannot be opened directly as a singleplayer world

---

## Example

```
/hassiumc export 192.168.1.100_25565
```

Output directory: `hassium_exports/server_192.168.1.100_25565/` (mirroring the layout of `hassium_cache/server_192.168.1.100_25565/world/`).

Chat reports `export finished: <target path>` on completion.

---

## Caveats

- **No entities, no inventory, no advancements** — the shadow-side world holds only chunk/light and block-entity data
- **Format stays type 126** — requires Hassium to read; vanilla translation is planned later
- **A snapshot of the chunks you have visited** — empty chunks are filled by the world generator
- **Modded blocks need the same mods and a close MC version** — otherwise they may render as unknown
- **BE availability depends on the shadow-side cache contents** — Live-Unload snapshots include BE; warm-stash from inbound packets may be missing
- **Light is retained with the chunks** — chunks with `is_light_on=1` carry `SkyLight` / `BlockLight`
