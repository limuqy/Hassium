package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按包实测服务端出站 chunk 包中光照数据的线格式字节数
 * （{@code ClientboundLightUpdatePacketData.write} 前后 writerIndex 差值）。
 * <p>
 * 用途：校准 {@link NetworkStats#ESTIMATED_LIGHT_BYTES}（16KB/块）估算——实测
 * 均值/占比用于 {@code serverNetwork.lightStrip} 开关的带宽决策（同 NEB
 * {@code ChunkPacketBreakdown.light_share_of_chunk_packet} 口径）。
 * <p>
 * 口径：覆盖**所有**出站 chunk 包（{@code ClientboundLevelChunkWithLightPacket}）——
 * 含握手完成前原版直发（真实 light）与 Hassium 接管后的剥光包（空 BitSet，~6B）；
 * 客户端只 decode 不调 write，天然只统计服务端。增量 light 包（
 * {@code ClientboundLightUpdatePacket}）被 {@code MixinChunkHolder} 拦截转
 * LightDeltaS2C，不参与此统计。
 * <p>
 * 线程安全：encode 在 pushPool/Netty 多线程执行，ThreadLocal 隔离测量，AtomicLong 累计。
 */
@Mixin(ClientboundLightUpdatePacketData.class)
public abstract class MixinLightDataWrite {

    @Unique
    private static final ThreadLocal<Integer> hassium$lightWriteStartIdx = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    private void hassium$captureLightWriteStart(FriendlyByteBuf buf, CallbackInfo ci) {
        hassium$lightWriteStartIdx.set(buf.writerIndex());
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void hassium$recordLightWriteSize(FriendlyByteBuf buf, CallbackInfo ci) {
        Integer start = hassium$lightWriteStartIdx.get();
        if (start != null) {
            int bytes = buf.writerIndex() - start;
            if (bytes > 0) {
                NetworkStats.recordLightDataBytes(bytes);
            }
        }
    }
}
