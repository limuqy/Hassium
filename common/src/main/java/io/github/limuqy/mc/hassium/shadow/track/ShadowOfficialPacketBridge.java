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

    public static boolean forwardToRealClient(Packet<?> packet) {
        if (!enabled || packet == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.level == null) {
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
        }
    }
}
