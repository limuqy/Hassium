package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 超视渲染客户端边界（chunk-cache.md §10）：只抬 {@link ClientChunkCache} 半径、
 * 计算 effective clientVD、Forget 保留。环带枚举 / miss / 延迟卸载全部由影子端承担。
 */
public final class OvdClientLifecycle {

    private OvdClientLifecycle() {}

    /** OVD 配置门（几何/半径可先于握手发布；服务端分流仍看影子会话）。 */
    public static boolean isConfigEnabled() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        return cfg.isClientCacheEnabled() && cfg.isViewDistanceExtensionEnabled();
    }

    /**
     * OVD 可用：纯影子端能力——配置开 + 影子引擎未失败。
     * 不依赖服务端握手同意：环带只由本地源（盘/注入/可选生成）回填。
     */
    public static boolean isEnabled() {
        return isConfigEnabled() && ShadowLightCompute.isEnabled();
    }

    /** 服务端通告视距（登录包 / SetChunkCacheRadius；未知返回 -1）。 */
    public static int serverViewDistance() {
        return ShadowTrackingSession.serverViewDistance();
    }

    /**
     * effective clientVD：OVD 配置开 && 滑块 &gt; serverVD 时 = min(滑块, maxRenderDistance)；
     * 否则回落 serverVD（客户端「以为」服务器视距 = max(ovd, svd)）。
     * <p>几何发布不依赖握手——握手前也要把半径/影子扩窗算出来，否则 R2 虚拟玩家
     * 落座时 effective 仍是 -1，ticket 永久钉在权威边距。
     */
    public static int effectiveClientVD(Minecraft mc) {
        int serverVD = serverViewDistance();
        if (mc == null || mc.options == null || serverVD <= 0) {
            return Math.max(2, serverVD);
        }
        int slider = mc.options.renderDistance().get();
        if (!isConfigEnabled() || slider <= serverVD) {
            return serverVD;
        }
        int max = HassiumConfigService.getInstance().getMaxRenderDistance();
        return Math.max(serverVD, Math.min(slider, max));
    }

    /** 客户端 tick：守护半径 + 把 effective VD 发布给影子会话。 */
    public static void onClientTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }
        if (mc.getSingleplayerServer() != null) {
            return;
        }
        int effective = effectiveClientVD(mc);
        ShadowTrackingSession.publishEffectiveClientVD(effective);
        ensureClientRenderBounds(mc, effective);
        if (effective != lastLoggedEffective) {
            lastLoggedEffective = effective;
            var pipeline = io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance();
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[OVD] effectiveClientVD={} serverVD={} slider={} effectiveRD={} config={} engine={} shadowFailed={}",
                    effective, serverViewDistance(),
                    mc.options.renderDistance().get(),
                    mc.options.getEffectiveRenderDistance(),
                    isConfigEnabled(), isEnabled(),
                    pipeline.isShadowServerFailed());
        }
    }

    private static volatile int lastLoggedEffective = -1;

    /**
     * 客户端可见半径守护：
     * <ol>
     *   <li>抬 {@link ClientChunkCache} 存储半径到 effective（防 {@code SetChunkCacheRadius} 缩回后
     *       影子 OVD 包被 Storage.inRange 丢弃）。</li>
     *   <li>抬 {@code Options.serverRenderDistance}——vanilla {@code getEffectiveRenderDistance()}
     *       = {@code min(slider, serverRenderDistance)}，只扩缓存不抬渲染钳时，OVD 环带
     *       柱会 apply 进缓存却永不 mesh（R2 实证 max cheb=10）。</li>
     * </ol>
     * 无状态，每 tick 调用。
     */
    public static void ensureClientRenderBounds(Minecraft mc, int radius) {
        if (mc == null || mc.level == null || radius <= 0) {
            return;
        }
        try {
            ClientChunkCacheRadius.apply(((ClientLevelAccessor) mc.level).hassium$getChunkSource(), radius);
        } catch (Throwable t) {
            DebugLogger.debug(DebugLogger.LogType.CACHE,
                    "[OVD] expand ClientChunkCache radius to {} failed", radius, t);
        }
        try {
            // 仅抬不压：SetChunkCacheRadius 仍会写真实 serverVD，下一 tick 本方法再抬回 effective。
            if (mc.options != null && mc.options.getEffectiveRenderDistance() < radius) {
                mc.options.setServerRenderDistance(radius);
            }
        } catch (Throwable t) {
            DebugLogger.debug(DebugLogger.LogType.CACHE,
                    "[OVD] raise serverRenderDistance to {} failed", radius, t);
        }
    }

    /**
     * 抬高 ClientChunkCache 半径到 effective（防 SetChunkCacheRadius 缩回后
     * 影子 OVD 包被 Storage.inRange 丢弃）。无状态，每 tick 调用。
     */
    public static void ensureChunkCacheRadius(ClientLevel level, int radius) {
        if (level == null || radius <= 0) {
            return;
        }
        try {
            ClientChunkCacheRadius.apply(((ClientLevelAccessor) level).hassium$getChunkSource(), radius);
        } catch (Throwable t) {
            DebugLogger.debug(DebugLogger.LogType.CACHE,
                    "[OVD] expand ClientChunkCache radius to {} failed", radius, t);
        }
    }

    /**
     * 真服 Forget 时是否保留：仍在 effective 窗内（!serverRange && clientRange）。
     * 玩家走出权威圈时真服会 Forget，柱尚在 client 窗——不拦会闪虚空。
     */
    public static boolean shouldRetainOnForget(ChunkPos pos) {
        if (pos == null || !isEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        int serverVD = serverViewDistance();
        int clientVD = effectiveClientVD(mc);
        if (serverVD <= 0 || clientVD <= serverVD) {
            return false;
        }
        ChunkPos playerPos = mc.player.chunkPosition();
        int dx = pos.x - playerPos.x;
        int dz = pos.z - playerPos.z;
        if (isInClientChebyshev(dx, dz, clientVD)) {
            return !isInServerShape(dx, dz, serverVD);
        }
        return false;
    }

    /** 影子端 OVD 窗判定：client 窗内且权威窗外（与 shouldRetainOnForget 同几何）。 */
    public static boolean isOvdWindow(int playerChunkX, int playerChunkZ,
                                      int serverVD, int clientVD, int x, int z) {
        if (serverVD <= 0 || clientVD <= serverVD) {
            return false;
        }
        int dx = x - playerChunkX;
        int dz = z - playerChunkZ;
        return isInClientChebyshev(dx, dz, clientVD) && !isInServerShape(dx, dz, serverVD);
    }

    private static boolean isInServerShape(int dx, int dz, int serverVD) {
        return ChunkShapeCompat.contains(0, 0, serverVD, dx, dz);
    }

    private static boolean isInClientChebyshev(int dx, int dz, int radius) {
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }
}
