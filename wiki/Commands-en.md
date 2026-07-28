# Commands

---

> **简体中文**: [Commands](Commands) · English

Hassium exposes two command groups:

- Server: `/hassium ...` (requires OP 2)
- Client: `/hassiumc ...` (client only, no permission required)

---

## Server commands

| Command | Purpose | Notes |
| --- | --- | --- |
| `/hassium stats` | Show server-side compression and send statistics | Requires OP 2 |
| `/hassium stats reset` | Reset server-side counters | Requires OP 2 |
| `/hassium metrics on` | Runtime-enable metrics collection | OP 2 |
| `/hassium metrics off` | Runtime-disable metrics collection | OP 2 |

> `/hassium metrics off` also disables `/hassium stats`. Self-checks auto-enable metrics.

---

## Client commands

| Command | Purpose |
| --- | --- |
| `/hassiumc stats` | Show client stats: received bytes, compression savings, cache hits, beyond-view render, lighting optimization |
| `/hassiumc export [<serverIp>] [seed]` | Export the local cache to a vanilla Anvil singleplayer world |

> `export` arguments:
>
> - `<serverIp>` is optional; defaults to the currently connected server (use `IP_port`, or a bare IP)
> - `seed` is optional; defaults to a random seed in barrier-island mode
> - Output directory: `<gameDir>/saves/<worldName>/`
>
> See [World-Export](World-Export-en).

---

[← Configuration](Configuration-en) · [Home](Home-en) · [→ Features](Features-en)
