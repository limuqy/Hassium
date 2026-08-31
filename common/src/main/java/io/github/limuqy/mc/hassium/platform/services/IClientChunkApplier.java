package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

/**
 * 平台抽象：将区块数据注入客户端世界
 */
public interface IClientChunkApplier {


    /**
     * 从 FriendlyByteBuf 格式的数据将区块应用到客户端世界。
     */
    void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf);
}
