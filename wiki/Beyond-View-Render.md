# 超视渲染（OVD，影子双窗）

---

> **English**: [Beyond-View-Render-en](Beyond-View-Render-en) · 中文

> **状态：现行功能。** 影子双窗 OVD：tracking 扩到 effective clientRD；权威窗（serverVD）走 Compare+Pull，OVD 窗只从本地源（盘 / 注入）回填，禁止向真服请求。客户端只抬 `ClientChunkCache` 半径并拦截 Forget。详见 [`docs/chunk-cache.md`](../docs/chunk-cache.md) §10。

---

## 设计目标

多人服客户端渲染距离（RD）大于服务端视距（serverVD）时，用本地缓存回填 `serverVD < dist ≤ clientRD` 环带——**仅参与渲染、不参与模拟**，且不向服务器索取视距外区块。

## 现行拓扑

- **权威窗**（`dist ≤ serverVD`）：影子 tracking 驱动统一 Compare+Pull
- **OVD 窗**（`serverVD < dist ≤ effective clientRD`）：只读本地源（注入 / 盘），不向服务端请求
- **客户端**：抬 `ClientChunkCache` 半径；拦截 vanilla Forget，避免环带被卸载
- **关开关**：`chunk.viewDistanceExtensionEnabled=false` 时半径回落 serverVD，只走权威窗
- **与 Bobby 互斥**：Hassium 自研回填，勿与 Bobby 同装

## 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.viewDistanceExtensionEnabled` | `true` | 超视渲染总开关（依赖 `chunk.enabled`） |
| `chunk.maxRenderDistance` | `16` | effective clientRD 上限（2–64） |

延迟卸载（`chunk.ovdUnloadDelaySecs`）已取消，双窗不恢复该键；OVD 本地生成（`chunk.ovdLocalGeneration`）已退役删除。

---

[← Features](Features) · [Home](Home) · [→ World-Export](World-Export)
