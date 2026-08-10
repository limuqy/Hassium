package io.github.limuqy.mc.hassium.network.seedgen;

#if MC_VER < MC_1_21_9
import com.mojang.authlib.GameProfile;
#else
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
#endif

#if MC_VER >= MC_1_21_11
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
#endif

#if MC_VER >= MC_1_20_5
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
#endif

import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.logging.LogUtils;
import io.github.limuqy.mc.hassium.compat.BlockEntityCompat;
import io.github.limuqy.mc.hassium.compat.EntityPacketCompat;
import io.github.limuqy.mc.hassium.mixin.ServerLevelAccessor;
import io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
#if MC_VER >= MC_1_21_2
import net.minecraft.world.entity.EntitySpawnReason;
#endif
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
#if MC_VER < MC_1_20_5
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
#else
import net.minecraft.server.level.ChunkResult;
#endif
#if MC_VER < MC_1_21_9
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
#else
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
#endif
#if MC_VER >= MC_1_21_1
import net.minecraft.ReportType;
#endif
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
#if MC_VER >= MC_1_21_9
import net.minecraft.server.notifications.EmptyNotificationService;
#endif
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.LevelStorageSource;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
import org.slf4j.Logger;

/**
 * 影子服务端：客户端进程内、不 spin 的 MinecraftServer 子类，
 * 专用于按服务器下发的 worldSeed 本地生成 FULL 区块（SeedGen Phase 2）。
 * <p>
 * 模板 = 原版 GameTestServer（同样不走正常流程、不 prepareLevels），差异：
 * <ul>
 *   <li>worldSeed 用服务端下发的种子，preset 用 NORMAL（原版主世界，与服务器一致）</li>
 *   <li>overworldData 预置 initialized=true，跳过 setInitialSpawn 的 spawn 区块生成</li>
 *   <li>区块生成靠 {@link ServerChunkCache#getChunk} 的 managedBlock 驱动任务队列，
 *       任意线程阻塞调用即可，无需本 server 跑 tick 循环</li>
 * </ul>
 */
public class ShadowSeedServer extends MinecraftServer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Services 最小化（1.20.1 四参 / 1.21.11 五参，后者补 mock resolver 防 NPE） */
    private static final Services NO_SERVICES = buildNoServices();

    private final WorldStem stem;
    private final long worldSeed;
    /** 持久世界根（客户端缓存目录下原版存档结构；断连保存、重连复用，不删除）。 */
    private final java.nio.file.Path worldRoot;
    /**
     * 注入区块表（pos → LevelChunk）：注入的区块不经 ChunkMap 正规加载流程
     * （不 worldgen、无 ChunkHolder），由本表持有——打包（buildPacket）与
     * 保存（saveAll）直接取用；REPLACE 覆盖。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, net.minecraft.world.level.chunk.LevelChunk>
            injectedChunks = new java.util.concurrent.ConcurrentHashMap<>();

    private ShadowSeedServer(Thread thread,
                             LevelStorageSource.LevelStorageAccess access,
                             PackRepository repo,
                             WorldStem stem,
                             long seed,
                             java.nio.file.Path worldRoot) {
        super(thread, access, repo, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(), NO_SERVICES,
#if MC_VER < MC_1_20_5
                LoggerChunkProgressListener::new);
#elif MC_VER < MC_1_21_9
                LoggerChunkProgressListener::create);
#else
                LoggingLevelLoadListener.forDedicatedServer());
#endif
        this.stem = stem;
        this.worldSeed = seed;
        this.worldRoot = worldRoot;
    }

    static ShadowSeedServer create(Thread thread,
                                   LevelStorageSource.LevelStorageAccess access,
                                   PackRepository repo,
                                   WorldStem stem,
                                   long seed,
                                   java.nio.file.Path worldRoot) {
        return new ShadowSeedServer(thread, access, repo, stem, seed, worldRoot);
    }

    @Override
    public boolean initServer() {
        // 影子服务端上下文：存储层（MixinRegionFile）据此固定写 Hassium type 126 + hash。
        // 位于 loadLevel 之前（RegionFile 在 createLevels 装配存档时即创建）。
        // MixinMinecraftServer.onServerInit（INVOKE initServer 前）写入 dedicated=false，
        // 此处覆盖为 shadow=true——时序安全（先 false 后 true）。
        io.github.limuqy.mc.hassium.server.RuntimeServerContext.setShadowServer(true);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.clear();
        this.setPlayerList(new PlayerList(this, this.registries(), this.playerDataStorage,
#if MC_VER < MC_1_21_9
                1
#else
                new EmptyNotificationService()
#endif
        ) {});
        this.loadLevel();
        // 缓存清理热度索引加载（跨会话累计；损坏/缺失 → 空索引）
        ShadowCacheEviction.load(worldRoot);
        LOGGER.info("Hassium: Shadow seed server started (seed={})", worldSeed);
        return true;
    }

    @Override
    protected void loadLevel() {
        this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
#if MC_VER < MC_1_20_5
        this.createLevels(new LoggerChunkProgressListener(11));
#elif MC_VER < MC_1_21_9
        this.createLevels(LoggerChunkProgressListener.create(11));
#else
        this.createLevels();
#endif
        this.forceDifficulty();
        // 不调用 prepareLevels()：不等待 441 ticking 区块，按需生成
    }

    /** 单区块生成超时：原版 worldgen 卡死时兜底回退（3s ≫ 正常生成耗时）。 */
    private static final long GENERATION_TIMEOUT_NANOS = 3_000_000_000L;

    /**
     * 生成一个 FULL 区块（任意线程可调；超时/中断返回 null → 调用方回退全量请求）。
     * <p>
     * 非主线程路径（本 server 的 mainThread 是装配线程）：getChunkFuture 提交到
     * mainThreadProcessor，由 {@link #runMainLoop()} 驱动完成；本方法只轮询不阻塞。
     */
    public LevelChunk generateChunk(ChunkPos pos) {
        ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
        long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
#if MC_VER < MC_1_20_5
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
#else
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
#endif
        while (!future.isDone()) {
            if (System.nanoTime() > deadline) {
                return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                return null;
            }
        }
#if MC_VER < MC_1_20_5
        ChunkAccess chunk = future.join().left().orElse(null);
#else
        ChunkAccess chunk = future.join().orElse(null);
#endif
        return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
    }

    /** shutdown 用：资源引用 */
    WorldStem stem() {
        return stem;
    }

    /**
     * 注入一个服务端区块包（任意线程可调）：空壳 LevelChunk（不 worldgen）+
     * packet 数据填充 + 清光触发引擎传播重算。
     * <p>
     * 影子端是冻结后端：区块数据只来源于服务端 packet（{@code replaceWithPacketData}
     * 整柱替换），本 server 不生成世界。种子仅用于 ServerLevel 装配，不影响注入数据。
     * <p>
     * 清光 = 原版剥光同款机制：queueSectionData(null) 清数据层 → 引擎传播重算 →
     * propagateLightSources 推发光源。全部经 ThreadedLevelLightEngine 异步任务
     * （runMainLoop 已驱动 tryScheduleUpdate）。注入失败返回 false（调用方走单柱兜底）。
     */
    public boolean injectChunk(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        try {
            ServerLevel level = this.overworld();
            LevelChunk chunk = new LevelChunk(level, pos); // 空壳，不 worldgen
            ClientboundLevelChunkPacketData data = packet.getChunkData();
            chunk.replaceWithPacketData(data.getReadBuffer(), data.getHeightmaps(),
                    data.getBlockEntitiesTagsConsumer(pos.x, pos.z));
            clearChunkLight(pos, chunk);
            // 计算 contentHash（section hash combine，与网络 chunkHash 同算法）写入存储桥，
            // 保存（type 126 payload 带 hash）与 R2 比对（远程权威 hash vs 本地存储 hash）共用。
            try {
                long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos, contentHash);
            } catch (Throwable hashError) {
                LOGGER.debug("Hassium: Shadow contentHash compute failed for {}, skip hash write", pos);
            }
            injectedChunks.put(ChunkPos.asLong(pos.x, pos.z), chunk);
            ShadowCacheEviction.recordAccess(pos);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow light inject failed for {}", pos, t);
            return false;
        }
    }

    /**
     * 清光公共方法（injectChunk / relightChunk 共用）：清空整柱全部 section 光数据层 →
     * 引擎传播重算 → 推发光源。原版剥光同款机制（{@code queueSectionData(null)} 清数据层），
     * 全部经 ThreadedLevelLightEngine 异步任务（runMainLoop 已驱动 tryScheduleUpdate）。
     */
    private void clearChunkLight(ChunkPos pos, LevelChunk chunk) {
        ThreadedLevelLightEngine lightEngine =
                (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
        int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
        int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(chunk);
        for (int y = minSection; y < maxSection; y++) {
            SectionPos sp = SectionPos.of(pos, y);
            lightEngine.queueSectionData(LightLayer.SKY, sp, null);
            lightEngine.queueSectionData(LightLayer.BLOCK, sp, null);
            lightEngine.updateSectionStatus(sp, false);
        }
        lightEngine.propagateLightSources(pos);
    }

    /**
     * 磁盘命中 + hash 一致但光标脏：本地重算光照（不请求网络全量）。
     * <p>
     * 区块已加载进影子端（{@link #injectLoadedChunk} 先行），内容与远程权威一致，
     * 仅光欠（保存时未收敛落盘）——清光重算（{@link #clearChunkLight}）后引擎传播
     * 期间视为欠光：起始即标脏（防 R2 读盘直接打包欠光数据），收敛后由
     * pushReady(converged=true) 清除；补发给客户端的欠光由光照更新桥梁承担。
     * 任意线程可调（调用方在后台池）。
     */
    public void relightChunk(ChunkPos pos, LevelChunk chunk) {
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, true);
        clearChunkLight(pos, chunk);
    }

    /**
     * 应用服务端分段增量（SectionDeltaS2CPacket）到已注入区块。
     * <p>
     * 步骤（镜像 {@code replaceWithPacketData} 语义，按 delta 包裁剪）：
     * <ol>
     *   <li>变更 sections：{@code LevelChunkSection.read(buf)} 就地覆盖
     *       （counts+states+biomes 官方网络格式，与服务端 serializeSection 对称）</li>
     *   <li>heightmaps：逐 type {@code setHeightmap}（delta 包随附服务端 rawData——
     *       直接改 section 不会自动更新高度图）</li>
     *   <li>blockEntity：服务端发整 chunk BE 快照 → 先清旧表，再镜像官方
     *       replaceWithPacketData 的 consumer（IMMEDIATE 创建 + type 校验 + load）</li>
     *   <li><b>光</b>：delta 不含光照 → 变更 section 清光（{@code queueSectionData(null)}，
     *       injectChunk 同款），{@code propagateLightSources} 推发光源，引擎传播重算——
     *       收敛由调用方（ShadowLightCompute consumeLoop）批级采样；欠光由光照更新
     *       桥梁（collectLightUpdate → drainLightMasks）事件驱动补发</li>
     * </ol>
     * 完成后重算 contentHash 写存储桥（后续 hash 比对 / R2 落盘复用）。
     * 仅 {@code consumeLoop} 单线程调用；失败返回 false（调用方回退全量请求）。
     */
    public boolean applySectionDelta(ChunkPos pos,
                                     List<SectionDeltaS2CPacket.SectionData> changedSections,
                                     List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
                                     List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {
        try {
            LevelChunk chunk = injectedChunks.get(ChunkPos.asLong(pos.x, pos.z));
            if (chunk == null) {
                LOGGER.debug("Hassium: Shadow applySectionDelta chunk not injected ({}, {})", pos.x, pos.z);
                return false;
            }
            ThreadedLevelLightEngine lightEngine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            // 1) sections 就地覆盖（先于 BE——BE 创建依赖新 block state）
            for (SectionDeltaS2CPacket.SectionData sd : changedSections) {
                LevelChunkSection section = chunk.getSection(sd.sectionIndex());
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(sd.blockData()));
                try {
                    section.read(buf);
                } finally {
                    buf.release();
                }
                // 2) 变更 section 清光：delta 无光，旧光已过期 → 引擎重算
                SectionPos sp = SectionPos.of(pos, chunk.getSectionYFromSectionIndex(sd.sectionIndex()));
                lightEngine.queueSectionData(LightLayer.SKY, sp, null);
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, null);
                lightEngine.updateSectionStatus(sp, false);
            }
            // 3) heightmaps 逐 type 覆盖
            Heightmap.Types[] types = Heightmap.Types.values();
            for (SectionDeltaS2CPacket.HeightmapData hm : heightmaps) {
                if (hm.typeId() >= 0 && hm.typeId() < types.length) {
                    chunk.setHeightmap(types[hm.typeId()], hm.data());
                }
            }
            // 4) BE 全量覆盖（服务端发整 chunk BE 快照）：先清旧，再镜像官方
            // replaceWithPacketData 的 consumer（IMMEDIATE 创建 + load）。不做 type 校验：
            // IMMEDIATE 创建的 BE 类型必然匹配新 block state（delta 数据自洽），
            // 且 1.21.11 Registry.get 返回 Optional，跨版本校验成本大于收益。
            for (BlockPos bp : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                chunk.removeBlockEntity(bp);
            }
            for (SectionDeltaS2CPacket.BlockEntityData bed : blockEntities) {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        chunk.getBlockEntity(bed.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
                if (be != null && bed.nbt() != null) {
                    BlockEntityCompat.loadFromTag(be, bed.nbt(), this.overworld().registryAccess());
                }
            }
            lightEngine.propagateLightSources(pos);
            // 5) 重算 contentHash 写存储桥（R2 比对 / 断连落盘复用）
            try {
                long contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos, contentHash);
            } catch (Throwable hashError) {
                LOGGER.debug("Hassium: Shadow contentHash recompute failed for {}, skip hash write", pos);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow applySectionDelta failed for {}", pos, t);
            return false;
        }
    }

    /**
     * 实体操作串行锁：PersistentEntitySectionManager 的 EntityLookup/sectionStorage
     * 非线程安全（原版仅 server 主线程单写）。入口已统一 execute() 到影子主循环线程
     * （与方块操作同线程，天然串行），锁保留作防御（不再承担跨线程互斥）。
     */
    private final Object entityApplyLock = new Object();

    /**
     * 应用一个官方方块同步包到影子端（任意线程可调；内部 {@code execute()} 投递影子端主线程）。
     * <p>
     * 与客户端同源的服务端方块更新（T2 mixin 转发，不 cancel 原版处理）：内容变更 →
     * 缓存 hash 失效（下次比对现算）+ 光照标脏（读盘不直接打包欠光）。三类包：
     * <ul>
     *   <li>{@code ClientboundBlockUpdatePacket}：单方块，{@code level.setBlock(pos, state, 3)}</li>
     *   <li>{@code ClientboundSectionBlocksUpdatePacket}：分段批量，
     *       {@code packet.runUpdates((pos, state) -> ...)}（两版逐字一致）逐块 setBlock</li>
     *   <li>{@code ClientboundBlockEntityDataPacket}：BE 更新，
     *       {@code BlockEntityCompat.loadFromTag}（镜像原版 handler 语义）</li>
     * </ul>
     * 执行线程 = 影子端主循环线程（mainThreadProcessor 由 {@link #runMainLoop()} 驱动，
     * {@link #generateChunk} 同模式），天然串行；主循环已停（断连/关停）时投递被
     * {@code RejectedExecutionException} 兜底丢弃，无残留。
     */
    public void applyBlockUpdate(Packet<?> packet) {
        if (packet == null) {
            return;
        }
        try {
            this.execute(() -> {
                try {
                    if (packet instanceof ClientboundBlockUpdatePacket block) {
                        applyBlock(block.getPos(), block.getBlockState());
                    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
                        java.util.Set<Long> affected = new java.util.HashSet<>();
                        section.runUpdates((pos, state) -> {
                            this.overworld().setBlock(pos, state, 3);
                            affected.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                        });
                        invalidateChunkContent(affected);
                    } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
                        applyBlockEntity(blockEntity);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Hassium: Shadow applyBlockUpdate ignored: {}", t.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 主循环已停（断连竞态）：更新丢弃，数据由下次进服 hash 比对/直推兜底
        }
    }

    /** 单方块应用：注入区块直接 setBlockState（flags 与 {@code level.setBlock(pos, state, 3)} 同源）；未注入跳过并标脏。 */
    private void applyBlock(BlockPos pos, BlockState state) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk != null) {
            // review-fix: T13-FixT3Chunk-2：注入区块不经 ChunkMap，level.setBlock 会触发幻影 worldgen——直接对注入 chunk 应用
#if MC_VER < MC_1_21_5
            chunk.setBlockState(pos, state, false);
#else
            chunk.setBlockState(pos, state, 3);
#endif
        }
        invalidateChunkContent(java.util.Collections.singleton(key));
    }

    /** BE 更新应用：镜像原版 handleBlockEntityData（loadFromTag + setChanged），无 BE 则忽略。 */
    private void applyBlockEntity(ClientboundBlockEntityDataPacket packet) {
        ServerLevel level = this.overworld();
        long key = ChunkPos.asLong(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
        LevelChunk chunk = injectedChunks.get(key);
        if (chunk == null) {
            // review-fix: T13-FixT3Chunk-2：未注入——跳过应用并标脏（hash 比对触发全量回拉）
            invalidateChunkContent(java.util.Collections.singleton(key));
            return;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(packet.getPos());
        if (be == null || packet.getTag() == null) {
            return;
        }
        BlockEntityCompat.loadFromTag(be, packet.getTag(), level.registryAccess());
        be.setChanged();
        invalidateChunkContent(java.util.Collections.singleton(key));
    }

    /**
     * 内容失效（chunkKey 去重集合）：移除 hash 缓存（下次比对现算）+
     * 光照标脏（光不确定，读盘不直接打包欠光数据）。REQ 需求 2 语义。
     */
    private void invalidateChunkContent(java.util.Set<Long> chunkKeys) {
        for (long key : chunkKeys) {
            ChunkPos pos = new ChunkPos(key);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, true);
        }
    }

    /**
     * 应用一个官方实体同步包到影子端（任意线程可调；内部 {@code execute()} 投递影子端
     * 主循环线程，与 {@link #applyBlockUpdate} 同模式——方块/实体操作同线程串行）。
     * <p>
     * 客户端纯转发（T3 mixin 侧只调本方法），进程内对象直传（零编码零压缩）。
     * 按 instanceof 分发 7 类包，镜像原版 ClientPacketListener handler 的实体语义，
     * 但影子端不 tick 实体：全部为瞬时状态操作（挂载/assignValues/setPos/速度/移除），
     * 无插值、无 tick 循环、不阻塞主线程。
     * <p>
     * 容错：实体不存在/类型不匹配/异常一律静默（debug 日志），与 handler 容错一致；
     * 主循环已停（断连/关停）时投递被 {@code RejectedExecutionException} 兜底丢弃，无残留。
     */
    public void applyEntityPacket(Packet<?> packet) {
        try {
            this.execute(() -> {
                synchronized (entityApplyLock) {
                    try {
                        if (packet instanceof ClientboundAddEntityPacket add) {
                            applyAddEntity(add);
                        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
                            applySetEntityData(data);
                        } else if (packet instanceof ClientboundMoveEntityPacket move) {
                            applyMoveEntity(move);
                        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                            applyTeleportEntity(teleport);
                        } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                            applySetEntityMotion(motion);
                        } else if (packet instanceof ClientboundRotateHeadPacket head) {
                            applyRotateHead(head);
                        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
                            applyRemoveEntities(remove);
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("Hassium: Shadow applyEntityPacket ignored: {}", t.toString());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // review-fix: T13-FixT3Chunk-3：主循环已停（断连竞态）：更新丢弃，数据由下次进服 hash 比对/直推兜底
        }
    }

    /**
     * Add：镜像原版 handleAddEntity 重建链 —— {@code type.create(level[, LOAD])} →
     * {@code entity.recreateFromPacket(packet)}（内部 setId/UUID/位置/旋转/运动/data）→
     * {@code ServerLevel.addFreshEntity} 挂载（LevelChunk.addEntity 全段空方法不可用，
     * 见 T1 事实表 ④）。
     * <p>
     * 挂载成功后把实体所在 chunk 可见性置 ENTITY_TICKING：默认 HIDDEN 下实体不进
     * visibleEntityStorage（getEntity(id) 查不到）且 saveAll 走 processChunkUnload
     * 摘除实体；ENTITY_TICKING = accessible（可查、可存）但不 ticking（不进 entityTickList，
     * 影子端零 tick 红线保持）。
     */
    private void applyAddEntity(ClientboundAddEntityPacket packet) {
        ServerLevel level = this.overworld();
        if (level.getEntity(packet.getId()) != null) {
            return; // 防重复（原版 UUID 去重等价：addFreshEntity 内部按 UUID 拒绝重复）
        }
        EntityType<?> type = packet.getType();
        if (type == EntityType.PLAYER) {
            return; // 影子端不重建玩家（1.21.11 原版走 RemotePlayer 专用路径，服务端也不发 AddEntity 玩家）
        }
        Entity entity = type.create(level
#if MC_VER >= MC_1_21_2
                , EntitySpawnReason.LOAD
#endif
        );
        if (entity == null) {
            return;
        }
        entity.recreateFromPacket(packet);
        if (!level.addFreshEntity(entity)) {
            return;
        }
        this.entityManager().updateChunkStatus(entity.chunkPosition(), FullChunkStatus.ENTITY_TICKING);
    }

    /** SetEntityData：官方 handler 同款 {@code assignValues}，无解析无消费。 */
    private void applySetEntityData(ClientboundSetEntityDataPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.getEntityData().assignValues(packet.packedItems());
        }
    }

    /**
     * MoveEntity（Pos/PosRot/Rot 三重载同构）：相对位移 → 绝对（客户端 VecDeltaCodec
     * 同语义：base + delta/4096；影子端实体位置 = 上次包应用后位置，故当前坐标 + delta）。
     * 旋转按 hasRotation 决定，getter 三段漂移由 EntityPacketCompat 归一。
     */
    private void applyMoveEntity(ClientboundMoveEntityPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = packet.getEntity(level);
        if (entity == null) {
            return;
        }
        if (packet.hasPosition()) {
            entity.setPos(
                    entity.getX() + packet.getXa() / 4096.0,
                    entity.getY() + packet.getYa() / 4096.0,
                    entity.getZ() + packet.getZa() / 4096.0);
        }
        if (packet.hasRotation()) {
            entity.setYRot(EntityPacketCompat.moveYRot(packet));
            entity.setXRot(EntityPacketCompat.moveXRot(packet));
        }
        entity.setOnGround(packet.isOnGround());
    }

    /**
     * TeleportEntity：段 A–D 包字段直取绝对坐标；段 E+ record 经
     * {@code PositionMoveRotation.calculateAbsolute(prev, change, relatives)} 计算
     * （prev = 实体当前位置/旋转/已知运动，EntityPacketCompat 归一）。
     * yHeadRot 同步 yRot（镜像服务端 teleportSetPosition 语义）。
     */
    private void applyTeleportEntity(ClientboundTeleportEntityPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(EntityPacketCompat.teleportId(packet));
        if (entity == null) {
            return;
        }
        EntityPacketCompat.TeleportState tp = EntityPacketCompat.teleportState(entity, packet);
        entity.setPos(tp.position().x, tp.position().y, tp.position().z);
        entity.setYRot(tp.yRot());
        entity.setYHeadRot(tp.yRot());
        entity.setXRot(tp.xRot());
        if (tp.deltaMovement() != null) {
            entity.setDeltaMovement(tp.deltaMovement());
        }
        entity.setOnGround(tp.onGround());
    }

    /** Motion：setDeltaMovement（A–C int / D–G double / H+ Vec3 由 EntityPacketCompat 归一）。 */
    private void applySetEntityMotion(ClientboundSetEntityMotionPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = level.getEntity(packet.getId());
        if (entity != null) {
            entity.setDeltaMovement(EntityPacketCompat.motionVec(packet));
        }
    }

    /** RotateHead：setYHeadRot（A–D byte / E+ float 由 EntityPacketCompat 归一）。 */
    private void applyRotateHead(ClientboundRotateHeadPacket packet) {
        ServerLevel level = this.overworld();
        Entity entity = packet.getEntity(level);
        if (entity != null) {
            entity.setYHeadRot(EntityPacketCompat.headYRot(packet));
        }
    }

    /**
     * Remove：IntList 逐个按 id 移除。移除路径查证（T1 事实表 ④）：服务端无
     * {@code Level.removeEntity(int, reason)}（仅 ClientLevel 专有）；立即生效且不依赖
     * tick 的公开路径 = {@code entity.remove(DISCARDED)} → setRemoved → levelCallback.onRemove
     * 同步摘除（section + visibleEntityStorage + knownUuids），两版本同构。
     */
    private void applyRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
        ServerLevel level = this.overworld();
        for (int id : packet.getEntityIds()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.remove(Entity.RemovalReason.DISCARDED);
            }
        }
    }

    /** 影子端实体管理器（ServerLevel 私有字段，经 ServerLevelAccessor）。 */
    private PersistentEntitySectionManager<Entity> entityManager() {
        return ((ServerLevelAccessor) this.overworld()).hassium$getEntityManager();
    }

    /**
     * 从影子端存档（磁盘 region，type 126）加载区块——官方 {@code scheduleChunkLoad}
     * 完整链（readChunk → 126 解压（MixinRegionFile 读 hook）→ NBT 解析 → 光照恢复）。
     * 返回的 LevelChunk 带存档光（断连 saveAll 落的收敛光），直接打包即可（无需重算）。
     * <p>
     * R2 缓存命中 / OVD 环带回填共用此入口；失败或存档无此柱返回 null。
     * 注意：官方加载链的 mainThreadExecutor 步骤由 runMainLoop 的 pollTask 驱动，
     * 本方法可在任意线程调用（同步等待 future 完成，与 generateChunk 同模式）。
     */
    public LevelChunk loadFromDisk(ChunkPos pos) {
        try {
            net.minecraft.server.level.ChunkMap chunkMap =
                    ((net.minecraft.server.level.ServerChunkCache) this.overworld().getChunkSource()).chunkMap;
            io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor acc =
                    (io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor) chunkMap;
#if MC_VER < MC_1_21_2
            // 1.20.1~1.21.1：readChunk 拿原始 NBT（126 已由 MixinRegionFile 读 hook 解压），
            // 再走 ChunkSerializer.read 官方解析（含光照恢复）。
            java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>> future =
                    acc.hassium$readChunk(pos);
            long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
            while (!future.isDone()) {
                if (System.nanoTime() > deadline) {
                    LOGGER.warn("Hassium: Shadow loadFromDisk timeout ({}, {})", pos.x, pos.z);
                    return null;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            net.minecraft.nbt.CompoundTag tag = future.join().orElse(null);
            if (tag == null) {
                return null; // 存档无此柱
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
#if MC_VER < MC_1_21_1
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(), pos, tag);
#else
                    // 1.21.1+：read 增加 RegionStorageInfo（官方 ChunkMap 构造同款取值）
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                            this.overworld(), this.overworld().getPoiManager(),
                            new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                                    this.storageSource.getLevelId(), this.overworld().dimension(), "chunk"),
                            pos, tag);
#endif
            return toLevelChunk(accChunk, pos);
#else
            // 1.21.2+：官方 scheduleChunkLoad 完整链（SerializableChunkData 解析）。
            // TODO(版本推广)：1.21.2+ 分段适配（readChunk → SerializableChunkData.parse+read）
            CompletableFuture<?> future = acc.hassium$scheduleChunkLoad(pos);
            long deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS;
            while (!future.isDone()) {
                if (System.nanoTime() > deadline) {
                    return null;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            net.minecraft.world.level.chunk.ChunkAccess accChunk =
                    (net.minecraft.world.level.chunk.ChunkAccess) future.join();
            if (accChunk == null) {
                return null;
            }
            return toLevelChunk(accChunk, pos);
#endif
        } catch (Throwable t) {
            LOGGER.debug("Hassium: Shadow loadFromDisk failed for {}", pos, t);
            return null;
        }
    }

    /** 官方加载产物为 ProtoChunk（ChunkSerializer.read 语义）：FULL 转换同款。 */
    private LevelChunk toLevelChunk(net.minecraft.world.level.chunk.ChunkAccess accChunk, ChunkPos pos) {
        // 读盘命中（R2 缓存复用）记一次访问：容量清理热度评分用。
        // 覆盖 consumeLoop 磁盘优先路径（不经 injectLoadedChunk）与
        // processRemoteHashes 读盘比对路径（两者都经 loadFromDisk）。
        ShadowCacheEviction.recordAccess(pos);
        if (accChunk instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        return new LevelChunk(this.overworld(), (net.minecraft.world.level.chunk.ProtoChunk) accChunk,
                chunk -> { });
    }

    /** 注入区块表取用（打包/保存；未注入返回 null）。 */
    public net.minecraft.world.level.chunk.LevelChunk injectedChunk(int x, int z) {
        return injectedChunks.get(ChunkPos.asLong(x, z));
    }

    /**
     * 读盘命中（hash 比对一致）区块加载进影子端表：后续 hash 到达直接内存比对
     * （无需再读盘）；saveAll 落盘复用同一表。区块带存档收敛光，无需重算。
     */
    public void injectLoadedChunk(ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk) {
        injectedChunks.put(ChunkPos.asLong(pos.x, pos.z), chunk);
    }

    /**
     * 影子端存档布隆位图（R2 重连握手上报）：扫描全部 region 文件头部位图
     * （每 region 256 块存在位），存在的区块放入 Bloom（含 dimension 混淆，
     * 与 {@code ChunkBloomFilter.put} 语义一致）。维度以存档维度名为准
     * （overworld 存档单维度：影子端仅存主世界）。
     * <p>
     * 线程：任意线程（region 头读取是轻量 IO，不上锁；调用方在后台池）。
     */
    public io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter buildBloomFilter() {
        io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter =
                io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter.createDefault();
        String dimension = this.overworld().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        try {
            java.io.File regionDir =
                    new java.io.File(this.storageSource.getDimensionPath(this.overworld().dimension()).toFile(), "region");
            java.io.File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            if (files == null) {
                return filter;
            }
            for (java.io.File f : files) {
                int regionX;
                int regionZ;
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca").matcher(f.getName());
                    if (!m.matches()) {
                        continue;
                    }
                    regionX = Integer.parseInt(m.group(1));
                    regionZ = Integer.parseInt(m.group(2));
                } catch (Exception e) {
                    continue;
                }
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                    byte[] header = new byte[4096];
                    raf.readFully(header);
                    for (int i = 0; i < 1024; i++) {
                        int offset = (header[i] & 0xFF) << 16 | (header[i + 1024] & 0xFF) << 8 | (header[i + 2048] & 0xFF);
                        if (offset != 0) {
                            int chunkX = regionX * 32 + (i % 32);
                            int chunkZ = regionZ * 32 + (i / 32);
                            filter.put(chunkX, chunkZ, dimension);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Hassium: Shadow bloom scan failed for {}", f.getName(), t);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: Shadow bloom build failed", t);
        }
        return filter;
    }

    /**
     * 单柱落盘（saveAll / T5 出界卸载共用）：原版序列化 → ChunkMap 存储写
     * （type 126 + hash 由 MixinRegionFile 挂钩）。IOWorker 异步入队，不逐柱 flush
     * （断连 saveAll 统一 flush）。任意线程可调。
     *
     * @return true=已提交写队列；false=序列化/提交异常（调用方自行兜底）
     */
    public boolean saveChunkToDisk(ChunkPos pos, LevelChunk chunk) {
        try {
            ServerLevel level = this.overworld();
#if MC_VER < MC_1_21_2
            net.minecraft.nbt.CompoundTag nbt =
                    net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
#else
            // 1.21.2+：SerializableChunkData 序列化（结构/POI 数据 Phase 2 补全）
            net.minecraft.nbt.CompoundTag nbt =
                    net.minecraft.world.level.chunk.storage.SerializableChunkData
                            .copyOf(level, chunk).write();
#endif
#if MC_VER < MC_1_21_2
            level.getChunkSource().chunkMap.write(pos, nbt);
#else
            // 1.21.2+：write(ChunkPos, Supplier<CompoundTag>)（IOWorker 延迟序列化）
            level.getChunkSource().chunkMap.write(pos, () -> nbt);
#endif
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow chunk save failed for ({}, {})", pos.x, pos.z, t);
            return false;
        }
    }

    /**
     * 单柱卸载（T5 内存区块回收：出界到期柱）：落盘（IOWorker 异步）+ 从注入表
     * 条件移除 + hash 表移除（下次比对经读盘 hook 回填）。落盘前采样全局收敛——
     * 未收敛（欠光落盘）保守标脏（R2 读盘命中不得直接打包，走 relight 链）；
     * 收敛则清除标脏（落盘即收敛光）。与 ShadowCacheEviction（容量淘汰删磁盘）
     * 独立共存：本方法只落盘 + 清内存。
     *
     * @return true=已提交落盘并移除；false=落盘失败（保留内存驻留，断连 saveAll 兜底）
     */
    public boolean unloadChunk(ChunkPos pos, LevelChunk chunk) {
        boolean converged = isLightConverged();
        if (!saveChunkToDisk(pos, chunk)) {
            return false;
        }
        long key = ChunkPos.asLong(pos.x, pos.z);
        injectedChunks.remove(key, chunk); // 条件移除：仅当仍是该 chunk（防并发替换后误删新数据）
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, !converged);
        return true;
    }

    /** 注入区块表条目视图（T5 卸载扫描遍历用；弱一致迭代，任意线程）。 */
    public java.util.Set<java.util.Map.Entry<Long, LevelChunk>> injectedChunkEntries() {
        return injectedChunks.entrySet();
    }

    /**
     * 断连/关停保存（任意线程可调）：注入区块表全量落盘（原版序列化 →
     * ChunkMap 存储写，type 126 + hash 由 MixinRegionFile 挂钩）。影子端不跑
     * tick，原版自动保存不触发；注入区块不经 ChunkMap 正规流程，故显式按表写。
     * 落盘后重连由原版 ChunkMap 从磁盘正规加载恢复。
     */
    public void saveAll() {
        LOGGER.debug("Hassium: Shadow saveAll start, injected={} shadow={}",
                injectedChunks.size(),
                io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext());
        try {
            ServerLevel level = this.overworld();
            // 标脏不在此处做：保存期全局收敛状态不可靠（1.20.1 引擎队列异步传播，
            // 保存开始时队列未清空即误判未收敛 → 全量标脏 → R2 hash 命中全被拦截）。
            // 标脏只在运行期注入链标记：pushReady(converged=false)（converge 超时
            // 的欠光块）才标脏；pushReady(true) 清除。见 ShadowLightCompute。
            for (java.util.Map.Entry<Long, net.minecraft.world.level.chunk.LevelChunk> e : injectedChunks.entrySet()) {
                long key = e.getKey();
                // 官方 ChunkPos 编码：x 低位、z 高位（与 asLong 对称）
                int x = (int) key;
                int z = (int) (key >> 32);
                saveChunkToDisk(new ChunkPos(x, z), e.getValue());
            }
            // 写队列同步落盘（IOWorker.store 异步；halt 前必须 flush）
#if MC_VER < MC_1_21_11
            level.getChunkSource().chunkMap.flushWorker();
#else
            level.getChunkSource().chunkMap.synchronize(true).join();
#endif
            // 实体落盘：实体不在 chunk NBT（ChunkSerializer.write 的 "Entities" 仅 PROTOCHUNK
            // 分支；LevelChunk 无实体存储），真相源是 PersistentEntitySectionManager
            // （EntityStorage → entities/ 目录）。镜像 ServerLevel.save 顺序：chunk 先、实体后。
            // Add 挂载时已把实体所在 chunk visibility 置 ENTITY_TICKING，saveAll 走
            // storeChunkSections（storeEntities 写盘）而非 HIDDEN 的 processChunkUnload（会
            // 把内存实体摘除标记 removed）。FRESH 状态首轮经 saveAll 内部 requestChunkLoad
            // （空盘）+ processPendingLoads 收敛为 LOADED 后写盘。
            try {
                this.entityManager().saveAll();
            } catch (Throwable t) {
                LOGGER.warn("Hassium: Shadow entity save failed", t);
            }
            // 热度索引随存档落盘（跨会话累计；进程内索引由 load/reset 管理）
            ShadowCacheEviction.save(worldRoot);
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow server save failed", t);
        }
    }

    /**
     * 光照是否全局收敛（任意线程可调）：ThreadedLevelLightEngine 任务队列空
     * && 两引擎传播队列（blockNodesToCheck / decrease / increase）空。
     * <p>
     * 影子端世界只有注入任务，队列空即全部算完。1.21.11 的 ConsecutiveExecutor
     * 自驱动 + 异步 runUpdate，调用方须轮询（20ms 间隔）等待。
     */
    public boolean isLightConverged() {
        try {
            ThreadedLevelLightEngine engine =
                    (ThreadedLevelLightEngine) this.overworld().getChunkSource().getLightEngine();
            if (engine.hasLightWork()) {
                return false;
            }
            ThreadedLevelLightEngineAccessor acc = (ThreadedLevelLightEngineAccessor) engine;
            if (!acc.hassium$getLightTasks().isEmpty()) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** shutdown 用：存档访问（MinecraftServer.storageSource 为 protected）。 */
    LevelStorageSource.LevelStorageAccess storageAccess() {
        return this.storageSource;
    }

    private volatile Thread mainThreadLoop;

    /** 装配线程回填（见 {@link #createAndStart} 语义）。 */
    void attachMainThread(Thread main) {
        this.mainThreadLoop = main;
    }

    /**
     * 影子服务端主循环：持续驱动 {@link ServerChunkCache#pollTask()}。
     * <p>
     * 本 server 的 ServerChunkCache.mainThread 是装配线程（=本循环线程），
     * pollTask 覆写链 = runDistanceManagerUpdates（提交 worldgen 任务）+ 队列回调，
     * 等价 GameTestRunner 对 GameTestServer 的 tick 驱动，但不跑完整 tick（无玩家/实体）。
     */
    void runMainLoop() {
        ServerChunkCache cache = (ServerChunkCache) this.overworld().getChunkSource();
        // 服务端光照任务驱动：1.20.1~1.21.10 的任务邮箱由 ServerLevel.tick 的
        // tryScheduleUpdate 投递（影子端不跑完整 tick）；1.21.11 ConsecutiveExecutor
        // 自驱动，此处幂等无害。pre-update 任务（queueSectionData/updateSectionStatus/
        // propagateLightSources）与传播（runLightUpdates）都经它执行。
        ThreadedLevelLightEngine lightEngine = cache.getLightEngine();
        while (!Thread.currentThread().isInterrupted()) {
            boolean worked;
            try {
                worked = cache.pollTask();
            } catch (Throwable ignored) {
                break; // server 已 halt
            }
            try {
                lightEngine.tryScheduleUpdate();
            } catch (Throwable ignored) {
                // 光照任务驱动失败不影响 worldgen 主循环
            }
            if (!worked) {
                // managedBlock 同款：pollTask 覆写优先 runDistanceManagerUpdates，
                // 只有其返回 false 的间隙才消费 mainThreadProcessor 队列；park 100µs 保证高频重试。
                LockSupport.parkNanos("hassium-seedgen-main", 100_000L);
            }
        }
    }

    void stopMainLoop() {
        Thread t = mainThreadLoop;
        mainThreadLoop = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** shutdown 用：持久世界根（LevelStorageAccess 两版本无统一目录 getter）。 */
    java.nio.file.Path worldRoot() {
        return worldRoot;
    }

    /** 影子端世界 region 目录（容量统计/清理扫描用；与 buildBloomFilter 同源）。 */
    java.nio.file.Path regionDir() {
        return this.storageSource.getDimensionPath(this.overworld().dimension()).resolve("region");
    }

    /**
     * 删除磁盘区块（缓存清理调用，任意线程）：chunkMap.write(pos, null) →
     * IOWorker 异步 → RegionFile.clear（offset 置 0 + 释放扇区，下次写入复用）。
     * 内存注入表 / hash 桥 / 热度索引同步移除，防后续比对误命中。
     * <p>
     * 版本差异：1.21.2+ 的 write 接收 Supplier，传 {@code () -> null} 与官方
     * {@code IOWorker.STORE_EMPTY} 同语义（PendingStore.data=null → clear）。
     */
    public void deleteChunk(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        injectedChunks.remove(key);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.remove(pos);
        ShadowCacheEviction.remove(pos);
        try {
            ServerLevel level = this.overworld();
#if MC_VER < MC_1_21_2
            level.getChunkSource().chunkMap.write(pos, null);
#else
            level.getChunkSource().chunkMap.write(pos, () -> null);
#endif
        } catch (Throwable t) {
            LOGGER.warn("Hassium: Shadow chunk delete failed for ({}, {})", pos.x, pos.z, t);
        }
    }

    long worldSeed() {
        return worldSeed;
    }

    // ---- GameTestServer 镜像覆写（不跑 tick 循环，覆写仅满足 abstract 集与防御） ----

    @Override
    public void tickServer(BooleanSupplier hasTimeLeft) {
        super.tickServer(hasTimeLeft);
    }

    @Override
    public void waitUntilNextTick() {
        this.runAllTasks();
    }

    @Override
    public SystemReport fillServerSystemReport(SystemReport systemReport) {
        systemReport.setDetail("Type", "Hassium shadow seed server");
        return systemReport;
    }

    @Override
    public void onServerExit() {
        super.onServerExit();
        LOGGER.info("Hassium: Shadow seed server exiting");
    }

    @Override
    public void onServerCrash(CrashReport crashReport) {
        super.onServerCrash(crashReport);
        LOGGER.error("Hassium: Shadow seed server crashed\n{}",
#if MC_VER < MC_1_21_1
                crashReport.getFriendlyReport()
#else
                crashReport.getFriendlyReport(ReportType.CRASH)
#endif
        );
    }

    @Override
    public boolean isHardcore() {
        return false;
    }

#if MC_VER >= MC_1_20_5 && MC_VER < MC_1_21_11
    /** 1.20.5-1.21.10 段 MinecraftServer 抽象方法（1.21.11 起有默认实现）。 */
    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }
#endif

#if MC_VER >= MC_1_20_5 && MC_VER < MC_1_21_11
    private final SampleLogger sampleLogger = new LocalSampleLogger(4);

    @Override
    public SampleLogger getTickTimeLogger() {
        return this.sampleLogger;
    }
#endif

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

#if MC_VER < MC_1_21_9
    @Override
    public int getOperatorUserPermissionLevel() {
        return 0;
    }

    @Override
    public int getFunctionCompilationLevel() {
        return 4;
    }

    @Override
    public boolean isEpollEnabled() {
        return false;
    }

    @Override
    public boolean isCommandBlockEnabled() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(GameProfile profile) {
        return false;
    }
#elif MC_VER < MC_1_21_11
    @Override
    public int operatorUserPermissionLevel() {
        return 0;
    }

    @Override
    public int getFunctionCompilationLevel() {
        return 4;
    }

    @Override
    public boolean isEpollEnabled() {
        return false;
    }

    @Override
    public boolean isCommandBlockEnabled() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }
#else
    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.ALL;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return LevelBasedPermissionSet.OWNER;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }

    @Override
    public SampleLogger getTickTimeLogger() {
        return this.sampleLogger;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }
#endif

    private static Services buildNoServices() {
#if MC_VER < MC_1_21_9
        return new Services(null, ServicesKeySet.EMPTY, null, null);
#else
        return new Services(null, ServicesKeySet.EMPTY, null,
                new MockUserNameToIdResolver(), new MockProfileResolver());
#endif
    }

#if MC_VER >= MC_1_21_11
    private final SampleLogger sampleLogger = new LocalSampleLogger(4);
#endif

#if MC_VER >= MC_1_21_9
    /** 1.21.9+ Services 需要非 null resolver（PlayerList 构造期可能查询） */
    private static final class MockProfileResolver implements ProfileResolver {
        @Override
        public Optional<GameProfile> fetchByName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<GameProfile> fetchById(UUID uuid) {
            return Optional.empty();
        }
    }

    private static final class MockUserNameToIdResolver implements UserNameToIdResolver {
        private final Set<NameAndId> savedIds = new HashSet<>();

        @Override
        public void add(NameAndId nameAndId) {
            this.savedIds.add(nameAndId);
        }

        @Override
        public Optional<NameAndId> get(String name) {
            return this.savedIds.stream()
                    .filter(id -> id.name().equals(name))
                    .findFirst()
                    .or(() -> Optional.of(NameAndId.createOffline(name)));
        }

        @Override
        public Optional<NameAndId> get(UUID uuid) {
            return this.savedIds.stream().filter(id -> id.id().equals(uuid)).findFirst();
        }

        @Override
        public void resolveOfflineUsers(boolean offlineMode) {
        }

        @Override
        public void save() {
        }
    }
#endif
}
