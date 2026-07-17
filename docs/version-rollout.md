# 多版本适配推进顺序（Rollout）

与 [`version-segments.md`](version-segments.md) 配套。按 **A → B → C → … → I** 推进，禁止并行铺满 9 段。

## 当前状态

| 段 | 锚点 | 状态 | 说明 |
|----|------|------|------|
| A | 1.20.1 | 进行中 / Fabric 已测通 | 巩固 Forge/NeoForge 冒烟 |
| B | 1.20.2 | 进行中 | NeoForge 网络改回 SimpleChannel（neoforged + PlayNetworkDirection）；编译已通 |
| C | 1.20.5 | **已完成 / 已联调** | Fabric+NeoForge 1.20.5、Forge 1.20.6 进服握手与客户端统计已测通 |
| D–I | 1.21.1+ | 待办 | 按段只改 Compat + Mixin 签名 |

## 段 C 门控（已解除）

`NetworkCapability.isCustomChannelFullySupported()` 在全部目标版本返回 true。此前当其为 false（`MC_VER >= MC_1_20_5`）时：

1. `CommonClass.init()` 强制 `networkCompressionEnabled = false` 并打 WARN
2. 各加载器 `registerChannels` / `registerClientChannels` / NeoForge `registerPayloads` 跳过注册

### 解除门控条件（段 C Done）

全部满足后，将 `NetworkCapability.isCustomChannelFullySupported()` 改为对 1.20.5+ 返回 true：

- [x] `HassiumAggregationManager`：原版包可用 StreamCodec / RegistryFriendlyByteBuf 序列化（`PacketCodecCompat`）
- [x] `NamespaceIndexManager`：不再依赖已移除的 `getPacketsByIds`（`GameProtocols` + `IdDispatchCodec`）
- [x] `PacketPayloadCompat.extractPayloadData`：非 RawCustomPayload 也可经注册 codec / `data()` 提取
- [x] 锚点 1.20.5 Fabric + NeoForge 运行冒烟通过（Fabric：进服握手成功；NeoForge：Payload 注册 + runServer Done）
- [x] Forge 1.20.6：`ChannelBuilder` + `play()` 注册 4 C2S / 6 S2C；客户端握手与 `/hassiumc stats` 联调通过
- [x] 联调确认（2026-07-18）：Fabric/NeoForge 1.20.5、Forge 1.20.6 测试通过

## 推荐验证命令

```bash
# 非法分界
./gradlew scanVersionBoundaries

# 当前默认版本（gradle.properties mc_ver）
./gradlew fabric:compileJava forge:compileJava neoforge:compileJava

# 全锚点矩阵（耗时长）
./gradlew compileAnchors
# 或: powershell -File scripts/compile-anchors.ps1
```

运行时优先级：**1.20.1 → 1.20.5 → 1.21.11**。
