# 世界导出

---

> **English**: [World-Export-en](World-Export-en) · 中文

`/hassiumc export` 把当前（或指定）服务器的影子端世界目录整体拷贝为导出存档。仅客户端命令，无权限要求。

---

## 命令

```
/hassiumc export [<serverIp>] [seed]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<serverIp>` | 否 | 指定时导出该服务器缓存；不指定时导出**当前连接**的服务器。格式：`IP_端口` 或纯 `IP` |
| `seed` | 否 | 保留参数（目录拷贝不涉及种子） |

- 输出目录：`<gameDir>/hassium_exports/<cacheId>/`（`cacheId` = `server_<IP>_<端口>`，或当前连接服务器的 serverId）
- 数据源：影子端世界目录 `hassium_cache/<serverId>/world` 整体拷贝
- 异步执行（后台线程），完成后聊天回报「导出完成 / 导出失败」；未连接时回报「未连接服务器，无法确定导出目标」，目录缺失时回报「未找到影子端世界目录」

---

## 导出内容

- **数据源**：当前连接（或指定）服务器的影子端世界目录 `hassium_cache/<serverId>/world`，整体拷贝到 `hassium_exports/<cacheId>/`
- **格式保留**：type 126 + chunkHash 落盘格式不变（与影子端存储写路径一致）
- **原版翻译**（type 126 → 原版格式）后续提供；届时导出的世界方可直接进单机

---

## 示例

```
/hassiumc export 192.168.1.100_25565
```

输出目录：`hassium_exports/server_192.168.1.100_25565/`（与 `hassium_cache/server_192.168.1.100_25565/world/` 目录结构一致）。

完成后聊天回报 `导出完成: <目标路径>`。

---

## 限制

- **无实体、无玩家背包/成就**：影子端世界仅含区块/光照与方块实体数据
- **格式保留 type 126**：需 Hassium 读取；翻译为原版格式后续提供
- **仅为「去过的区块」快照**：空洞区块由世界生成器填充
- **模组方块需相同模组与相近 MC 版本**：否则方块可能显示为未知
- **BE 取决于影子端缓存是否含 NBT**：Live-Unload 快照包含 BE；收包 warm-stash 可能缺失
- **光照随区块保留**：`is_light_on=1` 的区块携带 `SkyLight` / `BlockLight`
