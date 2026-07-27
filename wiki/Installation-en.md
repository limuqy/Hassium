# Installation

---

> **简体中文**: [Installation](Installation) · English

## Download

Download the JAR matching the Minecraft version and loader from [GitHub Releases](https://github.com/limuqy/Hassium/releases) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium):

| Loader | Download choice |
| --- | --- |
| Fabric | Select the JAR for Fabric |
| Forge | Select the JAR for Forge |
| NeoForge | Select the JAR for NeoForge |

## Installation steps

1. Download the JAR for the required loader.
2. Place the JAR in the client or dedicated server's `mods/` directory.
3. Start the game or server. Configuration files are generated automatically at the following locations:

| Side | Configuration files |
| --- | --- |
| Client | `config/hassium/hassium-client.toml` and `config/hassium/hassium-common.toml` |
| Dedicated server | `config/hassium/hassium-common.toml` |

## Required dependencies

| Loader | Required dependency |
| --- | --- |
| Fabric | Fabric API |
| Forge | No additional dependency |
| NeoForge | No additional dependency |

## Installing on the client and server

| Installation | Result |
| --- | --- |
| Installed on both client and server | Recommended; enables negotiated compression and caching |
| Installed on the client only | Client caching is still available |
| Installed on the server only | Clients without the mod can still connect by default |

## Configuration

- Fabric: install **Mod Menu** and **Cloth**, then open the configuration from Mod Menu.
- Forge / NeoForge: click the **Configure** button in the mods list; **Cloth** is required.
- TOML files can also be edited directly: `config/hassium/hassium-client.toml` or `config/hassium/hassium-common.toml`.

## Before enabling storage for the first time

Enabling storage for the first time changes the world save format. Back up the world before enabling it. See the [FAQ](FAQ-en) for related handling guidance.

---

[← Home](Home-en) [→ Configuration](Configuration-en)
