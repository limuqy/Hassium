# Hassium 功能需求文档

## 1. 项目背景

Hassium 目标是提供一个替代 Minecraft 原版区块文件保存、读取与网络传输机制的 MOD，重点解决服务端长期运行后存档体积过大、玩家登录与移动时区块传输占用带宽过高的问题。

当前项目是 multiloader 结构，计划同时支持客户端与服务端。首个开发目标为 Minecraft `1.20.1`，同时需要在存储抽象和元数据设计中预留后续升级到 Minecraft `1.20.5` 及以上版本的兼容路径：

- Minecraft：`1.20.1`，后续兼容目标包含 `1.20.5+`
- Java：`17`
- Forge：`47.2.30`
- Fabric：`0.92.1+1.20.1`
- 模块结构：`common`、`forge`、`fabric`

## 2. 目标与非目标

### 2.1 目标

- 在尽量保持 Region/Anvil 外层文件结构的前提下扩展区块 payload 压缩与元数据，降低服务端存档体积。
- 使用 ZSTD 压缩算法，并基于真实区块数据训练专用字典，提高压缩率与解压速度。
- 在客户端维护本地区块缓存，降低重复下载相同区块数据的网络开销。
- 在客户端已有缓存区块时，使用服务端权威版本号、更新时间和数据版本等缓存元数据进行对比，未变化时避免重新请求完整区块数据。
- 修改服务端数据包压缩逻辑，在双方支持时启用更高效的压缩方案。
- 提供可配置、可回滚、可观测的运行机制，避免对存档安全和网络兼容性造成不可控影响。

### 2.2 非目标

- 不改变 Minecraft 区块语义、方块状态、实体、方块实体或生物群系数据的含义。
- 不绕过服务端权威数据；客户端缓存不能作为交互、碰撞、红石、实体同步或反作弊判定依据。
- 不默认破坏原版客户端或未安装 Hassium 客户端的连接能力。
- 不承诺与所有修改区块存储、网络协议或数据修复流程的 MOD 自动兼容。

## 3. 功能需求

### 3.1 Region 兼容存储格式

Hassium 应优先参考 Minecraft Wiki 中的 Region file format，不发明完全不同的外层文件结构，而是在 Region/Anvil 容器基础上扩展区块 payload 压缩、字典信息和 Hassium 元数据。

需求：

- 保留 `.mca` 按 32x32 chunk 组成 region 的定位方式，继续通过 chunk 坐标右移 5 位定位 region 文件。
- 保留 4KiB sector 对齐、1024 项 location table。
- 保留 payload 的 4 字节大端 length 字段，并在 length 后记录压缩类型或 Hassium 扩展标识。
- 保存必要元数据，包括 Hassium 存储格式版本、ZSTD 字典 ID、原始数据长度、payload 长度、inhabitedTime 和校验值。
- 支持随机读取单个区块，继续利用 Region header 中的 sector offset 和 sector count。
- 支持损坏检测，至少保存每个区块 payload 的 CRC32C 校验值。
- 支持与原版 Anvil/Region 数据渐进迁移，允许首次读取原版区块后以 Region 兼容扩展格式写回。
- 支持回滚策略，至少需要保留”只读原版、镜像写入 Hassium 扩展格式”或”导出回原版 Zlib payload”的设计路径。

当前实现：

**服务端存储（`MixinRegionFile`）**：
- 通过 Mixin 拦截原版 `RegionFile` 的 `getChunkDataInputStream` 和 `getChunkDataOutputStream`。
- 检测压缩类型 127，解码 HassiumEnvelope 并解压返回原版 NBT 流。
- 使用 `MetadataTable` 管理区块时间戳（`inhabitedTime`）。
- 支持三种模式：`readonly_vanilla`、`mirror`、`hassium_only`。

**客户端缓存存储（`HassiumRegionFile`）**：
- 统一的 Region 文件实现，格式兼容原版 .mca。
- Header 包含 Offset Table（1024 ints）和 Metadata Table（1024 entries × 8 bytes）。
- Metadata Table 存储 `inhabitedTime`，支持快速读取（8 bytes）。
- 使用 `HassiumEnvelope` 格式存储压缩数据（ZSTD）。

**HassiumEnvelope 格式**：
```
├── magic: “HSM1” (4 bytes)
├── storageFormatVersion: uint16
├── algorithmId: namespaced string (length-prefixed)
├── dictionaryId: nullable string (length-prefixed)
├── dataVersion: int32 (保留兼容)
├── uncompressedLength: int32
├── compressedLength: int32
├── chunkRevision: int64
├── lastModifiedGameTime: int64 (inhabitedTime)
├── lastSavedUnixTime: int64
├── checksum: uint64 (CRC32C)
└── compressedData: byte[] (ZSTD 压缩数据)
```

兼容策略：

- 首选”Region 容器兼容 + Hassium 读写器”，即尽量保持外层 `.mca` 文件组织不变，但由 Hassium 接管自定义压缩 payload 的读写。
- Wiki 中提到 compression scheme `127` 可用于第三方自定义压缩算法，后续 Minecraft `1.20.5+` 版本应优先利用该机制声明 Hassium 的自定义压缩算法。
- 当前首个开发目标为 Minecraft `1.20.1`，不能假设原版 1.20.1 能自动识别 compression scheme `127`；1.20.1 实现应通过 Hassium 读写器接管自定义压缩，同时保持元数据字段与 1.20.5+ 的 `127 + namespaced algorithm` 表达方式可映射。
- 若使用 1.20.1 原版未定义的压缩类型或扩展 payload，必须保证 Hassium 读写器可识别，并在未安装 Hassium 时明确降级或拒绝读取，避免被原版误读造成损坏。
- 对同一 world 可先采用 mirror 模式：原版数据作为权威存储，Hassium 扩展数据作为旁路缓存或候选存储，待稳定后再切换为主存储。

可行性评审：已实现。通过 `MixinRegionFile` 拦截原版 RegionFile，使用 `MetadataTable` 管理区块时间戳，`HassiumEnvelope` 格式存储压缩数据。客户端使用统一的 `HassiumRegionFile` 实现。服务端和客户端共用 `MetadataTable` 类。

### 3.2 ZSTD 压缩与专用字典

Hassium 应使用 ZSTD（Zstandard，高性能压缩算法）压缩区块数据，并支持专用字典。

需求：

- 区块保存时使用 ZSTD 压缩。
- 支持无字典压缩作为默认可用路径。
- 支持基于样本区块数据训练字典。
- 字典应有唯一 ID、版本、适用范围和校验值。
- 区块数据中必须记录使用的字典 ID，避免字典更新后无法解压旧数据。
- 当字典缺失或损坏时，应拒绝写入或进入只读降级模式，不能静默写出不可恢复数据。

字典训练建议：

- 从多个维度、多个区域、不同地形和不同 MOD 数据环境采样。
- 区分 Overworld、Nether、End 以及大型 MOD 维度的样本集。
- 训练前应保留原始样本与训练配置，方便复现字典。
- 字典更新应采用追加版本，不应覆盖旧字典。

可行性评审：可实现。Java 生态中可使用成熟 ZSTD 库，但需要评估 Fabric 与 Forge 打包、原生库加载、服务端无图形环境、不同操作系统兼容性。若引入 JNI（Java Native Interface，本地库调用接口）版本，需额外测试 Windows、Linux x64 与常见服务器环境。

### 3.3 客户端区块缓存

客户端应维护本地区块缓存，缓存从服务端接收后卸载的区块数据，用于降低重复下载与远距离查看带宽消耗。

需求：

- 客户端可配置缓存启用状态、缓存目录、最大容量、最大保留时间和清理策略。
- 客户端在区块卸载时保存该区块最近一次从服务端获得的渲染相关数据。
- 缓存使用统一的 `HassiumRegionFile` 格式（基于 .mca），包含 `MetadataTable` 用于快速读取时间戳。
- 缓存键必须至少包含服务器标识、世界或存档标识、维度、区块坐标。
- 时间戳统一使用服务端的 `inhabitedTime`（区块被玩家居住的总时间），确保服务端和客户端可直接比较。
- 正常视距范围内的区块仍通过服务端获取，以保证交互与同步正确。
- 超出指定范围的区块可使用本地缓存展示，但必须明确其为非权威数据。
- 玩家再次进入缓存区块范围时，需要根据服务端权威数据刷新。

缓存范围建议：

- `server_authoritative_radius`：服务端权威同步半径，默认使用原版视距或服务器发送范围。
- `client_cache_render_radius`：客户端缓存展示半径，必须大于或等于权威同步半径。
- 超出权威同步范围的缓存区块只参与视觉展示，不参与实体、光照更新之外的服务端逻辑。

可行性评审：已实现。通过 `MixinClientLevel.unload()` 在区块卸载时保存缓存，使用 `MetadataTable` 快速读取时间戳，`inhabitedTime` 作为统一时间戳。性能验证通过，本项目代码不在性能消耗占比高的方法中。

### 3.4 区块缓存版本校验与跳过传输

当客户端已经存在某个区块缓存时，应通过服务端权威缓存元数据判断区块是否变化，未变化时避免下载完整数据。该机制类似 HTTP ETag（实体标签）或 Last-Modified（最后修改时间）的思路，但以服务端维护的数据为准。

需求：

- 服务端在区块加载或更新时，主动向客户端发送区块元数据（位置 + `inhabitedTime`）。
- 客户端收到元数据后，直接比对本地缓存的 `MetadataTable` 中的时间戳。
- 缓存命中时，客户端从本地缓存加载区块，无需请求服务端。
- 缓存未命中时，客户端向服务端发送数据请求，服务端通过线程池异步压缩并发送区块数据。
- 服务端和客户端都使用 `inhabitedTime` 作为时间戳，确保可直接比较。
- 元数据不一致、缺少缓存或服务端策略禁止时，回退为完整区块传输。

架构设计：

- 服务端：`ServerChunkPushManager` 管理数据请求队列，使用线程池异步处理。
- 客户端：`ClientCacheLoadQueue` 管理区块加载队列，使用线程池异步加载。
- 所有处理异步，不阻塞主线程。
- 队列使用优先级队列，玩家距离越近优先级越高。

可行性评审：已实现。服务端通过 `ChunkMetadataS2CPacket` 推送元数据，客户端通过 `MetadataTable` 快速比对，`ServerChunkPushManager` 和 `ClientCacheLoadQueue` 使用线程池异步处理。集成测试通过，性能验证通过。

### 3.5 服务端数据包压缩替换

Hassium 应在服务端网络层支持更高效的数据包压缩方式，降低区块发送和其他大包的带宽占用。

需求：

- 仅在客户端与服务端均安装 Hassium，并通过握手确认能力后启用。
- 支持配置压缩算法、压缩等级、最小压缩阈值和是否仅压缩区块相关数据包。
- 支持保留原版压缩路径，用于兼容未安装 MOD 的客户端。
- 支持运行时统计压缩前后字节数、耗时、命中包类型和异常次数。
- 压缩失败时应关闭增强压缩并回退到原版协议，不能断开所有玩家连接。

建议算法：

- 首选 ZSTD，用于大包与区块数据。
- 对很小的数据包跳过压缩，避免 CPU 开销大于带宽收益。
- 可根据包大小选择压缩等级，例如区块数据使用中等等级，小包不压缩。

可行性评审：可实现，但需要谨慎。Minecraft 网络层基于 Netty pipeline，原版已有压缩处理器。替换压缩逻辑涉及协议兼容、登录阶段协商和与其他网络 MOD 的顺序问题。建议先以“自定义区块数据通道压缩”实现，再评估是否替换全局 packet compression（数据包压缩）。

### 3.6 配置与兼容策略

需求：

- 服务端配置应能独立控制 Region 兼容扩展存储、ZSTD 字典、客户端缓存协商、增强网络压缩。
- 客户端配置应能控制本地缓存目录、容量、清理周期、展示半径和是否发送缓存元数据。
- 配置变更应明确哪些需要重启，哪些可热更新。
- 对未安装 Hassium 的客户端必须保持可连接，除非服务端显式启用强制模式。
- 对已有世界默认不应立即破坏性迁移，应先以旁路或渐进迁移方式运行。

建议配置项：

- `storage.enabled`
- `storage.mode = readonly_vanilla | mirror | hassium_only`
- `storage.zstd.level`
- `storage.zstd.dictionary_id`
- `storage.region_custom_scheme = hassium_legacy | scheme_127`
- `client_cache.enabled`
- `client_cache.max_size_mb`
- `client_cache.render_radius`
- `network.enabled`
- `network.compression_algorithm`
- `network.min_packet_size`
- `compat.require_client_mod`

## 4. 可行性总评

| 功能 | 可行性 | 风险 | 当前状态 | 评审结论 |
| --- | --- | --- | --- | --- |
| Region 兼容扩展存储 | 可实现 | 高 | ✅ 已实现 | 比独立外层格式更稳妥，已通过 Mixin 接管读写 |
| ZSTD 压缩 | 可实现 | 中 | ✅ 已实现 | zstd-jni 稳定运行，压缩比 6:1 ~ 9:1 |
| 专用字典训练 | 可实现 | 中 | ✅ 已实现 | DictionaryTrainer 支持真实存档样本 |
| 1.20.5+ compression scheme 127 兼容 | 可实现 | 中 | ✅ 已抽象 | CompressionAlgorithmId 已设计，迁移工具已实现 |
| 客户端缓存卸载区块 | 可实现 | 中 | ✅ 已实现 | 区块卸载时保存，MetadataTable 快速读取，统一 RegionFile |
| 区块缓存版本校验 | 可实现 | 低 | ✅ 已实现 | 服务端推送元数据，客户端比对 MetadataTable，线程池异步处理 |
| 服务端压缩替换 | 可实现 | 高 | ✅ 已实现 | 自定义通道 ZSTD 压缩，双方安装时启用 |
| 原版/其他 MOD 兼容 | 部分可实现 | 高 | ⚠️ 待测试 | 默认保守、可降级、可关闭，需更多兼容性测试 |

总体结论：该 MOD 已通过 Fabric 平台端到端验证。区块压缩传输、客户端缓存（Region 文件存储）、缓存协商协议、元数据推送机制均已实现并验证通过。性能验证通过，本项目代码不在性能消耗占比高的方法中。

## 5. 推荐实施路线

### 阶段 1：基准测试与压缩原型 ✅ 已完成

- [x] 收集不同世界、不同维度、不同 MOD 环境下的区块样本。
- [x] 对原版 GZip/Zlib、ZSTD 无字典、ZSTD 有字典进行压缩率和耗时测试。
- [x] 产出压缩等级、字典大小、样本规模的推荐参数。
- [x] 不接入游戏主流程，仅做离线工具和测试。

### 阶段 2：Region 兼容旁路存储 ✅ 已完成

- [x] 保持原版存储可用，同时额外写入 Hassium Region 兼容扩展数据。
- [x] 对比原版 Zlib payload 与 Hassium ZSTD payload 的读写结果、体积和耗时。
- [x] 加入完整性校验、格式版本、压缩算法命名空间和字典版本管理。
- [x] 在稳定前不默认删除或替代原版可读 payload。
- [x] 在 1.20.1 阶段使用 Hassium 自定义读写器；在设计上预留升级到 1.20.5+ 后使用 compression scheme `127` 的迁移路径。

### 阶段 3：自定义区块传输优化 ✅ 已完成

- [x] 增加客户端/服务端握手能力协商。
- [x] 实现区块缓存索引与 `inhabitedTime` 等元数据交换。
- [x] 命中缓存时跳过完整区块传输，未命中时回退原版区块发送。
- [x] 服务端通过 `ChunkMetadataS2CPacket` 主动推送元数据。
- [x] 客户端通过 `MetadataTable` 快速比对缓存。
- [x] 服务端 `ServerChunkPushManager` 使用线程池异步处理数据请求。
- [x] 客户端 `ClientCacheLoadQueue` 使用线程池异步加载缓存。
- [x] 先限定双方安装 Hassium 的环境。
- [ ] Forge 平台适配（待完成）

### 阶段 4：客户端视觉缓存 ✅ 已完成

- [x] 在客户端保存卸载区块的渲染数据（统一 Region 文件格式 .mca）。
- [x] 使用 `MetadataTable` 快速读取区块时间戳。
- [x] 统一使用 `inhabitedTime` 作为时间戳。
- [x] 实现缓存容量限制、过期清理和维度隔离。
- [x] 超出指定范围的区块可使用本地缓存展示。
- [x] 玩家再次进入缓存区块范围时，根据服务端权威数据刷新。
- [x] 集成测试通过，性能验证通过。
- [ ] renderOnly 超视距渲染（骨架已存在，待稳定）
- [ ] Forge 平台适配（待完成）

### 阶段 5：网络压缩增强 ✅ 已完成

- [x] 先对 Hassium 自定义通道启用 ZSTD。
- [x] 基准验证收益后，再评估替换或包裹原版 Netty 压缩处理器。
- [x] 增加压缩耗时预算，避免服务端 TPS 下降。

## 6. 主要风险

- 存档损坏风险：区块保存格式错误会造成不可逆数据丢失，必须优先设计备份、校验和回滚。
- 数据修复风险：Minecraft DataFixerUpper（数据版本迁移系统）与 Region 兼容扩展 payload 的集成需要仔细处理。
- 多 MOD 兼容风险：其他 MOD 可能修改区块序列化、网络包、维度系统或客户端渲染。
- CPU 开销风险：更强压缩可能节省带宽但增加服务端 CPU 压力。
- 客户端体验风险：缓存区块过期可能导致玩家看到错误地形。
- 协议兼容风险：网络层修改可能影响代理、转发服务、反作弊或协议兼容 MOD。

## 7. 建议补充功能

- 提供离线迁移工具：支持原版 RegionFile 与 Hassium Region 兼容扩展格式互转。
- 提供世界级备份提示：首次启用 Region 兼容扩展存储前提醒管理员备份。
- 提供统计命令：查看压缩率、缓存命中率、节省带宽、ZSTD 耗时和异常次数。
- 提供调试导出：导出单个区块的新旧格式数据，方便排查损坏。
- 提供 per-dimension（按维度）策略：不同维度可使用不同字典或关闭缓存。
- 提供兼容模式：发现未知网络处理器或存储冲突时自动降级。
- 提供性能预算：例如单 tick 压缩耗时超过阈值时降低压缩等级或暂停增强压缩。
- 提供 1.20.5+ 升级迁移工具：将 1.20.1 阶段的 Hassium 压缩算法标识迁移或映射为 compression scheme `127` 的 namespaced algorithm。

## 8. 验收标准

### 8.1 存储

- 在测试世界中，Hassium Region 兼容扩展数据总体积相比原版 Zlib payload 至少降低 20%，目标降低 30% 以上。
- 随机读取单个区块的平均耗时不显著高于原版，目标不超过原版 120%。
- 断电、进程崩溃或异常关闭后，不出现索引与数据不可恢复不一致。
- 字典缺失时能够明确报错或降级，不写出不可解压数据。
- 1.20.1 阶段写入的 Hassium 压缩算法标识，必须能无歧义迁移或映射到 1.20.5+ 的 compression scheme `127` namespaced algorithm。

### 8.2 网络

- 在重复经过同一区域的场景中，客户端缓存命中后区块传输字节数明显下降。
- 客户端未安装 Hassium 时仍可按原版逻辑连接服务端。
- `chunkRevision`、更新时间、数据版本或缓存格式不一致时必须回退完整区块传输。
- 增强压缩启用后，服务端 TPS 不应出现可观测下降。

### 8.3 客户端缓存

- 缓存目录容量限制有效，超过限制后自动清理。
- 不同服务器、维度和世界之间不会错误复用缓存。
- 进入服务端权威同步范围后，显示内容能被服务端数据刷新。
- 清理缓存后客户端仍能正常从服务端获取区块。

### 8.4 兼容与稳定性

- 单人游戏、专用服务端、Forge 客户端、Fabric 客户端分别完成基础启动测试。
- 与至少一组常见区块或世界生成 MOD 进行兼容性测试。
- 启用和关闭每个主要功能后，已有世界均可继续加载。
- 所有高风险功能默认可单独关闭。

## 9. 结论

Hassium 的目标方向明确，已通过 Fabric 平台端到端验证。区块压缩传输、客户端缓存（统一 Region 文件存储）、缓存协商协议、元数据推送机制均已实现并验证通过。

**架构优化成果**：
- 服务端主动推送元数据，客户端自主决策，简化了缓存查询流程
- 统一使用 `MetadataTable` 管理区块时间戳，快速读取（8 bytes）
- 统一使用 `inhabitedTime` 作为时间戳，服务端和客户端可直接比较
- 使用线程池异步处理，不阻塞主线程
- 性能验证通过，本项目代码不在性能消耗占比高的方法中

当前主要剩余工作：
1. **Forge 平台适配**：将 Fabric 验证通过的功能移植到 Forge
2. **renderOnly 超视距渲染**：骨架已存在，需在缓存闭环稳定后推进
3. **兼容性测试**：更多 MOD 和环境的兼容性验证
4. **动态线程池**：根据队列深度动态调整线程池大小
5. **预加载优化**：客户端可预加载玩家移动方向上的区块
