# 世界导出

---

> **English**: [World-Export-en](World-Export-en) · 中文

`/hassiumc export` 把客户端本地缓存导出为可进单机的原版 Anvil 存档。仅客户端命令，无权限要求。

---

## 命令

```
/hassiumc export [<serverIp>] [seed]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<serverIp>` | 否 | 指定时导出该服务器缓存；不指定时导出**当前连接**的服务器。格式：`IP_端口` 或纯 `IP` |
| `seed` | 否 | 不指定时使用随机种子 + 空岛模式 |

- 输出目录：`<gameDir>/saves/<worldName>/`
- 异步执行（后台线程），按维度粒度进度回报到聊天
- 单 Region 失败不中断整体导出（累加失败计数）
- 全局防重入：正在导出时拒绝新请求

---

## 输出结构

| 维度 | 目标目录 |
| --- | --- |
| `minecraft:overworld` | `region/` |
| `minecraft:the_nether` | `DIM-1/region/` |
| `minecraft:the_end` | `DIM1/region/` |
| 其它 | `dimensions/<ns>/<path>/region/` |

每个 Region 文件遵循原版布局：

- 双扇区 header（offset table + timestamp）
- `[length(4)][type=2][zlib data]`

转码路径：Hassium type 126（ZSTD 压缩）→ NBT → zlib type 2

`level.dat` 与 `level.dat_old`：

- 最小可进世界脚手架
- `DataVersion` = 当前客户端
- `GameType = SURVIVAL`
- `SpawnX/Y/Z` 与 `generatorName = default`

---

## 示例

```
/hassiumc export 192.168.1.100_25565
```

输出：

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

完成后在单机主菜单可见 `MyCacheWorld`，进入后可浏览去过的区块。

---

## 限制

导出后聊天回报包含以下限制：

- **无实体、无玩家背包/成就**：缓存仅含方块状态与 BE NBT
- **仅为「去过的区块」快照**：空洞区块由世界生成器填充
- **模组方块需相同模组与相近 MC 版本**：否则方块可能显示为未知
- **DataVersion 与当前客户端一致**：跨版本存档升级交给原版
- **BE 取决于缓存是否含 NBT**：Live-Unload 快照包含 BE；收包 warm-stash 可能缺失
- **光照从缓存保留**：`is_light_on=1` 的区块导出时携带 `SkyLight` / `BlockLight`，单机打开无需重算

---

[← Beyond-View-Render](Beyond-View-Render) · [Home](Home) · [→ Compatibility](Compatibility)
