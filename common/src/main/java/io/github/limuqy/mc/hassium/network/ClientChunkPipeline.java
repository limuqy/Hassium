package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;

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

    /** 客户端缓存存储（断连置 null） */
    private volatile ClientHassiumStorage clientStorage;

    /** 元数据 contentHash 暂存：chunkPos -> (hash, timestamp)，用于收到数据后写入缓存 */
    private final Map<Long, PendingHash> pendingContentHashes = new ConcurrentHashMap<>();

    /** section 哈希暂存：chunkPos -> (sectionHashes, timestamp)，用于 persist 时一起写入 */
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
     * {@code MixinVanillaChunkApplyBudget}（1.20.1~1.21.10 段）把 {@code handleLevelChunkWithLight}
     * 统一路由到 MainThreadDispatcher 预算队列；但 Hassium 的 applyToLevelFromByteBuf 内部
     * 也会调用该方法，且调用方（processQueueUntil / HANDLE_COMPRESSED 回调）本身已在主线程
     * 预算内——再次拦截会造成「入队后立即 hasChunk 校验失败 → 假失败 → 缓存路径重请求风暴、
     * OVD 全量失败」的恶性循环。此标志让 Mixin 放行 Hassium 预算内的 apply。
     */
    private final ThreadLocal<Boolean> hassiumApplyInProgress =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

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
    public void initStorage(Path gameDir, String serverId, String dimension) {
        // 维度目录名：将冒号替换为下划线
        String dimDir = dimension.replaceAll("[^a-zA-Z0-9._-]", "_");
        clientStorage = new ClientHassiumStorage(gameDir, serverId, dimDir);
        Constants.LOG.info("Hassium: Initialized client chunk cache for server {} dimension {}", serverId, dimension);
    }

    /**
     * 获取客户端缓存存储实例
     */
    public ClientHassiumStorage getClientStorage() {
        return clientStorage;
    }

    /**
     * 重置客户端缓存存储（断开连接时调用）
     */
    public void resetStorage() {
        clientStorage = null;
        pendingContentHashes.clear();
        pendingSectionHashes.clear();
        serverSeed = 0L;
        serverLevelStemNbt = null;
        serverSeedGenEnabled = false;
        hassiumHandshakeDone = false;
        shadowServerReady = false;
        shadowServerFailed = false;
        ClientChunkDirtyTracker.clearAll();
    }

    /**
     * 握手 S2C 下发 SeedGen 信息后调用（客户端）。
     */
    public void setServerSeedInfo(long seed, byte[] levelStemNbt, boolean enabled) {
        this.serverSeed = seed;
        this.serverLevelStemNbt = levelStemNbt;
        this.serverSeedGenEnabled = enabled;
        this.hassiumHandshakeDone = true; // 握手响应到达 = 服务端已装 Hassium MOD
        if (enabled) {
            Constants.LOG.info("Hassium: Server SeedGen enabled (seed={})", seed);
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
     * 暂存 contentHash，供后续收到区块数据时使用
     */
    public void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        evictExpiredEntries();
        pendingContentHashes.put(chunkPosKey(chunkX, chunkZ), new PendingHash(contentHash, System.currentTimeMillis()));
    }

    /**
     * 取出并移除暂存的 contentHash
     */
    public long consumePendingContentHash(int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.remove(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /**
     * 窥视暂存 contentHash（不移除），供异步入库与 apply 共用。
     */
    public long peekPendingContentHash(int chunkX, int chunkZ) {
        PendingHash entry = pendingContentHashes.get(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hash() : 0L;
    }

    /**
     * 取出并移除暂存的 section 哈希
     */
    public long[] consumePendingSectionHashes(int chunkX, int chunkZ) {
        PendingSectionHashes entry = pendingSectionHashes.remove(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    public long[] peekPendingSectionHashes(int chunkX, int chunkZ) {
        PendingSectionHashes entry = pendingSectionHashes.get(chunkPosKey(chunkX, chunkZ));
        return entry != null ? entry.hashes() : null;
    }

    /** 是否正在 Hassium 预算内的 apply（MixinVanillaChunkApplyBudget 豁免判定）。 */
    public boolean isApplyInProgress() {
        return hassiumApplyInProgress.get();
    }

    /** 设置 Hassium 预算内 apply 重入标志（apply 前后配对调用）。 */
    public void setApplyInProgress(boolean inProgress) {
        hassiumApplyInProgress.set(inProgress);
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

    private static long chunkPosKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
