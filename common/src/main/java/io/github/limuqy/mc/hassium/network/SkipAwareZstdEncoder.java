package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 管线 ZSTD 编码器：支持通过 {@link HassiumPipelineAttributes#SKIP_PIPELINE_COMPRESSION}
 * 跳过下一帧压缩（供应用层聚合包使用）。
 */
public class SkipAwareZstdEncoder extends ZstdContextEncoder {

    public SkipAwareZstdEncoder(int threshold, int level, boolean magicless) {
        super(threshold, level, magicless);
    }

    public SkipAwareZstdEncoder(int threshold, int level) {
        super(threshold, level);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        Boolean skip = ctx.channel().attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).getAndSet(false);
        if (Boolean.TRUE.equals(skip)) {
            int outStart = out.writerIndex();
            FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);
            friendlyBuf.writeVarInt(0);
            friendlyBuf.writeBytes(in);
            NetworkStats.recordWireBytesSent(out.writerIndex() - outStart);
            return;
        }
        super.encode(ctx, in, out); // 父类已记，禁止双重调用
    }
}
