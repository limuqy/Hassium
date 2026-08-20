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
| `/hassium stats toggle` | Toggle metrics collection | OP 2 |
| `/hassium metrics on` | Runtime-enable metrics collection | OP 2 |
| `/hassium metrics off` | Runtime-disable metrics collection | OP 2 |

> `/hassium metrics off` also disables `/hassium stats`. Self-checks auto-enable metrics.

---

## Client commands

| Command | Purpose |
| --- | --- |
| `/hassiumc stats` | Show client stats: bandwidth compression, chunk cache (full hits + delta), chunk loading (new + stale + local), light cache, light recompute, beyond-view ON\|OFF, bandwidth savings |
| `/hassiumc export [<serverIp>] [seed]` | Copy the shadow-side world directory wholesale as an export |

> `export` arguments:
>
> - `<serverIp>` is optional; defaults to the currently connected server (use `IP_port`, or a bare IP)
> - `seed` is a retained argument (a directory copy does not involve the seed)
> - Output directory: `<gameDir>/hassium_exports/<cacheId>/` (keeps the type 126 + chunkHash format; vanilla translation is planned later)
>
> See [World-Export](World-Export-en).

---

## Client migration commands (`/hassium migrate`)

Drill / manual master switch (registered on the client; running on a dedicated server prints a client-context hint):

| Command | Purpose |
| --- | --- |
| `/hassium migrate` | Usage help |
| `/hassium migrate list` | List available migration endpoints |
| `/hassium migrate status` | Current Network Core / migration status |
| `/hassium migrate <host:port>` | Migrate to the given master gateway endpoint (prewarm + resume ticket) |

See [Network Core and Master Migration](Network-Core-and-Master-Migration-en).

---

[← Configuration](Configuration-en) · [Home](Home-en) · [→ Features](Features-en)
