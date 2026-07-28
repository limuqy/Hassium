# 命令

---

> **English**: [Commands-en](Commands-en) · 中文

Hassium 提供两组命令：

- 服务端：`/hassium ...`（需要 OP 2）
- 客户端：`/hassiumc ...`（仅客户端，无权限要求）

---

## 服务端命令

| 命令 | 说明 | 备注 |
| --- | --- | --- |
| `/hassium stats` | 查看服务端压缩与发送统计 | 需要 OP 2 |
| `/hassium stats reset` | 重置服务器端统计计数器 | 需要 OP 2 |
| `/hassium metrics on` | 运行时打开指标收集 | 同上 |
| `/hassium metrics off` | 运行时关闭指标收集 | 同上 |

> `/hassium metrics off` 会同时让 `/hassium stats` 不可用；自检时自动开启。

---

## 客户端命令

| 命令 | 说明 |
| --- | --- |
| `/hassiumc stats` | 查看客户端统计：接收字节数、压缩节省、缓存命中、超视渲染、光照优化 |
| `/hassiumc export [<serverIp>] [seed]` | 把本地缓存导出为可进单机的原版 Anvil 世界 |

> `export` 参数：
>
> - `<serverIp>` 可选；不指定时导出当前连接的服务器缓存（格式：`IP_端口`，或纯 IP）
> - `seed` 可选；不指定时使用随机 seed + 空岛模式
> - 输出目录：`<gameDir>/saves/<worldName>/`
>
> 详见 [World-Export](World-Export)。

---

[← Configuration](Configuration) · [Home](Home) · [→ Features](Features)
