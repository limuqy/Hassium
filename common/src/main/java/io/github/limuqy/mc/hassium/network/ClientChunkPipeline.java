package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DimensionKey;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端区块摄入管线的状态容器（Phase 0 隔离重构产物）。
 * <p>
 * 原 {@link ClientChunkHandler} 的全 static 状态（storage、pending hash 表、apply 重入标志）
 * 收拢为本类实例字段，由 {@link #getInstance()} 单例访问；{@link ClientChunkHandler}
 * 退化为兼容门面（Phase 4 后删除）。
 * <p>
 * 生命周期：{@link #initStorage} / {@link #resetStorage} 由 {@code ClientLifecycleHelper}
 * 经门面调用，本类不持有 Minecraft 生命周期引用。
 */
public final class ClientChunkPipeline {

    private static volatile ClientChunkPipeline INSTANCE;

    /** 影子端世界根定位（initStorage 记录；hassium_cache/<serverId>/world）。 */
    private volatile java.nio.file.Path gameDir;
    private volatile String serverId;

    /** 元数据 contentHash 暂存：DimensionKey 复合键 -> (hash, timestamp)，用于收到数据后写入缓存 */
    private final Map<Long, PendingHash> pendingContentHashes = new ConcurrentHashMap<>();

    /** section 哈希暂存：DimensionKey 复合键 -> (sectionHashes, timestamp)，用于 persist 时一起写入 */
    private final Map<Long, PendingSectionHashes> pendingSectionHashes = new ConcurrentHashMap<>();

    /** 条目过期时间（30秒） */
    private static final long PENDING_HASH_TTL_MS = 30_000;

    /** 上次清理时间 */
    private volatile long lastPendingCleanupTime = 0;

    /** 清理间隔（5秒） */
    private static final long PENDING_CLEANUP_INTERVAL_MS = 5_000;

    // === SeedGen 握手信息（服务端 S2C 下发；断连清空） ===
    private volatile long serverSeed = 0L;
    private volatile byte[] serverLevelStemNbt = null;
    private volatile boolean serverSeedGenEnabled = false;

    // === 影子端状态（非网络向功能总开关） ===
    /** 服务端已装 Hassium MOD（能力握手响应到达；setServerSeedInfo 调用点 = 三加载器握手解码）。 */
    private volatile boolean hassiumHandshakeDone = false;
    /** 影子服务端创建成功（启用态：客户端不计算光照，统一投递影子端）。 */
    private volatile boolean shadowServerReady = false;
    /** 影子服务端创建失败（降级态：缓存/OVD/SeedGen 全关 + 游戏内报错）。 */
    private volatile boolean shadowServerFailed = false;

    private record PendingHash(long hash, long timestamp) {}
    private record PendingSectionHashes(long[] hashes, long timestamp) {}

    /**
     * Hassium 内部 apply 进行中标志（缓存读回 / OVD / 压缩通道）。
     * <p>
     * Hassium 的 applyToLevelFromByteBuf 内部会调用官方区块应用路径，而调用方
     * （processQueueUntil / HANDLE_COMPRESSED 回调）本身已在主线程预算内——置位此标志
     * 供区块应用路径识别「Hassium 预算内 apply」，避免重入冲突（入队后立即 hasChunk
     * 校验失败 → 假失败 → 缓存路径重请求风暴、OVD 全量失败）。
     */
    private final ThreadLocal<Boolean> hassiumApplyInProgress =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 当前连接的 authoritative apply ACK；断连时与其它会话状态一并清空。 */
    private final ClientChunkApplyAckAggregator chunkApplyAcks =
            new ClientChunkApplyAckAggregator(ack -> io.github.limuqy.mc.hassium.network.core.NetworkCore
                    .getInstance().sendChunkApplyAck(ack));

    private ClientChunkPipeline() {
    }

    /**
     * 获取单例（进程内仅一份；断连不清实例，只清状态）。
     */
    public static ClientChunkPipeline getInstance() {
        if (INSTANCE == null) {
            synchronized (ClientChunkPipeline.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ClientChunkPipeline();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 初始化客户端缓存存储
     *
     * @param gameDir     游戏目录
     * @param serverId    服务器标识（如 server_127.0.0.1_25565）
     * @param dimension   维度标识（如 minecraft:overworld）
     */
    /**
     * 仅记录目录定位（gameDir/serverId；影子端世界根定位用，不创建任何存储）。
     * 与 {@link #setCacheLocation} 共用字段。
     */
    public void setCacheLocation(Path gameDir, String serverId) {
        this.gameDir = gameDir;
        this.serverId = serverId;
    }

    /** 游戏目录（影子端世界根定位用；未初始化返回 null）。 */
    public java.nio.file.Path getGameDir() {
        return gameDir;
    }

    /** 服务器标识（如 server_127.0.0.1_25565；未初始化返回 null）。 */
    public String getServerId() {
        return serverId;
    }

    /**
     * 重置客户端缓存存储（断开连接时调用）
     */
    public void resetStorage() {
        pendingContentHashes.clear();
        pendingSectionHashes.clear();
        serverSeed = 0L;
        serverLevelStemNbt = null;
        serverSeedGenEnabled = false;
        hassiumHandshakeDone = false;
        shadowServerReady = false;
        shadowServerFailed = false;
        chunkApplyAcks.clear();
    }

    /**
     * 握手 S2C 下发 SeedGen 信息后调用（客户端）。
     */
    public void setServerSeedInfo(long seed, byte[] levelStemNbt, boolean enabled) {
        this.serverSeed = seed;
        this.serverLevelStemNbt = levelStemNbt;
        this.serverSeedGenEnabled = enabled;
        this.hassiumHandshakeDone = true; // 握手响应到达 = 服务端已装 Hassium MOD
        // 取消投机看门狗（已确认 Hassium 服，勿关停刚拉起的影子）
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance()
                    .cancelSpeculativeWatchdogPublic();
        } catch (Throwable ignored) {
        }
        if (enabled) {
            Constants.LOG.info("Hassium: Server SeedGen enabled; world seed will be saved in shadow level.dat");
        }
    }

    // === 影子端状态访问 ===

    /** 服务端是否已装 Hassium MOD（能力握手响应到达）。 */
    public boolean isHassiumHandshakeDone() {
        return hassiumHandshakeDone;
    }

    /** 影子服务端创建成功标记（ShadowLightCompute 启动任务回填）。 */
    public void setShadowServerReady(boolean ready) {
        this.shadowServerReady = ready;
    }

    /** 影子服务端创建失败标记（ShadowLightCompute 启动任务回填）。 */
    public void setShadowServerFailed(boolean failed) {
        this.shadowServerFailed = failed;
    }

    /**
     * 影子端启用态（客户端不再计算光照，区块光照统一投递影子端）：
     * 配置开启 && 服务端已装 MOD && 影子服务端创建成功。
     */
    public boolean isShadowEngineAvailable() {
        return hassiumHandshakeDone && shadowServerReady && !shadowServerFailed;
    }

    /**
     * 影子端激活（投递/分支判定用）：配置开（调用方另查）&& 握手完成 && 未失败。
     * 创建进行中（shadowServerReady=false）也激活——投递入队等创建完成，
     * 避免首波 chunk 落回客户端重算。
     */
    public boolean isShadowEngineActive() {
        return hassiumHandshakeDone && !shadowServerFailed;
    }

    /** 影子端创建失败（降级态全关判定；配置关时由 HassiumConfigService 短路）。 */
    public boolean isShadowServerFailed() {
        return shadowServerFailed;
    }

    /** 服务端主世界 seed（握手下发；未下发为 0）。 */
    public long getServerSeed() {
        return serverSeed;
    }

    /** 服务端主世界 LevelStem NBT（握手下发；未下发为 null）。 */
    public byte[] getServerLevelStemNbt() {
        return serverLevelStemNbt;
    }

    /** 服务端是否启用 SeedGen（握手下发）。 */
    public boolean isServerSeedGenEnabled() {
        return serverSeedGenEnabled;
    }

    /**
     * 暂存 contentHash，供后续收到区块数据时使用（指定维度）。
     */
    public void storePendingContentHash(String dimension, int chunkX, int chunkZ, long contentHash) {
        evictExpiredEntries();
        pendingContentHashes.put(DimensionKey.key(dimension, chunkX, chunkZ),
                new PendingHash(contentHash, System.currentTimeMillis()));
    }

    /** 暂存 contentHash（主世界；过渡期兼容签名，语义 = OVERWORLD）。 */
    public void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        storePendingContentHash(DimensionKey.OVERWORLD, chunkX, chunkZ, contentHash);
    }

    /**
     * 取出并移除暂存的 contentHash（指定维度）。
     */
    public long consumePendingContentHash(String dimension, int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.remove(DimensionKey.key(dimension, chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /** 取出并移除暂存的 contentHash（主世界；过渡期兼容签名）。 */
    public long consumePendingContentHash(int chunkX, int chunkZ) {
        return consumePendingContentHash(DimensionKey.OVERWORLD, chunkX, chunkZ);
    }

    /**
     * 窥视暂存 contentHash（不移除），供异步入库与 apply 共用（指定维度）。
     */
    public long peekPendingContentHash(String dimension, int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.get(DimensionKey.key(dimension, chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /** 窥视暂存 contentHash（主世界；过渡期兼容签名）。 */
    public long peekPendingContentHash(int chunkX, int chunkZ) {
        return peekPendingContentHash(DimensionKey.OVERWORLD, chunkX, chunkZ);
    }

    /**
     * 取出并移除暂存的 section 哈希（指定维度）。
     */
    public long[] consumePendingSectionHashes(String dimension, int chunkX, int chunkZ) {
        PendingSectionHashes entry =
                pendingSectionHashes.remove(DimensionKey.key(dimension, chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    /** 取出并移除暂存的 section 哈希（主世界；过渡期兼容签名）。 */
    public long[] consumePendingSectionHashes(int chunkX, int chunkZ) {
        return consumePendingSectionHashes(DimensionKey.OVERWORLD, chunkX, chunkZ);
    }

    /** 窥视暂存 section 哈希（指定维度；不移除）。 */
    public long[] peekPendingSectionHashes(String dimension, int chunkX, int chunkZ) {
        PendingSectionHashes entry =
                pendingSectionHashes.get(DimensionKey.key(dimension, chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    /** 窥视暂存 section 哈希（主世界；过渡期兼容签名）。 */
    public long[] peekPendingSectionHashes(int chunkX, int chunkZ) {
        return peekPendingSectionHashes(DimensionKey.OVERWORLD, chunkX, chunkZ);
    }

    /** 是否正在 Hassium 预算内的 apply（重入标志，供区块应用路径识别）。 */
    public boolean isApplyInProgress() {
        return hassiumApplyInProgress.get();
    }

    /** 设置 Hassium 预算内 apply 重入标志（apply 前后配对调用）。 */
    public void setApplyInProgress(boolean inProgress) {
        hassiumApplyInProgress.set(inProgress);
    }

    /** 只接收最终成功的权威落地；0 表示非 flow-controlled 投递。 */
    void recordAuthoritativeApply(long deliveryId) {
        chunkApplyAcks.recordApplied(deliveryId);
    }

    /** 客户端 tick 尾冲刷已落地的 authoritative delivery。 */
    void flushChunkApplyAcks() {
        chunkApplyAcks.flush();
    }

    public int pendingAckCount() {
        return chunkApplyAcks.size();
    }

    /** 断线时丢弃旧会话 ACK，禁止跨连接重放。 */
    void clearChunkApplyAcks() {
        chunkApplyAcks.clear();
    }

    /**
     * 懒清理过期条目（定期调用，避免无限增长）
     */
    private void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        if (now - lastPendingCleanupTime < PENDING_CLEANUP_INTERVAL_MS) {
            return;
        }
        lastPendingCleanupTime = now;
        pendingContentHashes.entrySet().removeIf(e -> now - e.getValue().timestamp() > PENDING_HASH_TTL_MS);
        pendingSectionHashes.entrySet().removeIf(e -> now - e.getValue().timestamp() > PENDING_HASH_TTL_MS);
    }

}
