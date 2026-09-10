# Commands

---

> **English**: [Commands](Commands) · English

Hassium provides two command sets:

- Server: `/hassium ...` (requires OP 2)
- Client: `/hassiumc ...` (client only, no permission required)

---

## Server commands

| Command | Description | Notes |
| --- | --- | --- |
| `/hassium stats` | Server compression and send statistics | Requires OP 2 |
| `/hassium stats reset` | Reset server-side counters | Requires OP 2 |
| `/hassium stats toggle` | Toggle metrics collection | Same |
| `/hassium metrics on` | Enable metrics at runtime | Same |
| `/hassium metrics off` | Disable metrics at runtime | Same |

> `/hassium metrics off` also makes `/hassium stats` unavailable; self-checks auto-enable it.

---

## Client commands

| Command | Description |
| --- | --- |
| `/hassiumc stats` | Client statistics: bandwidth compression, chunk cache (full+partial−delta over applied, by bytes; local generation not counted as cache), chunk loading (new+stale+local), lighting cache, lighting recompute, traffic savings (actual / no-mod expected) |
| `/hassiumc export [<serverIp>] [seed]` | Copy the shadow world into an export save |

> `export` arguments:
>
> - `<serverIp>` optional; exports that server's cache instead of the currently connected one (format: `IP_port` or plain `IP`)
> - `seed` optional; **ignored**. The seed is written into `level.dat` (`WorldOptions`) by the shadow server via vanilla `saveDataTag` — just copy
> - Output directory: `<gameDir>/hassium_exports/<cacheId>/` (keeps type 126 + chunkHash; vanilla translation pending)
>
> See [World-Export](World-Export-en).

---

[← Configuration](Configuration-en) · [Home](Home-en) · [→ Features](Features-en)
