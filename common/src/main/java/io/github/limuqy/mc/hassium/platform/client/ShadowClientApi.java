package io.github.limuqy.mc.hassium.platform.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * shadow 访问客户端运行时能力的 SPI（handoff-common-repackage-shadow §2.1）。
 * <p>
 * 实现方 = {@code ClientChunkPipeline}（登录后注册进 {@link ShadowClientBridge}）。
 * shadow 侧只依赖本接口 + Bridge，不 import client/network 业务类。
 */
public interface ShadowClientApi {

    // === 会话 / 握手 / SeedGen（原 ClientChunkPipeline）===

    Path getGameDir();

    String getServerId();

    long getServerSeed();

    byte[] getServerLevelStemNbt();

    List<String> getServerDimensionIds();

    boolean isHassiumHandshakeDone();

    boolean isShadowEngineActive();

    boolean isShadowServerFailed();

    boolean isShadowServerReady();

    void setShadowServerReady(boolean ready);

    void setShadowServerFailed(boolean failed);

    boolean isServerSeedGenEnabled();

    boolean isServerSeedAvailable();

    boolean isSeedGenHardDisabled();

    void disableSeedGen(String reason);

    long peekPendingContentHash(String dimension, int chunkX, int chunkZ);

    void setApplyInProgress(boolean inProgress);

    /** ConnectScreen / pending 目录兜底用的当前服务器 IP；无则 null。 */
    String currentServerIp();

    // === 主线程预算（原 ClientMainThreadBudget）===

    boolean tryAcquireCacheRead();

    void refundCacheRead();

    boolean isJoinBoostActive();

    long joinBoostRemainingMs();

    long getBudgetNs();

    void noteChunkApplyActivity();

    // === 进服脚下焦点（原 JoinWorldFocus）===

    void updateFocusFromClient();

    boolean shouldDeferFarChunk(int chunkX, int chunkZ, boolean loadingScreenVisible);

    double chunkApplyPriority(int chunkX, int chunkZ, double fifo);

    <T> Long findStandingKey(ConcurrentHashMap<Long, T> source);

    <T> void fillDistanceFirst(ConcurrentHashMap<Long, T> source,
                               List<Map.Entry<Long, T>> batch, int limit);

    // === 区块 handler 诊断 / 探针（原 ClientChunkHandler 等）===

    void logShadowChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, TraceOrigin origin);

    void onDimensionChanged();

    void markChunkSectionsDirty(ClientLevel level, int chunkX, int chunkZ);

    void onProbeChunkUnloaded(ChunkPos pos);

    void scheduleProbeRecheck(ChunkPos pos);

    void runProbeRecheck(ClientLevel level);

    void probeChunkState(ChunkPos pos, ClientLevel level, String source);

    void probeShadowLightState(ChunkPos pos, ClientLevel level, TraceOrigin fullOrigin,
                               boolean fullRenderOnly, long fullApplySequence, long fullApplyAgeMs,
                               long lightQueueDelayMs, boolean fullAppliedAfterLightQueued,
                               boolean chunkPresent);

    /** 原 ClientMetadataHandler.stallSnapshot 诊断串。 */
    String stallSnapshot();

    /** 原 ChunkMeshCompileLog.reset（维度切换）。 */
    void resetMeshCompileLog();
}
