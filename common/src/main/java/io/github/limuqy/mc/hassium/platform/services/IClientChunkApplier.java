package io.github.limuqy.mc.hassium.platform.services;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

/**
 * 平台抽象：将区块数据注入客户端世界
 */
public interface IClientChunkApplier {

    /**
     * 将区块数据应用到客户端世界
     *
     * @param level      客户端世界实例
     * @param pos        区块坐标
     * @param nbt        区块NBT数据(已反序列化的完整区块数据)
     * @param renderOnly true=仅渲染不参与逻辑tick, false=完全加载
     */
    void applyToLevel(ClientLevel level, ChunkPos pos, CompoundTag nbt, boolean renderOnly);

    /**
     * 从FriendlyByteBuf格式的数据将区块应用到客户端世界
     *
     * @param level      客户端世界实例
     * @param pos        区块坐标
     * @param buf        FriendlyByteBuf格式的区块数据包数据
     * @param renderOnly true=仅渲染不参与逻辑tick, false=完全加载
     */
    void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf, boolean renderOnly);
}
