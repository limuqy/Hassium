# 多版本适配推进顺序（Rollout）

与 [`version-segments.md`](version-segments.md) 配套。按 **A → B → C → … → I** 推进，禁止并行铺满 9 段。

## 当前状态

| 段 | 锚点 | 状态 | 说明 |
|----|------|------|------|
| A | 1.20.1 | 进行中 / Fabric 已测通 | 巩固 Forge/NeoForge 冒烟 |
| B | 1.20.2 | 待办 | CustomPayload / NeoForge 包名 |
| C | 1.20.5 | **闸门未完成** | STREAM_CODEC 聚合写包；`NetworkCapability` 仍为 false |
| D–I | 1.21.1+ | 待办 | 按段只改 Compat + Mixin 签名 |

## 段 C 完成前的门控

当 `NetworkCapability.isCustomChannelFullySupported()` 为 false（当前：`MC_VER >= MC_1_20_5`）：

1. `CommonClass.init()` 强制 `networkCompressionEnabled = false` 并打 WARN
2. 各加载器 `registerChannels` / `registerClientChannels` / NeoForge `registerPayloads` 跳过注册
3. **编过 ≠ 能用** —— 高版本 jar 可编译发布，但自定义通道不会启用

### 解除门控条件（段 C Done）

全部满足后，将 `NetworkCapability.isCustomChannelFullySupported()` 改为对 1.20.5+ 返回 true：

- [ ] `HassiumAggregationManager`：原版包可用 StreamCodec / RegistryFriendlyByteBuf 序列化
- [ ] `NamespaceIndexManager`：不再依赖已移除的 `getPacketsByIds`
- [ ] `PacketPayloadCompat.extractPayloadData`：非 RawCustomPayload 也可经注册 codec 提取
- [ ] 锚点 1.20.5 Fabric + NeoForge 运行冒烟通过

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
