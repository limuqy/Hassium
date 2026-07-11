package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 Connection.setupCompression()，防止在 ZSTD 已协商后原版再次安装 Zlib。
 * <p>
 * 时序说明：
 * - Login 阶段：setupCompression() 被调用，此时 ZSTD 未协商，原版 Zlib 正常安装
 * - Play 阶段：Hassium 握手完成，switchToZstd() 先移除 Zlib 再安装 ZSTD
 * - 如果 setupCompression() 在 Play 阶段被再次调用（如协议切换），需要阻止原版重新安装 Zlib
 * <p>
 * 关键：vanilla setupCompression() 使用 instanceof 检查，如果 "decompress" 不是 CompressionDecoder，
 * 会尝试 addBefore() 添加同名 handler，导致 IllegalArgumentException。此 mixin 阻止这种情况。
 */
@Mixin(Connection.class)
public abstract class MixinConnectionSetupCompression {

    @Shadow
    private Channel channel;

    private static final Logger hassium$LOGGER = LoggerFactory.getLogger("Hassium/PacketCompression");

    /**
     * 当 ZSTD 已协商时，阻止原版 setupCompression() 安装/更新 Zlib Handler。
     * 这避免了以下问题：
     * 1. ZSTD handler 被 Zlib handler 覆盖
     * 2. addBefore() 因同名 handler 已存在而抛出 IllegalArgumentException
     */
    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void hassium$preventVanillaCompressionWhenZstdActive(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        // 仅当 ZSTD 已协商时阻止原版安装
        if (ZstdNegotiationTracker.isZstdNegotiated(this.channel)) {
            hassium$LOGGER.debug("Blocked vanilla setupCompression() because ZSTD is active (threshold={})", threshold);
            ci.cancel();
        }
        // 未协商时不做任何事，让原版 Zlib 正常安装
    }
}
