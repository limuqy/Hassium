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
| `/hassium stats toggle` | 切换指标收集开关 | 同上 |
| `/hassium metrics on` | 运行时打开指标收集 | 同上 |
| `/hassium metrics off` | 运行时关闭指标收集 | 同上 |

> `/hassium metrics off` 会同时让 `/hassium stats` 不可用；自检时自动开启。

---

## 客户端命令

| 命令 | 说明 |
| --- | --- |
| `/hassiumc stats` | 查看客户端统计：带宽压缩、区块缓存（全命中+部分命中−增量 / 应用，按字节；本地生成不算缓存）、区块加载（新增+过期+本地）、光照缓存、光照重算、超视渲染 ON\|OFF、流量节省（实际 / 无MOD应收） |
| `/hassiumc export [<serverIp>] [seed]` | 把影子端世界目录整体拷贝为导出存档 |

> `export` 参数：
>
> - `<serverIp>` 可选；不指定时导出当前连接的服务器缓存（格式：`IP_端口`，或纯 IP）
> - `seed` 保留参数（目录拷贝不涉及种子）
> - 输出目录：`<gameDir>/hassium_exports/<cacheId>/`（保留 type 126 + chunkHash 格式；翻译为原版格式后续提供）
>
> 详见 [World-Export](World-Export)。

---

## 客户端迁移命令（`/hassium migrate`，仅开发环境）

主控切换演练入口；**仅在开发环境（`runClient` / IDE）注册**，正式发布包不可用。生产迁移由故障/策略自动触发，无需玩家操作。

| 命令 | 说明 |
| --- | --- |
| `/hassium migrate` | 用法帮助 |
| `/hassium migrate list` | 列出可用迁移端点 |
| `/hassium migrate status` | 当前网络核心 / 迁移状态 |
| `/hassium migrate <host:port>` | 迁移到指定主控网关端点（预热 + 续流票据） |

详见 [网络核心与主控迁移](Network-Core-and-Master-Migration)。

---

[← Configuration](Configuration) · [Home](Home) · [→ Features](Features)
