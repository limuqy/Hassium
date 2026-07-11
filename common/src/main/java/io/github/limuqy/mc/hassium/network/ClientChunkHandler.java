package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
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
     * 处理接收到的压缩区块数据
     * <p>
     * 解码在调用者线程（主线程），ZSTD 解压提交到后台线程池，
     * 解压完成后通过 MainThreadDispatcher 回到主线程应用区块数据。
     * <p>
     * 任务标记为 SAFE_TO_CANCEL，登出时如果解压尚未完成可安全取消。
     */
    public static void handleCompressedChunk(byte[] compressedData) {
        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Received compressed chunk data ({} bytes)", compressedData.length);

        // 解码（轻量操作，无 I/O）
        ChunkCompressionHandler.CompressedChunkData compressed =
            ChunkCompressionHandler.CompressedChunkData.decode(compressedData);

        if (compressed == null) {
            DebugLogger.error("[HANDLE_COMPRESSED] Failed to decode compressed chunk data");
            return;
        }

        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decoded chunk [{}, {}] (originalSize={}, algorithm={}, compressedSize={})",
                compressed.chunkX, compressed.chunkZ, compressed.originalSize, compressed.algorithm, compressed.compressedData.length);

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

                // 立即缓存到本地（后台线程，含压缩+磁盘写入）
                persistDecompressedChunk(chunkX, chunkZ, decompressed);

                // 回到主线程应用区块数据
                MainThreadDispatcher.execute(() -> {
                    DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Applying chunk [{}, {}] to world", chunkX, chunkZ);
                    applyChunkData(chunkX, chunkZ, decompressed, false);
                    DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Successfully applied chunk [{}, {}] from server", chunkX, chunkZ);
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

            // 立即缓存到本地
            persistDecompressedChunk(compressed.chunkX, compressed.chunkZ, decompressed);

            applyChunkData(compressed.chunkX, compressed.chunkZ, decompressed, false);
            Constants.LOG.debug("Hassium: Applied chunk [{}, {}] from server",
                compressed.chunkX, compressed.chunkZ);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Error in fallback decompress for chunk [{}, {}]",
                compressed.chunkX, compressed.chunkZ, e);
        }
    }

    /**
     * 将解压后的区块数据应用到客户端世界
     *
     * @param chunkX     区块X坐标
     * @param chunkZ     区块Z坐标
     * @param chunkData  解压后的NBT字节数据
     * @param renderOnly true=仅渲染不参与逻辑tick
     */
    public static void applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly) {
        DebugLogger.info(LogType.CHUNK_APPLY, "[APPLY_CHUNK] Applying chunk [{}, {}] (dataSize={}, renderOnly={})",
                chunkX, chunkZ, chunkData.length, renderOnly);

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level == null) {
            DebugLogger.error("[APPLY_CHUNK] Cannot apply chunk [{}, {}], client level is null", chunkX, chunkZ);
            return;
        }

        try {
            // chunkData 是 FriendlyByteBuf 格式，需要通过 Minecraft 的数据包处理器来应用
            // 创建 FriendlyByteBuf 来读取数据，确保从位置 0 开始读取
            io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.wrappedBuffer(chunkData);
            nettyBuf.readerIndex(0);  // 确保从头开始读取
            net.minecraft.network.FriendlyByteBuf friendlyBuf = new net.minecraft.network.FriendlyByteBuf(nettyBuf);

            // 通过平台抽象注入区块（需要传入 FriendlyByteBuf）
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            Services.getClientChunkApplier().applyToLevelFromByteBuf(level, pos, friendlyBuf, renderOnly);

            DebugLogger.info(LogType.CHUNK_APPLY, "[APPLY_CHUNK] Successfully applied chunk [{}, {}] to client world", chunkX, chunkZ);

            // 区块就绪：发送延后的 BE 请求 + 冲刷暂存 BE
            ClientMetadataHandler.onChunkApplied(pos);

        } catch (Exception e) {
            DebugLogger.error("[APPLY_CHUNK] Failed to apply chunk data for [{}, {}]", e, chunkX, chunkZ);
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
        return clientStorage.persist(pos, data, contentHash, sectionHashes);
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

        try {
            applyChunkData(pos.x, pos.z, chunkData, renderOnly);
            Constants.LOG.debug("Hassium: Applied chunk {} from cache (renderOnly={})", pos, renderOnly);
            return true;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk {} from cache", pos, e);
            return false;
        }
    }

    /**
     * 从缓存加载区块数据（仅加载和解压，不应用到世界）
     * <p>
     * 可以在后台线程调用，避免阻塞主线程。
     *
     * @param pos 区块坐标
     * @return 解压后的区块数据，如果不存在返回 null
     */
    public static byte[] loadChunkDataFromCache(ChunkPos pos) {
        if (clientStorage == null) {
            return null;
        }

        try {
            byte[] chunkData = clientStorage.loadAndDecompress(pos);
            if (chunkData != null) {
                Constants.LOG.debug("Hassium: Loaded chunk {} from cache ({} bytes)", pos, chunkData.length);
            }
            return chunkData;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to load chunk {} from cache", pos, e);
            return null;
        }
    }

    /**
     * 将解压后的区块数据持久化到本地缓存（后台线程安全）
     * <p>
     * 使用从元数据包暂存的 contentHash。
     * 同时计算 section 哈希并持久化，用于阶段二比对。
     */
    private static void persistDecompressedChunk(int chunkX, int chunkZ, byte[] data) {
        if (clientStorage == null) {
            return;
        }

        try {
            long contentHash = consumePendingContentHash(chunkX, chunkZ);

            // 计算 section 哈希（从解压后的 packet 字节）
            long[] sectionHashes = null;
            try {
                sectionHashes = computeSectionHashesFromData(data);
                if (sectionHashes != null) {
                    storePendingSectionHashes(chunkX, chunkZ, sectionHashes);
                }
            } catch (Exception e) {
                Constants.LOG.debug("Hassium: Failed to compute section hashes for chunk [{}, {}]", chunkX, chunkZ, e);
            }

            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            boolean saved = persistToCache(pos, data, contentHash, sectionHashes);
            if (saved) {
                Constants.LOG.debug("Hassium: Cached chunk [{}, {}] from server data (hash={})",
                        chunkX, chunkZ, Long.toHexString(contentHash));
            }
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to persist chunk [{}, {}] to cache", chunkX, chunkZ, e);
        }
    }

    /**
     * 从解压后的 packet 字节计算 section 哈希。
     * <p>
     * 在后台线程调用，用于持久化 section 哈希到缓存。
     * 使用与服务端相同的 section 序列化格式，确保哈希一致性。
     *
     * @param data 解压后的 packet 字节
     * @return section 哈希数组（索引 = sectionIndex），失败返回 null
     */
    private static long[] computeSectionHashesFromData(byte[] data) {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(data);
        try {
            buf.readerIndex(0);
            net.minecraft.network.FriendlyByteBuf friendlyBuf = new net.minecraft.network.FriendlyByteBuf(buf);

            // 读取完整的 chunk packet 数据
            // packet 格式：chunkX(4) + chunkZ(4) + chunkData + lightData
            // chunkData 格式：heightmapsNBT + sectionsBytes(varint) + sections + blockEntities
            int chunkX = friendlyBuf.readInt();
            int chunkZ = friendlyBuf.readInt();

            // 读取 chunk data 部分（与 ClientboundLevelChunkPacketData 相同格式）
            // 跳过 heightmaps NBT
            net.minecraft.nbt.Tag heightmaps = friendlyBuf.readNbt();

            // 读取 sections 数据大小（varint）
            int sectionsSize = friendlyBuf.readVarInt();
            if (sectionsSize <= 0 || sectionsSize > friendlyBuf.readableBytes()) {
                return null;
            }

            // 读取 sections 字节
            byte[] sectionsBytes = new byte[sectionsSize];
            friendlyBuf.readBytes(sectionsBytes);

            // 使用 ChunkContentHashUtil 计算 per-section 哈希
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            int sectionCount = mc.level.getSectionsCount();
            var biomeRegistry = mc.level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            java.util.Map<Integer, Long> sectionHashes =
                    ChunkContentHashUtil.computeSectionHashesFromBytes(sectionsBytes, sectionCount, biomeRegistry);

            return ChunkContentHashUtil.sectionHashesToArray(sectionHashes);
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to parse packet for section hashes", e);
            return null;
        } finally {
            buf.release();
        }
    }
}
