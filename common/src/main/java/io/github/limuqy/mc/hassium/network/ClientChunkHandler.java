package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
import io.github.limuqy.mc.hassium.cache.client.ChunkOutOfViewException;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 客户端区块处理器门面（Phase 0 隔离重构后）。
 * <p>
 * 全部可变状态（storage、pending hash 表、apply 重入标志）已迁入
 * {@link ClientChunkPipeline} 实例字段；本类保留既有 public static 签名
 * 供调用方零改动转发，处理逻辑经 {@link ClientChunkPipeline#getInstance()} 访问状态。
 * Phase 4 完成后删除本门面，调用方直指 pipeline 实例。
 */
public class ClientChunkHandler {

    /**
     * 初始化客户端缓存存储（转发 pipeline）
     *
     * @param gameDir     游戏目录
     * @param serverId    服务器标识（如 server_127.0.0.1_25565）
     * @param dimension   维度标识（如 minecraft:overworld）
     */
    public static void initStorage(Path gameDir, String serverId, String dimension) {
        ClientChunkPipeline.getInstance().initStorage(gameDir, serverId, dimension);
    }

    /**
     * 获取客户端缓存存储实例（转发 pipeline）
     */
    public static ClientHassiumStorage getClientStorage() {
        return ClientChunkPipeline.getInstance().getClientStorage();
    }

    /**
     * 重置客户端缓存存储（断开连接时调用，转发 pipeline）
     */
    public static void resetStorage() {
        ClientChunkPipeline.getInstance().resetStorage();
    }

    /**
     * 暂存 contentHash，供后续收到区块数据时使用（转发 pipeline）
     */
    public static void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        ClientChunkPipeline.getInstance().storePendingContentHash(chunkX, chunkZ, contentHash);
    }

    /**
     * 全量推送异步入库：packet → NBT → CacheSaveQueue（不堵主线程）。
     * <p>
     * 初始多为 is_light_on=0；光照回写后标净。level 未就绪时保持 dirty，留给卸载/断连安全网。
     */
    private static void scheduleAsyncCacheIngest(int chunkX, int chunkZ, byte[] packetBytes) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker.markDirty(pos);

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        Runnable ingest = () -> {
            try {
                if (ClientChunkPipeline.getInstance().getClientStorage() == null) {
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                ClientLevel level = mc.level;
                if (level == null) {
                    DebugLogger.debug(LogType.COMPRESSION,
                            "[CACHE_INGEST] Level not ready for [{}, {}], keep dirty for unload", chunkX, chunkZ);
                    return;
                }
                CompoundTag nbt = ChunkDiskCodec.packetBytesToNbt(
                        packetBytes, level.registryAccess(), level.getSectionsCount());
                if (nbt == null) {
                    DebugLogger.debug(LogType.COMPRESSION,
                            "[CACHE_INGEST] packetBytesToNbt failed for [{}, {}]", chunkX, chunkZ);
                    return;
                }
                byte[] nbtBytes = ChunkDiskCodec.nbtToBytes(nbt);
                if (nbtBytes == null) {
                    return;
                }
                ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
                long contentHash = pipeline.peekPendingContentHash(chunkX, chunkZ);
                long[] sectionHashes = pipeline.peekPendingSectionHashes(chunkX, chunkZ);
                if (contentHash == 0L && sectionHashes != null && sectionHashes.length > 0) {
                    contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashesFromArray(sectionHashes);
                }
                if (contentHash == 0L) {
                    // 兜底：chunkHash 包未先于 chunk 到达（或已过期）时，从服务端数据重算，
                    // 避免 contentHash=0 被 MetadataTable 翻转成 1 → 次回 compare 全 MISMATCH。
                    // 与 SectionDelta 的 applyDeltaEntry 重算路径一致（方块数据同源，hash 相同）。
                    int sectionCount = level.getSectionsCount();
                    long[] nbtSectionHashes = io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec
                            .computeSectionHashesFromNbt(nbt, sectionCount, level.registryAccess());
                    sectionHashes = nbtSectionHashes;
                    contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashesFromArray(nbtSectionHashes);
                }
                io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue.getInstance()
                        .enqueueSerialized(pos, nbtBytes, contentHash, sectionHashes);
                pipeline.consumePendingContentHash(chunkX, chunkZ);
                pipeline.consumePendingSectionHashes(chunkX, chunkZ);
            } catch (Exception e) {
                DebugLogger.debug(LogType.COMPRESSION,
                        "[CACHE_INGEST] Failed for [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            }
        };

        if (Minecraft.getInstance().isSameThread()) {
            if (executor != null && executor.isRunning()) {
                executor.submit(ingest, TaskCategory.SAFE_TO_CANCEL);
            } else {
                ingest.run();
            }
        } else {
            // 已在解压后台线程，直接入库避免再排队
            ingest.run();
        }
    }

    /**
     * 处理接收到的压缩区块数据
     * <p>
     * 解码在调用者线程（主线程），ZSTD 解压提交到后台线程池，
     * 解压完成后通过 MainThreadDispatcher 回到主线程应用区块数据。
     * <p>
     * 任务标记为 SAFE_TO_CANCEL，登出时如果解压尚未完成可安全取消。
     */
    public static void handleCompressedChunk(byte[] compressedData) {
        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Received compressed chunk data ({} bytes)",
                compressedData == null ? -1 : compressedData.length);

        if (compressedData == null || compressedData.length == 0) {
            Constants.LOG.error("[HANDLE_COMPRESSED] Empty compressed payload, ignoring");
            return;
        }

        // 解码（轻量操作，无 I/O）
        ChunkCompressionHandler.CompressedChunkData compressed =
            ChunkCompressionHandler.CompressedChunkData.decode(compressedData);

        if (compressed == null) {
            Constants.LOG.error("[HANDLE_COMPRESSED] Failed to decode compressed chunk data");
            DebugLogger.error("[HANDLE_COMPRESSED] Failed to decode compressed chunk data");
            return;
        }

        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decoded chunk [{}, {}] ({} -> {} bytes, algo={})",
                compressed.chunkX, compressed.chunkZ, compressed.compressedData.length,
                compressed.originalSize, compressed.algorithm);

        // 清除全量请求超时登记（数据已到达，服务端丢弃/积压兜底结束）
        ClientMetadataHandler.onChunkDataReceived(compressed.chunkX, compressed.chunkZ);

        // 记录收到压缩区块数据
        NetworkStats.recordChunkReceived(VanillaZlibEstimator.estimate(compressed.originalSize));

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null) {
            DebugLogger.warn(LogType.COMPRESSION, "[HANDLE_COMPRESSED] HassiumTaskExecutor not initialized, using sync fallback");
            // 执行器未初始化：回退到主线程同步解压
            decompressAndApply(compressed);
            return;
        }

        final int chunkX = compressed.chunkX;
        final int chunkZ = compressed.chunkZ;
        final byte[] compData = compressed.compressedData;
        final String algorithm = compressed.algorithm;

        // 提交 ZSTD 解压到后台线程池
        executor.submit(() -> {
            try {
                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressing chunk [{}, {}] in background", chunkX, chunkZ);
                // 解压区块数据（后台线程，不阻塞主线程）
                byte[] decompressed = ChunkCompressionHandler.decompressChunkDataFromRaw(chunkX, chunkZ, compData, algorithm);
                if (decompressed == null) {
                    DebugLogger.error("[HANDLE_COMPRESSED] Failed to decompress chunk data for [{}, {}]", chunkX, chunkZ);
                    return;
                }

                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressed chunk [{}, {}] ({} -> {} bytes)",
                    chunkX, chunkZ, compData.length, decompressed.length);

                // 推送即入库：后台转 NBT 并投入 CacheSaveQueue（与主线程 apply 并行摊销写盘）
                scheduleAsyncCacheIngest(chunkX, chunkZ, decompressed);

                // 回主线程应用区块（距离优先级依赖 updatePlayerPosition）
                MainThreadDispatcher.execute(() -> {
                    DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Applying chunk [{}, {}] to world", chunkX, chunkZ);
                    if (applyChunkData(chunkX, chunkZ, decompressed, false)) {
                        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Successfully applied chunk [{}, {}] from server", chunkX, chunkZ);
                    } else {
                        DebugLogger.warn(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Failed to apply chunk [{}, {}] from server", chunkX, chunkZ);
                    }
                }, new ChunkPos(chunkX, chunkZ), TaskCategory.SAFE_TO_CANCEL);

            } catch (Exception e) {
                DebugLogger.error("[HANDLE_COMPRESSED] Error in background decompress for chunk [{}, {}]", e, chunkX, chunkZ);
            }
        }, TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 同步解压并应用（回退路径，当 HassiumTaskExecutor 未初始化时使用）
     */
    private static void decompressAndApply(ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            byte[] decompressed = ChunkCompressionHandler.decompressChunkData(compressed);
            if (decompressed == null) {
                Constants.LOG.error("Hassium: Failed to decompress chunk data for [{}, {}]",
                    compressed.chunkX, compressed.chunkZ);
                return;
            }

            Constants.LOG.debug("Hassium: Decompressed chunk [{}, {}] on main thread (fallback), size: {} -> {} bytes",
                compressed.chunkX, compressed.chunkZ, compressed.compressedData.length, decompressed.length);

            scheduleAsyncCacheIngest(compressed.chunkX, compressed.chunkZ, decompressed);

            // 应用区块
            boolean applied = applyChunkData(compressed.chunkX, compressed.chunkZ, decompressed, false);
            if (applied) {
                Constants.LOG.debug("Hassium: Applied chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            } else {
                Constants.LOG.warn("Hassium: Failed to apply chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Error in fallback decompress for chunk [{}, {}]",
                compressed.chunkX, compressed.chunkZ, e);
        }
    }

    /**
     * 将解压后的区块数据应用到客户端世界
     * <p>
     * {@code chunkData} 支持两种格式：
     * <ul>
     *   <li>NBT 字节（含 {@code "HBT1"} magic 前缀）：{@link ChunkDiskCodec#bytesToNbt} →
     *       {@link ChunkDiskCodec#nbtToPacketBytes} 重组 packet → {@code applyToLevelFromByteBuf}</li>
     *   <li>旧 packet 字节（无 magic 前缀，向后兼容）：直接 {@code applyToLevelFromByteBuf}</li>
     * </ul>
     *
     * @param chunkX     区块X坐标
     * @param chunkZ     区块Z坐标
     * @param chunkData  NBT 字节或 packet 字节
     * @param renderOnly true=仅渲染不参与逻辑tick
     */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly) {
        // 仍是 HBT1 NBT 时自动识别 is_light_on；已是 packet 字节则视为无标志（由调用方显式传入）
        CompoundTag nbt = ChunkDiskCodec.bytesToNbt(chunkData);
        if (nbt != null) {
            return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, nbt,
                    ChunkDiskCodec.isLightOn(nbt));
        }
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, null, false);
    }

    /**
     * 将解压后的区块数据应用到客户端世界（接受预构建的 NBT 以避免光照回写时重复读盘）。
     *
     * @param chunkX     区块X坐标
     * @param chunkZ     区块Z坐标
     * @param chunkData  NBT 字节或 packet 字节
     * @param renderOnly true=仅渲染不参与逻辑tick
     * @param cachedNbt  内存中的缓存 NBT（可为 null，null 时回退磁盘读取）
     */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, CompoundTag cachedNbt) {
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, cachedNbt,
                ChunkDiskCodec.isLightOn(cachedNbt));
    }

    /**
     * 缓存队列 apply：显式传入是否已含光照，避免 packet 字节路径无法再读 {@code is_light_on}。
     */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, CompoundTag cachedNbt,
                                         boolean hasCachedLight) {
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, cachedNbt, hasCachedLight);
    }

    /**
     * Hassium 内部 apply 进行中标志已迁 {@link ClientChunkPipeline#isApplyInProgress()}。
     */

    private static boolean applyChunkDataInternal(int chunkX, int chunkZ, byte[] chunkData,
                                                  boolean renderOnly, CompoundTag cachedNbt,
                                                  boolean hasCachedLight) {
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[APPLY_CHUNK] Applying chunk [{}, {}] (dataSize={}, renderOnly={}, hasCachedLight={})",
                chunkX, chunkZ, chunkData.length, renderOnly, hasCachedLight);
        long applyStartNs = System.nanoTime();

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level == null) {
            DebugLogger.error("[APPLY_CHUNK] Cannot apply chunk [{}, {}], client level is null", chunkX, chunkZ);
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
            }
            return false;
        }

        try {
            // 权威数据到货时玩家已移出服务端视距（apply 决策点主动分流）：
            // 数据是最新服务器数据，不丢弃——若仍在 OVD 环带则原地转 renderOnly 渲染，
            // 省掉「skip → OVD 重扫 → 磁盘 reload → renderOnly apply」的绕路与虚空窗口；
            // OVD 未开或已出环带（>clientVD）则走原路径：平台 applier 的 inRange 判定
            // 丢弃（数据已推送即入库，等于直接入客户端缓存）。判定与 apply 之间仍有
            // 竞态窗口，由下方 ChunkOutOfViewException 兜底。
            if (!renderOnly && ViewDistanceExtensionService.getInstance().shouldKeepAsRenderOnly(pos)) {
                return applyChunkDataInternal(chunkX, chunkZ, chunkData, true, cachedNbt, hasCachedLight);
            }

            // 超视渲染 / 缓存 apply 前先保证 Storage 半径 ≥ clientVD（防 server 缩半径窗口）
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
            }

            byte[] packetBytes = ChunkDiskCodec.maybeNbtToPacketBytes(
                    chunkData, level.registryAccess(), level.getSectionsCount());

            // chunkData 是 FriendlyByteBuf 格式，需要通过 Minecraft 的数据包处理器来应用
            // 创建 FriendlyByteBuf 来读取数据，确保从位置 0 开始读取
            io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.wrappedBuffer(packetBytes);
            nettyBuf.readerIndex(0);  // 确保从头开始读取
            net.minecraft.network.FriendlyByteBuf friendlyBuf = new net.minecraft.network.FriendlyByteBuf(nettyBuf);

            // 通过平台抽象注入区块（需要传入 FriendlyByteBuf）
            // hassiumApplyInProgress：本调用在 Hassium 主线程预算内，MixinVanillaChunkApplyBudget
            // 不得再次拦截（否则入队 dispatcher 后 hasChunk 校验立即失败 → 假失败/重请求风暴）。
            ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
            pipeline.setApplyInProgress(true);
            try {
                Services.getClientChunkApplier().applyToLevelFromByteBuf(level, pos, friendlyBuf, renderOnly);
            } finally {
                pipeline.setApplyInProgress(false);
            }

            DebugLogger.info(LogType.CHUNK_APPLY, "[APPLY_CHUNK] Successfully applied chunk [{}, {}] to client world in {} ms",
                    chunkX, chunkZ, String.format("%.2f", (System.nanoTime() - applyStartNs) / 1_000_000.0));

            // 加载活跃：续期 JoinBoost 窗口（含 hasLight 无重算块，重算块在 applyLightEngineNow 续期）。
            // 仅权威块续期：renderOnly（OVD）不续期，避免超视渲染灌队把 JoinBoost 窗口永久续期
            // （高预算被 OVD 吃满、VDES 的 JoinBoost 门控失效）。
            if (!renderOnly) {
                io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.noteChunkApplyActivity();
            }

            // 区块就绪：发送延后的 BE 请求 + 冲刷暂存 BE
            // renderOnly（超视渲染）不向服务器请求 BE，避免视距外流量
            // 空光照重算由 MixinLightRecompute 在 handleLevelChunkWithLight TAIL 完成，此处勿重复调用
            if (!renderOnly) {
                if (hasCachedLight) {
                    NetworkStats.recordLightCacheHit(getLightBytesPerChunk(level));
                }
                ClientMetadataHandler.onChunkApplied(pos);
            } else if (hasCachedLight) {
                // 缓存已含光照：packet 已写入真实 LightData，Mixin 跳过重算
                NetworkStats.recordLightCacheHit(getLightBytesPerChunk(level));
                ViewDistanceExtensionService.getInstance().onRenderOnlyApplied(pos);
            } else {
                // 重算在帧尾统一执行（官方：ClientLightBufferQueue 消费；并行：引擎完成回调
                // updateCacheWithLightData）——重算刚完成的光照未收敛，不得立即写盘；只标脏，
                // 磁盘光照由卸载/断连 dump 从引擎收敛态捕获。此处再登记一次保证任何路径都进入
                // dirty 集合（重算完成点与 apply 分属不同 tick 时依赖此兜底）。
                io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker.markDirty(pos);
                ViewDistanceExtensionService.getInstance().onRenderOnlyApplied(pos);
            }
            return true;

        } catch (ChunkOutOfViewException e) {
            // 预期竞态：异步解压/主线程预算/视距缩窗导致 apply 时已 out of range
            DebugLogger.debug(LogType.CHUNK_APPLY,
                    "[APPLY_CHUNK] Out of view range, skipped [{}, {}]", chunkX, chunkZ);
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(new ChunkPos(chunkX, chunkZ));
            }
            return false;
        } catch (Exception e) {
            DebugLogger.error("[APPLY_CHUNK] Failed to apply chunk data for [{}, {}]", e, chunkX, chunkZ);
            // renderOnly：登记 miss 退避重试
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(new ChunkPos(chunkX, chunkZ));
            }
            return false;
        }
    }

    /**
     * 光照缓存等价值字节估算（与 {@code ClientMetadataHandler.ESTIMATED_CHUNK_BYTES} 同口径，16KB/chunk）。
     * 见 {@link NetworkStats#ESTIMATED_LIGHT_BYTES} 注释。
     */
    private static long getLightBytesPerChunk(ClientLevel level) {
        // level 参数保留以便未来按 sectionsCount 动态估算；当前与区块口径一致用常量
        return NetworkStats.ESTIMATED_LIGHT_BYTES;
    }

    /**
     * 批量加载缓存区块（region 级批量读，语义与单块路径一致）。
     * <p>
     * 同 region 的块一次锁持有顺序读，锁外解压；无效 NBT 校验与删盘行为同
     * {@link #loadChunkDataFromCache(ChunkPos)}。
     *
     * @param positions 区块坐标列表（可跨 region，内部按 region 分组）
     * @return 校验通过的区块坐标 → HBT1 字节
     */
    public static Map<ChunkPos, byte[]> loadChunkDataBatchFromCache(List<ChunkPos> positions) {
        ClientHassiumStorage storage = ClientChunkPipeline.getInstance().getClientStorage();
        if (storage == null) {
            return Collections.emptyMap();
        }
        Map<ChunkPos, byte[]> loaded = storage.loadRegionBatch(positions);
        if (loaded.isEmpty()) {
            return loaded;
        }
        Iterator<Map.Entry<ChunkPos, byte[]>> it = loaded.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, byte[]> e = it.next();
            byte[] chunkData = e.getValue();
            if (ChunkDiskCodec.isValidChunkNbt(chunkData)) {
                continue;
            }
            if (ChunkDiskCodec.stripMagicPrefix(chunkData) == null) {
                Constants.LOG.debug("Hassium: Cache invalid (non-NBT) for chunk {}, removing", e.getKey());
                try {
                    storage.remove(e.getKey());
                } catch (Throwable t) {
                    Constants.LOG.debug("Hassium: Failed to remove invalid cache for {}", e.getKey(), t);
                }
            } else {
                Constants.LOG.warn("Hassium: Cache has HBT1 magic but invalid chunk NBT for {}, keeping on disk",
                        e.getKey());
            }
            NetworkStats.recordCacheMiss();
            it.remove();
        }
        return loaded;
    }

    /**
     * 从缓存加载区块数据（仅加载和解压，不应用到世界）
     * <p>
     * 可以在后台线程调用，避免阻塞主线程。
     * <p>
     * 旧 packet 缓存识别：解压后若不是合法 NBT（无 magic 前缀），删块并返回 null，
     * 让 {@code ClientCacheLoadQueue} 走全量请求。
     *
     * @param pos 区块坐标
     * @return NBT 字节（含 magic 前缀）；不存在或非法返回 null
     */
    public static byte[] loadChunkDataFromCache(ChunkPos pos) {
        ClientHassiumStorage storage = ClientChunkPipeline.getInstance().getClientStorage();
        if (storage == null) {
            return null;
        }

        try {
            byte[] chunkData = storage.loadAndDecompress(pos);
            if (chunkData == null) {
                return null;
            }
            // 校验 NBT 格式：仅旧 packet（无 HBT1 magic）才删盘；HBT1 结构异常保留以免误删
            if (!ChunkDiskCodec.isValidChunkNbt(chunkData)) {
                if (ChunkDiskCodec.stripMagicPrefix(chunkData) == null) {
                    Constants.LOG.debug("Hassium: Cache invalid (non-NBT) for chunk {}, removing", pos);
                    try {
                        storage.remove(pos);
                    } catch (Throwable t) {
                        Constants.LOG.debug("Hassium: Failed to remove invalid cache for {}", pos, t);
                    }
                } else {
                    Constants.LOG.warn("Hassium: Cache has HBT1 magic but invalid chunk NBT for {}, keeping on disk",
                            pos);
                }
                NetworkStats.recordCacheMiss();
                return null;
            }
            Constants.LOG.debug("Hassium: Loaded chunk {} from cache ({} NBT bytes)", pos, chunkData.length);
            return chunkData;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to load chunk {} from cache", pos, e);
            return null;
        }
    }

    /**
     * 从缓存加载区块 NBT（后台线程安全）。
     *
     * @param pos 区块坐标
     * @return chunk NBT；不存在或非法返回 null
     */
    public static net.minecraft.nbt.CompoundTag loadChunkNbtFromCache(ChunkPos pos) {
        byte[] bytes = loadChunkDataFromCache(pos);
        return bytes != null ? ChunkDiskCodec.bytesToNbt(bytes) : null;
    }

}
