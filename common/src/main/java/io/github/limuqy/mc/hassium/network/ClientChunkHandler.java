package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
import io.github.limuqy.mc.hassium.cache.client.ClientLightRecomputeService;
import io.github.limuqy.mc.hassium.cache.client.ChunkOutOfViewException;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端区块处理器，负责将解压后的区块数据应用到客户端世界
 */
public class ClientChunkHandler {

    private static final int DEFAULT_COMPRESSION_LEVEL = 9;
    private static volatile ClientHassiumStorage clientStorage;

    /** 元数据 contentHash 暂存：chunkPos -> (hash, timestamp)，用于收到数据后写入缓存 */
    private static final Map<Long, PendingHash> pendingContentHashes = new ConcurrentHashMap<>();

    /** section 哈希暂存：chunkPos -> (sectionHashes, timestamp)，用于 persist 时一起写入 */
    private static final Map<Long, PendingSectionHashes> pendingSectionHashes = new ConcurrentHashMap<>();

    /** 条目过期时间（30秒） */
    private static final long PENDING_HASH_TTL_MS = 30_000;

    /** 上次清理时间 */
    private static volatile long lastPendingCleanupTime = 0;

    /** 清理间隔（5秒） */
    private static final long PENDING_CLEANUP_INTERVAL_MS = 5_000;

    private record PendingHash(long hash, long timestamp) {}
    private record PendingSectionHashes(long[] hashes, long timestamp) {}

    /**
     * 初始化客户端缓存存储
     *
     * @param gameDir     游戏目录
     * @param serverId    服务器标识（如 server_127.0.0.1_25565）
     * @param dimension   维度标识（如 minecraft:overworld）
     */
    public static void initStorage(Path gameDir, String serverId, String dimension) {
        // 维度目录名：将冒号替换为下划线
        String dimDir = dimension.replaceAll("[^a-zA-Z0-9._-]", "_");
        clientStorage = new ClientHassiumStorage(gameDir, serverId, dimDir);
        Constants.LOG.info("Hassium: Initialized client chunk cache for server {} dimension {}", serverId, dimension);
    }

    /**
     * 获取客户端缓存存储实例
     */
    public static ClientHassiumStorage getClientStorage() {
        return clientStorage;
    }

    /**
     * 重置客户端缓存存储（断开连接时调用）
     */
    public static void resetStorage() {
        clientStorage = null;
        pendingContentHashes.clear();
        pendingSectionHashes.clear();
        io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker.clearAll();
    }

    /**
     * 暂存 contentHash，供后续收到区块数据时使用
     */
    public static void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        evictExpiredEntries();
        pendingContentHashes.put(chunkPosKey(chunkX, chunkZ), new PendingHash(contentHash, System.currentTimeMillis()));
    }

    /**
     * 取出并移除暂存的 contentHash
     */
    private static long consumePendingContentHash(int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.remove(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /**
     * 窥视暂存 contentHash（不移除），供异步入库与 apply 共用。
     */
    private static long peekPendingContentHash(int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.get(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /**
     * 暂存 section 哈希，供后续 persist 时一起写入缓存
     */
    public static void storePendingSectionHashes(int chunkX, int chunkZ, long[] sectionHashes) {
        evictExpiredEntries();
        pendingSectionHashes.put(chunkPosKey(chunkX, chunkZ),
                new PendingSectionHashes(sectionHashes, System.currentTimeMillis()));
    }

    /**
     * 取出并移除暂存的 section 哈希
     */
    private static long[] consumePendingSectionHashes(int chunkX, int chunkZ) {
        PendingSectionHashes entry = pendingSectionHashes.remove(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    private static long[] peekPendingSectionHashes(int chunkX, int chunkZ) {
        PendingSectionHashes entry = pendingSectionHashes.get(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    /**
     * 懒清理过期条目（定期调用，避免无限增长）
     */
    private static void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        if (now - lastPendingCleanupTime < PENDING_CLEANUP_INTERVAL_MS) {
            return;
        }
        lastPendingCleanupTime = now;
        pendingContentHashes.entrySet().removeIf(e -> now - e.getValue().timestamp() > PENDING_HASH_TTL_MS);
        pendingSectionHashes.entrySet().removeIf(e -> now - e.getValue().timestamp() > PENDING_HASH_TTL_MS);
    }

    private static long chunkPosKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
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
                if (clientStorage == null) {
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
                long contentHash = peekPendingContentHash(chunkX, chunkZ);
                long[] sectionHashes = peekPendingSectionHashes(chunkX, chunkZ);
                if (contentHash == 0L && sectionHashes != null && sectionHashes.length > 0) {
                    contentHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .combineSectionHashesFromArray(sectionHashes);
                }
                io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue.getInstance()
                        .enqueueSerialized(pos, nbtBytes, contentHash, sectionHashes);
                consumePendingContentHash(chunkX, chunkZ);
                consumePendingSectionHashes(chunkX, chunkZ);
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

        // 记录收到压缩区块数据
        NetworkStats.recordChunkReceived(compressed.originalSize, compressed.compressedData.length);

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

    private static boolean applyChunkDataInternal(int chunkX, int chunkZ, byte[] chunkData,
                                                  boolean renderOnly, CompoundTag cachedNbt,
                                                  boolean hasCachedLight) {
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[APPLY_CHUNK] Applying chunk [{}, {}] (dataSize={}, renderOnly={}, hasCachedLight={})",
                chunkX, chunkZ, chunkData.length, renderOnly, hasCachedLight);

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
            Services.getClientChunkApplier().applyToLevelFromByteBuf(level, pos, friendlyBuf, renderOnly);

            DebugLogger.info(LogType.CHUNK_APPLY, "[APPLY_CHUNK] Successfully applied chunk [{}, {}] to client world", chunkX, chunkZ);

            // 区块就绪：发送延后的 BE 请求 + 冲刷暂存 BE
            // renderOnly（超视渲染）不向服务器请求 BE，避免视距外流量
            // 空光照重算由 MixinLightRecompute 在 handleLevelChunkWithLight TAIL 完成，此处勿重复调用
            if (!renderOnly) {
                if (hasCachedLight) {
                    NetworkStats.recordLightCacheHit();
                }
                ClientMetadataHandler.onChunkApplied(pos);
            } else if (hasCachedLight) {
                // 缓存已含光照：packet 已写入真实 LightData，Mixin 跳过重算
                NetworkStats.recordLightCacheHit();
                ViewDistanceExtensionService.getInstance().onRenderOnlyApplied(pos);
            } else {
                // Mixin 已同步重算；用内存 NBT 补回写，避免仅依赖读盘
                if (cachedNbt != null) {
                    ClientLightRecomputeService.updateCacheWithLightNbt(pos, cachedNbt);
                }
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
     * 将区块数据压缩并保存到缓存
     *
     * @param pos           区块坐标
     * @param data          区块数据（FriendlyByteBuf 格式的 packet 数据）
     * @param contentHash   内容哈希
     * @param sectionHashes per-section 哈希数组（可为 null）
     * @return 是否成功保存
     */
    public static boolean persistToCache(ChunkPos pos, byte[] data, long contentHash, long[] sectionHashes) {
        if (clientStorage == null) {
            Constants.LOG.warn("Hassium: Client storage not initialized");
            return false;
        }
        boolean ok = clientStorage.persist(pos, data, contentHash, sectionHashes);
        if (ok) {
            // contentHash 不含光照：仅 is_light_on=1 才标净；否则等光照回写 / 卸载快照
            CompoundTag nbt = ChunkDiskCodec.bytesToNbt(data);
            if (ChunkDiskCodec.isLightOn(nbt)) {
                io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker.clear(pos);
            } else {
                io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker.markDirty(pos);
            }
        }
        return ok;
    }

    /**
     * 从缓存加载区块并应用到世界
     *
     * @param pos        区块坐标
     * @param renderOnly true=仅渲染不参与逻辑tick
     * @return 是否成功加载
     */
    public static boolean loadFromCacheAndApply(ChunkPos pos, boolean renderOnly) {
        byte[] chunkData = loadChunkDataFromCache(pos);
        if (chunkData == null) {
            return false;
        }

        boolean applied = applyChunkData(pos.x, pos.z, chunkData, renderOnly);
        if (applied) {
            Constants.LOG.debug("Hassium: Applied chunk {} from cache (renderOnly={})", pos, renderOnly);
        }
        return applied;
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
        if (clientStorage == null) {
            return null;
        }

        try {
            byte[] chunkData = clientStorage.loadAndDecompress(pos);
            if (chunkData == null) {
                return null;
            }
            // 校验 NBT 格式：仅旧 packet（无 HBT1 magic）才删盘；HBT1 结构异常保留以免误删
            if (!ChunkDiskCodec.isValidChunkNbt(chunkData)) {
                if (ChunkDiskCodec.stripMagicPrefix(chunkData) == null) {
                    Constants.LOG.debug("Hassium: Cache invalid (non-NBT) for chunk {}, removing", pos);
                    try {
                        clientStorage.remove(pos);
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
