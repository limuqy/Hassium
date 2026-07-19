package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Fabric 平台的客户端区块注入实现
 */
public class FabricClientChunkApplier implements IClientChunkApplier {

    @Override
    public void applyToLevel(ClientLevel level, ChunkPos pos, CompoundTag nbt, boolean renderOnly) {
        try {
            // TODO: 实现完整的区块数据恢复
            // 当前创建空区块作为占位，实际的 NBT 数据恢复需要：
            // 1. 手动从 NBT 恢复 sections
            // 2. 恢复 block entities
            // 3. 恢复 heightmaps
            // 这需要复制 ChunkSerializer 的大部分逻辑

            LevelChunk chunk = new LevelChunk(level, pos);

            // 通过 accessor 获取 ClientChunkCache 并注入区块
            ClientLevelAccessor accessor = (ClientLevelAccessor) level;
            ClientChunkCache chunkSource = accessor.hassium$getChunkSource();

            // 使用反射调用内部方法注入区块
            injectChunkViaReflection(chunkSource, pos, chunk);

            // 标记区块为已加载状态
            chunk.setLoaded(true);

            // 如果是仅渲染区块，标记它
            if (renderOnly) {
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                mixinAccessor.hassium$addRenderOnlyChunk(pos);
            }

            Constants.LOG.debug("Hassium: Fabric applied chunk [{}, {}] (renderOnly={}) [PLACEHOLDER]",
                pos.x, pos.z, renderOnly);

        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] to client level", pos.x, pos.z, e);
        }
    }

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
                if (renderOnly) {
                    ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
                }
                return;
            }

            // 使用 Minecraft 的客户端数据包监听器处理
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.client.multiplayer.ClientPacketListener packetListener = mc.getConnection();

            if (packetListener != null) {
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                if (!renderOnly) {
                    // 真实区块到达：apply 前清除可能的 renderOnly 标记（边界替换）
                    mixinAccessor.hassium$removeRenderOnlyChunk(pos);
                } else {
                    // 超视渲染：apply 前再扩一次半径，覆盖 server 缩半径与本 tick 之间的窗口
                    ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
                }
                // 直接调用原版的处理方法
                packetListener.handleLevelChunkWithLight(packet);

                // replaceWithPacketData 在 storage.inRange 外会静默丢弃；须校验是否真正入缓存
                ClientChunkCache chunkSource = ((ClientLevelAccessor) level).hassium$getChunkSource();
                if (!chunkSource.hasChunk(pos.x, pos.z)) {
                    if (renderOnly) {
                        // 仍 out-of-range：软 miss + 退避，禁止 ERROR 刷屏
                        ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
                        Constants.LOG.debug(
                                "Hassium: OVD apply skipped (out of view range) [{}, {}]", pos.x, pos.z);
                        return;
                    }
                    throw new IllegalStateException(
                            "Chunk apply ignored by ClientChunkCache (out of view range): " + pos);
                }

                if (renderOnly) {
                    // renderOnly 区块：apply 后标记
                    mixinAccessor.hassium$addRenderOnlyChunk(pos);
                } else {
                    // 真实区块：从 loadedRenderOnly 摘除，防止后续 update 误 enqueue
                    ViewDistanceExtensionService.getInstance().onRealChunkApplied(pos);
                }

                Constants.LOG.debug("Hassium: Fabric applied chunk [{}, {}] from ByteBuf (renderOnly={})",
                    pos.x, pos.z, renderOnly);
            } else {
                Constants.LOG.warn("Hassium: ClientPacketListener is null, cannot apply chunk");
            }

        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] from ByteBuf", pos.x, pos.z, e);
            // 向上抛出，避免调用方误记「Successfully applied」并触发 BE 请求
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    private void injectChunkViaReflection(ClientChunkCache chunkSource, ChunkPos pos, LevelChunk chunk) {
        try {
            // ClientChunkCache 的内部 Storage 类有 replace 方法
            // 我们通过反射访问它
            java.lang.reflect.Field storageField = ClientChunkCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(chunkSource);

            if (storage != null) {
                // 调用 storage.replace 方法
                java.lang.reflect.Method replaceMethod = storage.getClass().getDeclaredMethod(
                        "replace", int.class, int.class, LevelChunk.class);
                replaceMethod.setAccessible(true);
                replaceMethod.invoke(storage, pos.x, pos.z, chunk);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to inject chunk via reflection", e);
        }
    }
}
