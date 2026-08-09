package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 区块光照投递入口 Mixin（旧链兜底/OVD 路径）。
 * <p>
 * 检测到空光照（服务端剥离）后投递影子端（Hassium 引擎）计算；客户端不再包含
 * 本地光照逻辑——光照只来自影子端或随包自带（剥光与否在握手协商：客户端未声明
 * 引擎可用时服务端不剥光）。
 * <p>
 * 主链路（压缩通道）已由 {@code ClientChunkHandler} 在解压后直接投递影子端
 * （注入 → 算光 → 打包官方包 → 官方通道落地），本 Mixin 只覆盖旧链兜底
 * （影子端注入失败柱 apply 的剥光块）与 OVD renderOnly 的空光块。
 * <p>
 * 合并 pipeline：handleLevelChunkWithLight TAIL 时区块已 apply，直接投递，
 * 不再经过 MainThreadDispatcher 延迟调度，避免跨帧黑块。
 */
@Mixin(ClientPacketListener.class)
public class MixinLightRecompute {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void hassium$onHandleChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (level == null) {
            return;
        }

        var lightData = packet.getLightData();
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            // 服务端带光块：权威光照随包落地（apply 时 queueSectionData 生效），无需投递。
            return;
        }

        // 空光照（服务端剥离）：影子端启用态统一投递影子端计算（客户端不计算）。
        // 影子端不可用时不处理——剥光仅在握手声明引擎可用后才发生（服务端侧 gate）。
        // 注入失败 = 影子链路整体失败（走降级关闭核心逻辑），无逐柱兜底。
        // 字节口径与 ClientChunkHandler.getLightBytesPerChunk 一致：用 NetworkStats.ESTIMATED_LIGHT_BYTES
        NetworkStats.recordLightCacheMiss(NetworkStats.ESTIMATED_LIGHT_BYTES);
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        if (io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submit(pos, packet);
        }
    }
}
