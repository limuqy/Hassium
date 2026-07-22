package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.github.limuqy.mc.hassium.network.ZstdPipelineSwitcher;
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
 * 拦截 Connection.setupCompression()，当 ZSTD 已协商时切换管线压缩。
 * <p>
 * 时序说明：
 * - Login 阶段：setupCompression() 被调用，此时 ZSTD 未协商，原版 Zlib 正常安装
 * - Play 阶段：Hassium 握手完成，ZstdNegotiationTracker 标记已协商
 * - 后续 setupCompression() 调用：切换到 ZSTD 而非阻止
 */
@Mixin(Connection.class)
public abstract class MixinConnectionSetupCompression {

    @Shadow
    private Channel channel;

    private static final Logger hassium$LOGGER = LoggerFactory.getLogger("Hassium/PacketCompression");

    /**
     * 当 ZSTD 已协商时，切换到管线级 ZSTD 替换原版 Zlib。
     */
    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void hassium$switchToZstdWhenNegotiated(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        if (ZstdNegotiationTracker.isZstdNegotiated(this.channel)) {
            int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
            hassium$LOGGER.info("Switching to ZSTD pipeline (threshold={}, level={})", threshold, level);
            ZstdPipelineSwitcher.switchToZstd(this.channel, threshold, level);
            ci.cancel();
        }
    }
}
