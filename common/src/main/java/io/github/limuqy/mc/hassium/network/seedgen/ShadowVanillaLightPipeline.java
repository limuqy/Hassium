package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin;
import io.github.limuqy.mc.hassium.network.ShadowChunkRole;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Role-aware stripped-chunk ingress. Injects the column, then enqueues the official
 * {@code initializeLight}+{@code lightChunk} barrier. Native {@code getChunkFuture(FULL)}
 * cannot be the publish gate: injected ImposterProtoChunks already report FULL, so
 * vanilla only {@code load}s and never {@code generate}s LIGHT — packing that result
 * sends empty sky/block layers (R1 all-black). Halos are lighted for neighbors but
 * never rendered.
 */
public final class ShadowVanillaLightPipeline {

    private ShadowVanillaLightPipeline() {}

    public static void submitVisible(String dimension, ChunkPos pos,
                                     ClientboundLevelChunkWithLightPacket packet,
                                     Object traceOrigin) {
        submit(dimension, pos, packet, ShadowChunkRole.VISIBLE,
                traceOrigin instanceof TraceOrigin origin ? origin : TraceOrigin.SERVER_PUSH);
    }

    public static void submitHalo(String dimension, ChunkPos pos,
                                  ClientboundLevelChunkWithLightPacket packet) {
        submit(dimension, pos, packet, ShadowChunkRole.HALO, TraceOrigin.SERVER_PUSH);
    }

    /**
     * 影子端尚未装配（gameDir 未记、创建中）时不得 {@code failShadowServer}：
     * 旧 {@code submit()} 只是入队等 ready；永久降级会让后续包走直 apply，
     * 分母 applied=0 且 NeoForge 进服窗口内 landed 对不上。
     */
    static boolean shouldFailShadowWhenServerUnavailable() {
        return false;
    }

    private static void submit(String dimension, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet,
                               ShadowChunkRole role, TraceOrigin origin) {
        if (pos == null || packet == null) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            if (shouldFailShadowWhenServerUnavailable()) {
                ShadowServerRegistry.getInstance().failShadowServer();
                return;
            }
            if (role == ShadowChunkRole.HALO) {
                ShadowLightCompute.submitHalo(dimension, pos, packet);
            } else {
                ShadowLightCompute.submitVisible(dimension, pos, packet);
            }
            return;
        }
        String resolvedDimension = dimension == null ? currentDimension() : dimension;
        server.setPersistenceRole(resolvedDimension, pos,
                role == ShadowChunkRole.HALO ? ShadowChunkPersistenceRole.HALO_BLOCKS_ONLY
                        : ShadowChunkPersistenceRole.VISIBLE_FULL_LIGHT);
        if (role == ShadowChunkRole.VISIBLE) {
            SmokeChunkTrace.recordNetworkReceived(resolvedDimension, pos);
        }
        if (!server.injectChunk(resolvedDimension, pos, packet, role)) {
            ShadowServerRegistry.getInstance().failShadowServer();
            return;
        }
        if (role == ShadowChunkRole.VISIBLE) {
            SmokeChunkTrace.recordShadowInjected(resolvedDimension, pos);
        }
        if (role == ShadowChunkRole.VISIBLE) {
            ShadowLightCompute.accountVisibleNetworkIngress(resolvedDimension, pos);
        }
        if (ShadowLightCompute.shouldSkipLightCompute()) {
            if (role == ShadowChunkRole.VISIBLE) {
                ShadowLightCompute.publishWithoutLightCompute(resolvedDimension, pos, packet, origin);
            }
            return;
        }
        ShadowLightCompute.enqueueInjectedForLight(resolvedDimension, pos, role, origin);

    }

    public static String currentDimension() {
        return ShadowLightCompute.currentDimension();
    }

    public static boolean isRenderable(ShadowChunkRole role) {
        return role == ShadowChunkRole.VISIBLE;
    }
}
