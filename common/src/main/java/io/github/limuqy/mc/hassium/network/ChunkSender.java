package io.github.limuqy.mc.hassium.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 区块发送接口（平台无关）
 */
public interface ChunkSender {

    /**
     * 发送压缩的区块数据
     */
    void sendCompressedChunk(ServerPlayer player, ChunkCompressionHandler.CompressedChunkData compressed);

    /**
     * 设置全局的区块发送器
     */
    static void setInstance(ChunkSender sender) {
        ChunkSenderHolder.instance = sender;
    }

    /**
     * 获取全局的区块发送器
     */
    static ChunkSender getInstance() {
        return ChunkSenderHolder.instance;
    }
}

class ChunkSenderHolder {
    static volatile ChunkSender instance;
}
