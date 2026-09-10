# 超视渲染（规划中）

---

> **English**: [Beyond-View-Render-en](Beyond-View-Render-en) · 中文

> **状态：规划中，当前版本未启用。** 2.0.0 直连拓扑回归后，区块交付全部由影子端 vanilla tracking 驱动（进范围必交付、出范围 Forget），旧的 OVD 环带链路（`renderOnly` 区块、`ClientHeatIndex` 单块淘汰、`ChunkDataRequestC2S` 视距外请求）已从代码裁剪，相关配置键（`chunk.viewDistanceExtensionEnabled` / `chunk.maxRenderDistance` / `chunk.ovdUnloadDelaySecs` / `chunk.ovdLocalGeneration`）已删除。本页保留设计记录，功能落地时恢复配置键与本页的「启用」章节。

---

## 设计目标

超视渲染（beyond-view render）让多人服客户端在渲染距离（RD）大于服务端视距（serverVD）时，用本地缓存回填 `serverVD < dist ≤ clientVD` 环带的位置 —— **仅参与渲染、不参与模拟**，且不向服务器索取视距外区块或方块实体。

## 设计要点（历史记录）

- **多人服限定**：单人服不启用
- **环带回填**：命中本地缓存的区块以 renderOnly 形态 apply；缓存未命中静默回滚，不向服请求
- **真实区块优先**：真实区块到达 renderOnly 位置时覆盖标记并请求 BE
- **与 Bobby 互斥**：Hassium 自研回填，勿与 Bobby 同装
- **资源复用**：复用影子端缓存淘汰机制，不建专用内存池
- **边界**：`clientVD ≤ serverVD` 自动 clear；`serverRenderDistance == 0` fallback 到 simulationDistance

## 现行替代

当前版本下，视距内区块由影子端统一交付（含缓存命中复用与分段增量）；视距外不回填、不请求——行为与原版一致。若需要视距外地形预览，请等待本功能落地。

---

[← Features](Features) · [Home](Home) · [→ World-Export](World-Export)
