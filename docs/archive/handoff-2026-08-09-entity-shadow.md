> 归档：交接已完成（实体接入影子端已落地）（2026-08-09）
# Handoff — 实体数据接入影子端（2026-08-09）

需求定稿：`.omp/workflows/entity-shadow/REQ.md` + `TASKS.md`（读这两个文件为主，本文档为执行方式与上下文摘要）。

## 背景摘要

影子端（`ShadowSeedServer`，进程内影子服务端，`hassium_cache/<serverId>/world`）目前只接收方块+BE+光。本次把实体数据接入：客户端拦截官方实体同步包（7 类）→ 进程内对象直传影子端 → 影子端重建/更新/移除实体（挂入 `LevelChunk`）→ saveAll 原版序列化自然落盘 → R2 由原版 ChunkMap 恢复。服务端零改动。同时删 `clientCache.entitySnapshotsEnabled` 配置键（10 处）并按 T1 结论处理 `EntitySnapshotCompat`。

**用途边界（用户 2026-08-09 澄清，验收以此为准）**：影子端实体**仅写入本地缓存**（`entities/` 目录），供 `/hassiumc export` 导出存档使用（导出=递归拷贝 world/，含 entities/）；**不读取**——R2 不依赖实体恢复（ChunkMap 附带读回内存属原版机制，无人消费，供防重复查重用，不主动拦截）；**不下发客户端**——客户端实体永远来自服务端原版包，`applyEntityPacket` 唯一调用方是客户端转发（单向）。

## 已确认决策（用户拍板）

- 传输 = 官方数据包拦截转发（不自造快照包）；数据形态官方包
- 落盘 = 影子端 saveAll 原版序列化
- 客户端 = 纯转发，删 entitySnapshotsEnabled 键
- 验收 = 跑真实客户端+服务端，R2 重连验证实体恢复

## 关键技术锚点（调研已确认）

- 服务端无实体推送代码；官方 `ClientboundAddEntityPacket` 独立于区块包、无拦截，客户端 vanilla 处理
- 影子端：`injectChunk`/`applySectionDelta` 只处理方块+BE+光；`saveAll` 用原版 `ChunkSerializer.write`/`SerializableChunkData` → chunkMap.write（type 126+hash 由 MixinRegionFile 挂钩），**chunk 实体集合非空即自然落盘**；R2 由原版 ChunkMap 磁盘加载恢复
- 现成模板：BE 通道（服务端 collect → BlockEntityDataS2CPacket → 客户端 `ClientMetadataHandler` 转发 → MainThreadDispatcher 异步 apply）
- 客户端 listener mixin：`MixinClientPacketListener`（1.20.1 ClientPacketListener）+ `MixinClientCommonPacketListenerImpl`（1.20.2+）；实体包 handler 全在 `ClientPacketListener`（mojmap 名稳定：handleAddEntity 等）
- 客户端与影子端同进程，对接**对象直传零压缩**（项目既有约定）
- `EntitySnapshotCompat`（compat/）现无引用（死代码），注释指向「1.21.6+ TagValueOutput」——实体 NBT save API 跨版本兼容层，本次接入的候选底座
- 配置键 `entitySnapshotsEnabled` 共 10 处：ConfigSchema/ConfigSnapshotAdapter/FabricTomlConfigIO/HassiumConfig/HassiumClothConfigScreen/en_us.json/zh_cn.json/docs/config-audit.md（+ plans/specs 历史文档不清理）
- 仓库已提交（1e3d1c1），工作区干净；master 分支

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序：T1（scout 事实表）先行 → T2/T3/T4 并行（契约在 TASKS.md「共享契约」）→ T5（编译）→ T6（冒烟）
- 子代理开工先报 ETA；主会话 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/entity-shadow/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾
- T1 scout 只读：事实表以输出返回，主会话落盘 `.omp/workflows/entity-shadow/work/T1-facts.md` 后经 `local://` 传给 T2/T3/T4

## 验收标准（REQ 节选）

R2 实体恢复实测一致；落盘实体 NBT 完整；客户端纯转发无消费；entitySnapshotsEnabled 零残留；common 九段 + fabric 编译绿；R1/R2 区块链路不回归。
