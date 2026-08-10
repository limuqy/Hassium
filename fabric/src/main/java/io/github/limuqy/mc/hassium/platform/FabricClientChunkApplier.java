package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.cache.client.ChunkOutOfViewException;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Fabric 平台的客户端区块注入实现
 */
public class FabricClientChunkApplier implements IClientChunkApplier {


    @Override
    public void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf, boolean renderOnly) {
        try {
            // 解压后的数据是完整的数据包内容（坐标 + 区块数据 + 光照数据）
            // 直接用来构造数据包，让数据包构造函数自己读取坐标
#if MC_VER < MC_1_20_5
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(buf);
#else
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC
                    .decode(new net.minecraft.network.RegistryFriendlyByteBuf(buf, level.registryAccess()));
#endif

            // 验证坐标
            if (packet.getX() != pos.x || packet.getZ() != pos.z) {
                Constants.LOG.error("Hassium: Chunk position mismatch! Expected [{}, {}], got [{}, {}]",
                    pos.x, pos.z, packet.getX(), packet.getZ());
                // 抛异常让 ClientChunkHandler.applyChunkData 的 catch 块统一处理 onRenderOnlyMiss
                throw new IllegalStateException("Chunk position mismatch");
            }

            // 使用 Minecraft 的客户端数据包监听器处理
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.client.multiplayer.ClientPacketListener packetListener = mc.getConnection();

            if (packetListener != null) {
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                if (!renderOnly) {
                    // 真实区块到达：apply 前清除可能的 renderOnly 标记（边界替换）
                    mixinAccessor.hassium$removeRenderOnlyChunk(pos.toLong());
                } else {
                    // 超视渲染：apply 前再扩一次半径，覆盖 server 缩半径与本 tick 之间的窗口
                    ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
                }
                // 直接调用原版的处理方法
                packetListener.handleLevelChunkWithLight(packet);

                // replaceWithPacketData 在 storage.inRange 外会静默丢弃；须校验是否真正入缓存
                ClientChunkCache chunkSource = ((ClientLevelAccessor) level).hassium$getChunkSource();
                if (!chunkSource.hasChunk(pos.x, pos.z)) {
                    // 不在此处调用 onRenderOnlyMiss；抛异常让 applyChunkData 的 catch 块统一处理。
                    // 否则 applyChunkData 会继续调用 onRenderOnlyApplied，把未入缓存的 pos 加入
                    // loadedRenderOnly，触发 reconcileMissingLoadedChunks 死循环（虚空根因）。
                    throw new ChunkOutOfViewException(pos);
                }

                if (renderOnly) {
                    // renderOnly 区块：apply 后标记
                    mixinAccessor.hassium$addRenderOnlyChunk(pos.toLong());
                } else {
                    // 真实区块：从 loadedRenderOnly 摘除，防止后续 update 误 enqueue
                    ViewDistanceExtensionService.getInstance().onRealChunkApplied(pos);
                }

                Constants.LOG.debug("Hassium: Fabric applied chunk [{}, {}] from ByteBuf (renderOnly={})",
                    pos.x, pos.z, renderOnly);
            } else {
                Constants.LOG.warn("Hassium: ClientPacketListener is null, cannot apply chunk");
            }

        } catch (ChunkOutOfViewException e) {
            // 预期竞态（异步 apply / 视距窗口），不刷 ERROR 堆栈
            throw e;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] from ByteBuf", pos.x, pos.z, e);
            // 向上抛出，避免调用方误记「Successfully applied」并触发 BE 请求
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

}
