> 归档：设计提案（未实现，已退役）（2026-08-09）
# 服务端远距离区块：外界空气连通表层 LOD

> **状态：设计提案，尚未实现。**
>
> 本文取代“实心岩芯剔除 + 两 Section 壳层”方案。基准显示，在默认洞穴丰富世界中，严格 DENSE section 很少且大多距非 DENSE section 不超过一层；两层安全壳的 ZSTD 收益约为 `0%–0.02%`，不值得作为主路线继续投入。

## 1. 目标

在玩家完整交互区之外，发送独立的远景表层数据，而非伪造或裁剪原版 `LevelChunk` packet。远景数据应：

- 保留地表、树林、悬崖、峡谷、地表洞口和与外界连通的岩壁；
- 丢弃完全封闭的地下岩芯、深层洞穴网络和不可见 block entity；
- 让远景 payload 的大小由可见表面复杂度决定，而不是原版 `PalettedContainer` 的 4096 格稠密编码决定；
- 在玩家进入完整半径时，无缝让位给真实 full chunk；
- 不修改服务端 `LevelChunk`、落盘存档、type 126、真实 `chunkHash` 或 Hassium 完整区块缓存；
- 1.21.2+ 不在后台读取 live `LevelChunk` / `PalettedContainer`，避免 `ThreadingDetector`。

本文的产物是独立的 **surface LOD payload + 客户端 LOD renderer**。它不是 HOLLOW 原版 chunk，不能 apply 到 `LevelChunk`，也不进入 `hassium_cache`。

## 2. 决策

采用：

> **天空/外界可达空气驱动的表面提取 + 独立三维 LOD 数据与渲染。**

不采用：

> 把原版 chunk packet 的深层 section 替换为空气，或在原版 `PalettedContainer` 内仅保留暴露方块。

原版 section 是稠密编码：单值全石头与单值全空气同为极小 `bits = 0`；而“空气 + 少量表面方块”仍须编码 4096 个位置。逐方块清空内部既不能稳定减少 bytes，也无法承载自定义 LOD 网格。整 section 剔除虽有效，但默认世界的两层安全壳实测几乎没有可剔除 section。

| 基准世界 | `shellDepth=1` ZSTD 节省 | `shellDepth=2` ZSTD 节省 |
|---|---:|---:|
| 1.20.1 预生成世界 | 0.69% | 0.00% |
| 1.21.11 预生成世界 | 0.80% | 0.02% |

`shellDepth=0` 可省约 11.85%–14.46%，但会切穿悬崖和洞壁，不能作为可用视觉方案。

## 3. 可见表面语义

### 3.1 外界空气

令 `EXTERIOR_AIR` 为从已知外界种子出发、六方向连通的空气格集合。一个 solid voxel 是远景候选，当且仅当其六邻域至少一格属于 `EXTERIOR_AIR`。

```text
天空 / 已知外界空气
          │
          ▼
  外界空气 BFS（跨 chunk halo）
          │
          ├─ 地表空气      -> 保留地表、植被和建筑外立面
          ├─ 峡谷空气      -> 保留峡谷两侧与底部岩壁
          ├─ 悬崖外侧空气  -> 保留整面可见崖壁
          └─ 洞口空气      -> 保留有限深度的洞口、顶与壁
```

这不是“每列取最高方块”。低处悬崖、山谷和横向洞口通过外界空气连通被正确保留；完全被实体包围的岩芯没有邻接 `EXTERIOR_AIR`，不输出。

### 3.2 天空光的角色

天空光 section 只是**快速定位种子的辅助信息**，不是最终拓扑判据。

- 若主线程快照提供 sky light，`skyLight > 0` 的 air voxel 可作为 `EXTERIOR_AIR` 种子；
- halo 顶部边界的 air voxel 也可作为种子，覆盖 light payload 被 strip 的情形；
- BFS 从种子继续穿过所有相连 air voxel，即使深处 sky light 已衰减到 `0`；
- 因此“与天空光 section 连通的空气接触 section”会被纳入表面计算，而不是只保留有光的 section；
- section 是否含天空光不能单独决定保留整 section：同一 section 内可能同时有洞口与深层岩芯，最终必须逐格判定。

v1 的 BFS 只穿过 air。fluid voxel 邻接 `EXTERIOR_AIR` 时保留为水面/熔岩表面，但不把 fluid 当作空气继续深入；水下地形与流体内部表面是后续独立语义，不能混入空气连通定义。

### 3.3 有界洞穴展开

无限跟随洞口进入洞穴网络会重新带回大量地下岩壁，且一次挖通可能使极大范围的 LOD 失效。外界空气 BFS 必须有预算：

```toml
surfaceLodAirMaxDepthBlocks = 96
surfaceLodAirMaxCells = 131072
surfaceLodHaloChunks = 1
```

- `surfaceLodAirMaxDepthBlocks`：从种子到 air voxel 的最大 BFS 距离；
- `surfaceLodAirMaxCells`：单个 LOD 任务可访问的 air voxel 上限；
- 预算耗尽时停止展开，未访问深洞不进入远景；
- 预算边界和未知邻居都 fail-open：保留紧邻该边界的 solid voxel，优先避免可见裂缝。

这保留地表洞口、峡谷和浅层开放洞穴，同时约束 payload、缓存失效与 CPU。

## 4. 数据与线程模型

### 4.1 快照输入

surface LOD 不得在 `pushPool` 读取 live world。输入是已完成的纯数据快照：

```text
主线程（允许读世界）
  buildChunkPacket / shared FULL entry
    -> center chunk 的完整 packet bytes
    -> 周围 halo chunk 的完整 packet bytes（已就绪者）
    -> 可选：sky-light air seed mask

pushPool
  -> 解码独立 scratch section
  -> 外界空气 BFS
  -> surface voxel / mesh 生成
  -> LOD payload 编码、ZSTD、发送
```

完整 packet bytes 是唯一输入真值。若 halo chunk 尚无 FULL entry，不等待、不后台触碰邻 chunk：将该方向视为 unknown 边界，按 fail-open 保留中心 chunk 边缘附近的表面候选。

### 4.2 区域而非单 chunk 计算

空气连通本质跨 chunk。最小计算单元为：

```text
中心 chunk 16 x 世界全高 x 16
  + 水平 1 chunk halo
  + 垂直相邻 section
```

只将中心 chunk 的表面结果输出。halo 仅用于判断中心边界的空气是否通向外界，不作为重复发送内容。后续可以按 `2x2` 或 `4x4` chunk tile 合并任务和结果，减少 halo 重复扫描。

### 4.3 表面提取

```text
decode center + halo snapshots
  -> seed exterior air
  -> bounded six-neighbor BFS
  -> 对每个 EXTERIOR_AIR 邻格检查六面
  -> 收集中心 chunk 中相邻的 solid / fluid 表面 voxel
  -> 生成 LOD 格与可见 face mask
```

无需输出内部 solid voxel。临近未知 halo 边界的 solid 按可见处理；这是保守多发，不是将未知误判为岩芯。

## 5. LOD 表示与渲染

### 5.1 独立 payload

远景数据不走 `ClientboundLevelChunkWithLightPacket`，使用新 `SurfaceLodS2C` 帧：

```text
version
dimension + chunk/tile position
lodLevel + cellScale
local material dictionary
surface cells: position, material index, biome/light, visible-face mask
optional greedy-merged quads
```

`lodLevel=0` 可表示 1-block 表面；更远距离使用 `$2^3$`、`$4^3$` 等粗体素。下采样时只让表面候选参与：优先外界邻接、较高不透明度、朝外方向与较亮样本。客户端按自己的 block-model/material 表生成网格并只绘制 face mask 指定的面。

这借鉴 Voxy 的三维 mip 与只绘制暴露面的思想，但网络上发送的是已过滤、独立编码的表面 LOD，而不是先发送完整 chunk 再让客户端自行保存完整体积。

### 5.2 与完整区块的边界

```text
距离 <= fullRadius（建议 6 chunks）
  -> 正常 ChunkHashS2C + full chunk payload
  -> 原版 renderer、交互、block entity、完整缓存

距离 > fullRadius
  -> SurfaceLodS2C
  -> 独立 LOD renderer
  -> 不创建伪 LevelChunk；不写 hassium_cache
```

客户端必须在 full chunk 可用时遮蔽相同位置的 surface LOD，避免双层渲染。玩家靠近时优先发送 FULL；收到 full 后立即移除或淡出对应 LOD tile。远离时 LOD 可在 full unload 后短暂保留，但绝不能反向当作完整缓存命中。

## 6. 协议、缓存与失效

### 6.1 权威完整数据不变

`ChunkHashS2CPacket.Entry.chunkHash`、`sectionBitmap`、section delta、客户端磁盘缓存始终只表示完整真实 chunk。surface LOD 没有也不得伪装成 `chunkHash` 变体。

### 6.2 缓存层级

```text
T1 FULL: (dimension, chunkPos)
  full packet, encoded bytes, true hash, section bitmap

T-Surface: (dimension, center/tile pos, lodLevel, classifierVersion, snapshot revisions)
  decoded surface mask / surface cells / encoded LOD bytes / optional ZSTD bytes
```

`T-Surface` 只在相关中心与 halo FULL entries 的 revision 全匹配时命中。广播更新必须失效：

- 被修改 chunk 的所有 surface LOD；
- 以该 chunk 为 halo 的邻近中心/tile LOD；
- 达到 `surfaceLodAirMaxDepthBlocks` 所允许的局部半径前，不得宣称更强的局部失效界。

首版应采用保守 tile 失效而非尝试增量修补外界空气 mask。

### 6.3 非 Hassium 客户端

没有 surface LOD capability 的客户端保持原版 full chunk 推送。surface LOD 不得替代其任何权威区块数据。

## 7. 推送调度

- 近距离 full queue 始终优先；surface LOD 使用独立、低优先级 token bucket；
- 首版仅覆盖 serverVD 外或 fullRadius 外的明确远景环带，避免与原版 chunk sender 对同一位置竞争；
- 同一玩家从 LOD 环带进入 `fullRadius` 时，取消尚未发送的 LOD 任务并提升 FULL 请求；
- 同一 tile 对多个远景玩家可复用 `T-Surface`，但发送队列和可见半径仍按玩家独立判断；
- `SurfaceLodS2C` 不参与 section delta、BE 补发或 `renderOnly` 语义。

## 8. 配置与指标（提议）

```toml
[serverNetwork]
sharedChunkBuildCache = true
surfaceLodEnabled = false
surfaceLodFullRadius = 6
surfaceLodStartDistance = 7
surfaceLodHaloChunks = 1
surfaceLodAirMaxDepthBlocks = 96
surfaceLodAirMaxCells = 131072
surfaceLodMaxLevel = 3
surfaceLodTileSizeChunks = 1
```

建议指标：

- `surfaceLodTilesSent`、`surfaceLodCellsSent`、`surfaceLodFacesSent`；
- full 对比 surface LOD 的 raw/ZSTD bytes、节省比例；
- BFS seed 数、visited air cells、预算耗尽数、unknown-boundary fail-open 数；
- surface cache hit/miss、失效数、字节占用、构建耗时；
- 客户端 LOD mesh build、draw、full-over-Lod handoff 时间；
- full queue 延迟与 token bucket 占用，证明远景不抢近景。

## 9. 验证矩阵

| 场景 | 预期 |
|---|---|
| 平原与地下岩层 | 只输出地表；深层岩芯不进入 payload |
| 高山、悬崖、峡谷 | 所有面向外界空气的崖壁连续保留，不按最高 Y 截断 |
| 地表洞口 | 洞口、顶与预算内洞壁保留；深洞在预算外停止 |
| 封闭洞穴 | 无外界种子连通时不输出其内部岩壁 |
| 河流、湖泊 | 邻接外界空气的 fluid 表面与岸壁保留；不沿 fluid 无限传播 |
| 相邻 halo 未就绪 | 边缘 fail-open；多发可接受，不出现持续裂缝 |
| 玩家远到近 | LOD 被 FULL 覆盖；无双层面、无伪 chunk、无缓存错误命中 |
| 方块更新 | 中心与相关 halo tile LOD 失效并重建；full 语义不受影响 |
| 1.20.1 / 1.21.1 / 1.21.11 | 编译和 runtime smoke 通过；1.21.2+ 无 `ThreadingDetector` |

基准必须至少覆盖平原、山地/悬崖、巨洞、下界、建筑区和水域。记录 full packet、surface LOD raw/ZSTD、主线程快照时间、`pushPool` CPU、客户端 mesh build 与 FPS；不得只以单一 seed 或仅以发送吞吐判断价值。

## 10. 非目标

- 不修改服务端 `LevelChunk`、区块存档或 type 126；
- 不把 surface LOD 写入 `hassium_cache`、`renderOnly` 或 `ChunkHashS2C`；
- 不将逐方块暴露面塞回原版 `PalettedContainer` packet；
- 不在后台读取 live `LevelChunk`、`PalettedContainer` 或邻 chunk；
- v1 不做无限深度的洞穴连通、不做 fluid 内部地形、也不保证远景与近景方块模型逐像素一致；
- 不以远景带宽收益换取近距离交互、更新广播或非 Hassium 客户端正确性。
