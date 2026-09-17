package io.github.limuqy.mc.hassium.platform.client;

import io.github.limuqy.mc.hassium.Constants;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * {@link ShadowClientApi} 注册表。客户端登录路径注册真实实现；
 * 未注册时返回 {@link #noop()}（单元测试 / 未进服），shadow 侧保持可编译可调用。
 */
public final class ShadowClientBridge {

    private static volatile ShadowClientApi impl;
    private static final AtomicBoolean LOGGED_NOOP = new AtomicBoolean();
    private static final ShadowClientApi NOOP = new NoopApi();

    private ShadowClientBridge() {
    }

    public static void register(ShadowClientApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static void unregister() {
        impl = null;
    }

    /** 已注册实现；未注册返回 no-op（不抛 NPE，避免影子启动竞态打崩登录）。 */
    public static ShadowClientApi get() {
        ShadowClientApi api = impl;
        if (api != null) {
            return api;
        }
        if (LOGGED_NOOP.compareAndSet(false, true)) {
            Constants.LOG.debug("Hassium: ShadowClientBridge not registered; using no-op client SPI");
        }
        return NOOP;
    }

    public static boolean isRegistered() {
        return impl != null;
    }

    static ShadowClientApi noop() {
        return NOOP;
    }

    private static final class NoopApi implements ShadowClientApi {
        @Override
        public Path getGameDir() {
            return null;
        }

        @Override
        public String getServerId() {
            return null;
        }

        @Override
        public long getServerSeed() {
            return 0L;
        }

        @Override
        public byte[] getServerLevelStemNbt() {
            return null;
        }

        @Override
        public List<String> getServerDimensionIds() {
            return List.of();
        }

        @Override
        public boolean isHassiumHandshakeDone() {
            return false;
        }

        @Override
        public boolean isShadowEngineActive() {
            return false;
        }

        @Override
        public boolean isShadowServerFailed() {
            return false;
        }

        @Override
        public boolean isShadowServerReady() {
            return false;
        }

        @Override
        public void setShadowServerReady(boolean ready) {
        }

        @Override
        public void setShadowServerFailed(boolean failed) {
        }

        @Override
        public boolean isServerSeedGenEnabled() {
            return false;
        }

        @Override
        public boolean isServerSeedAvailable() {
            return false;
        }

        @Override
        public boolean isSeedGenHardDisabled() {
            return false;
        }

        @Override
        public void disableSeedGen(String reason) {
        }

        @Override
        public long peekPendingContentHash(String dimension, int chunkX, int chunkZ) {
            return 0L;
        }

        @Override
        public void setApplyInProgress(boolean inProgress) {
        }

        @Override
        public String currentServerIp() {
            return null;
        }

        @Override
        public boolean tryAcquireCacheRead() {
            return true;
        }

        @Override
        public void refundCacheRead() {
        }

        @Override
        public boolean isJoinBoostActive() {
            return false;
        }

        @Override
        public long joinBoostRemainingMs() {
            return 0L;
        }

        @Override
        public long getBudgetNs() {
            return 0L;
        }

        @Override
        public void noteChunkApplyActivity() {
        }

        @Override
        public void updateFocusFromClient() {
        }

        @Override
        public boolean shouldDeferFarChunk(int chunkX, int chunkZ, boolean loadingScreenVisible) {
            return false;
        }

        @Override
        public double chunkApplyPriority(int chunkX, int chunkZ, double fifo) {
            return fifo;
        }

        @Override
        public <T> Long findStandingKey(ConcurrentHashMap<Long, T> source) {
            return null;
        }

        @Override
        public <T> void fillDistanceFirst(ConcurrentHashMap<Long, T> source,
                                          List<Map.Entry<Long, T>> batch, int limit) {
            if (source == null || limit <= 0) {
                return;
            }
            for (Map.Entry<Long, T> entry : source.entrySet()) {
                if (batch.size() >= limit) {
                    break;
                }
                batch.add(entry);
            }
        }

        @Override
        public void logShadowChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, TraceOrigin origin) {
        }

        @Override
        public void onDimensionChanged() {
        }

        @Override
        public void markChunkSectionsDirty(ClientLevel level, int chunkX, int chunkZ) {
        }

        @Override
        public void onProbeChunkUnloaded(ChunkPos pos) {
        }

        @Override
        public void scheduleProbeRecheck(ChunkPos pos) {
        }

        @Override
        public void runProbeRecheck(ClientLevel level) {
        }

        @Override
        public void probeChunkState(ChunkPos pos, ClientLevel level, String source) {
        }

        @Override
        public void probeShadowLightState(ChunkPos pos, ClientLevel level, TraceOrigin fullOrigin,
                                          boolean fullRenderOnly, long fullApplySequence, long fullApplyAgeMs,
                                          long lightQueueDelayMs, boolean fullAppliedAfterLightQueued,
                                          boolean chunkPresent) {
        }

        @Override
        public String stallSnapshot() {
            return "";
        }

        @Override
        public void resetMeshCompileLog() {
        }
    }
}
