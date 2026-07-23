package io.github.limuqy.mc.hassium.network;

import io.netty.util.AttributeKey;

/**
 * Netty Channel 属性键：聚合包发送前标记下一帧跳过管线级 ZSTD。
 * <p>
 * 注意：聚合包经 Packet 编码后原 ByteBuf 标记会丢失，故使用 Channel 属性而非 ByteBuf.attr。
 */
public final class HassiumPipelineAttributes {
    private HassiumPipelineAttributes() {}

    /**
     * 为 true 时，{@link SkipAwareZstdEncoder} 对下一帧写入未压缩 framing（VarInt(0)+raw），
     * 读后清除。聚合包内部已有字典 ZSTD，避免双重压缩。
     */
    public static final AttributeKey<Boolean> SKIP_PIPELINE_COMPRESSION =
            AttributeKey.valueOf("hassium:skip_pipeline_compression");
}
