package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.IndexSyncManager;
import io.github.limuqy.mc.hassium.network.NamespaceIndexManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 PacketEncoder，实现紧凑包头编码
 * <p>
 * 在编码自定义 Payload 包时，将 ResourceLocation 字符串替换为 VarInt 索引
 */
@Mixin(PacketEncoder.class)
public class MixinPacketEncoder {

    @Unique
    private static final byte hassium$COMPACT_FLAG = 1;
    @Unique
    private static final byte hassium$ORIGINAL_FLAG = 0;

    /**
     * 拦截 encode 方法，在编码后检查是否需要紧凑包头处理
     */
    @Inject(method = "encode", at = @At("RETURN"))
    private void hassium$onEncode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        // 检查是否启用紧凑包头
        HassiumConfigService config = HassiumConfigService.getInstance();
        if (!config.isCompactHeaderEnabled()) {
            return;
        }

        // 获取索引管理器
        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
        NamespaceIndexManager indexManager = indexSyncManager.getServerIndexManager();

        // 检查是否有索引
        if (indexManager.size() == 0) {
            return;
        }

        // 注意：这里只是框架，实际实现需要根据具体的包格式来处理
        // 因为 Minecraft 1.20.1 的自定义 Payload 包格式比较复杂
        // 需要识别哪些包是自定义 Payload 包，然后在包数据开头替换 ResourceLocation
    }
}
