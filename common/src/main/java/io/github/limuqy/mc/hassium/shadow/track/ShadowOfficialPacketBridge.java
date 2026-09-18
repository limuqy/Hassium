package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * 接法 B：影子专用服产出的官方包 → 真实客户端 {@link ClientPacketListener}。
 * <p>
 * 影子上下文 {@code trackChunk} / {@code playerLoadedChunk} / {@code sendChunk}
 * 在此转发，不再依赖 dummy Connection 丢弃后的 publish 旁路作为主路径。
 */
public final class ShadowOfficialPacketBridge {

    private ShadowOfficialPacketBridge() {}

    /** 是否允许桥接（S3；false 时回落 publish 管线）。 */
    public static volatile boolean enabled = true;

    /**
     * 影子端官方包 → 真实客户端。
     * <p>
     * <b>维度闸</b>：原版 {@code ClientboundLevelChunkWithLightPacket} /
     * {@code ClientboundLightUpdatePacket} **不带维度字段**（维度由 {@code ClientboundRespawnPacket}
     * 切换的 level 隐含），{@link #deliver} 直接把它们交给客户端当前 level。影子端是**一个
     * MinecraftServer 带多个 ServerLevel**，切维（如 TP 进暮色森林）后旧维度的在途柱仍在跑光门/
     * 打包/Provider 队列，若直通就会把旧维度地形落进新维度（实测：TP 进 TF 后仍投递 overworld 柱
     * → 暮色森林里出现主世界区块）。
     * <p>
     * 故投递必须校验**包的来源维度**与客户端当前维度一致；不一致即丢弃。
     * 丢弃安全：影子虚拟玩家离开旧维度时会 {@code untrackChunk}，重进时重新 track → 重新投递
     * （与重入重交付同一条链）。
     *
     * @param sourceDimension 产出该包的影子端维度 id；{@code null} 表示未知（不做维度闸）
     */
    public static boolean forwardToRealClient(Packet<?> packet, String sourceDimension) {
        if (!enabled || packet == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.level == null) {
            return false;
        }
        String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(mc.level);
        if (sourceDimension != null && dimension != null && !sourceDimension.equals(dimension)) {
            Constants.LOG.debug(
                    "Hassium: drop cross-dimension shadow packet {} ({} -> {})",
                    packet.getClass().getSimpleName(), sourceDimension, dimension);
            return false;
        }
        if (dimension != null && packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            if (io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                    .blockClientDelivery(dimension, chunkPacket.getX(), chunkPacket.getZ())) {
                Constants.LOG.debug(
                        "Hassium: seedGen awaiting compare, drop bridge chunk ({},{})",
                        chunkPacket.getX(), chunkPacket.getZ());
                return false;
            }
            // 回归原版 trackChunk：不因「光未对齐」丢弃官方整柱包；欠光首包光桥后补。
        }
        if (dimension != null && packet instanceof ClientboundLightUpdatePacket lightPacket
                && io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                        .blockClientDelivery(dimension, lightPacket.getX(), lightPacket.getZ())) {
            return false;
        }
        try {
            mc.execute(() -> deliver(mc.getConnection(), packet));
            return true;
        } catch (Throwable t) {
            Constants.LOG.debug("Hassium: shadow packet bridge failed {}", packet, t);
            return false;
        }
    }

    private static void deliver(ClientPacketListener listener, Packet<?> packet) {
        // 接法 B 直通原版落地：必须置 applyInProgress，否则 MixinClientPacketListener
        // 会把桥包再次 intercept 进影子 light 管线（Halo/ready 二次门禁 → 真空洞）。
        io.github.limuqy.mc.hassium.client.ClientChunkPipeline pipeline =
                io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance();
        pipeline.setApplyInProgress(true);
        try {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                listener.handleLevelChunkWithLight(chunkPacket);
            } else if (packet instanceof ClientboundLightUpdatePacket lightPacket) {
                listener.handleLightUpdatePacket(lightPacket);
            } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
#if MC_VER < MC_1_21_1
                listener.handleForgetLevelChunk(forget);
#else
                listener.handleForgetLevelChunk(forget);
#endif
            } else {
                // 其它包：不猜 listener 方法，避免版本漂移
                return;
            }
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_BRIDGE] forward {}", packet.getClass().getSimpleName());
        } catch (Throwable t) {
            Constants.LOG.debug("Hassium: shadow bridge deliver failed {}", packet, t);
        } finally {
            pipeline.setApplyInProgress(false);
        }
    }
}
