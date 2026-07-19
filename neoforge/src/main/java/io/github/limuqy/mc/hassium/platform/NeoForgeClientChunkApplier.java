package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * NeoForge 平台的客户端区块注入实现
 */
public class NeoForgeClientChunkApplier implements IClientChunkApplier {

    @Override
    public void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf, boolean renderOnly) {
        try {
#if MC_VER < MC_1_20_5
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(buf);
#else
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC
                    .decode(new net.minecraft.network.RegistryFriendlyByteBuf(buf, level.registryAccess()));
#endif

            if (packet.getX() != pos.x || packet.getZ() != pos.z) {
                Constants.LOG.error("Hassium: Chunk position mismatch! Expected [{}, {}], got [{}, {}]",
                    pos.x, pos.z, packet.getX(), packet.getZ());
                if (renderOnly) {
                    ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
                }
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener packetListener = mc.getConnection();

            if (packetListener != null) {
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                if (!renderOnly) {
                    // 真实区块到达：apply 前清除可能的 renderOnly 标记（边界替换）
                    mixinAccessor.hassium$removeRenderOnlyChunk(pos);
                } else {
                    ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
                }
                packetListener.handleLevelChunkWithLight(packet);

                ClientChunkCache chunkSource = ((ClientLevelAccessor) level).hassium$getChunkSource();
                if (!chunkSource.hasChunk(pos.x, pos.z)) {
                    if (renderOnly) {
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

                Constants.LOG.debug("Hassium: NeoForge applied chunk [{}, {}] from ByteBuf (renderOnly={})",
                    pos.x, pos.z, renderOnly);
            } else {
                Constants.LOG.warn("Hassium: ClientPacketListener is null, cannot apply chunk");
            }

        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] from ByteBuf", pos.x, pos.z, e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public void applyToLevel(ClientLevel level, ChunkPos pos, CompoundTag nbt, boolean renderOnly) {
        try {
            LevelChunk chunk = new LevelChunk(level, pos);

            ClientLevelAccessor accessor = (ClientLevelAccessor) level;
            ClientChunkCache chunkSource = accessor.hassium$getChunkSource();

            injectChunkViaReflection(chunkSource, pos, chunk);

            chunk.setLoaded(true);

            if (renderOnly) {
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                mixinAccessor.hassium$addRenderOnlyChunk(pos);
            }

            Constants.LOG.debug("Hassium: NeoForge applied chunk [{}, {}] (renderOnly={}) [PLACEHOLDER]",
                pos.x, pos.z, renderOnly);

        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] to client level", pos.x, pos.z, e);
        }
    }

    private void injectChunkViaReflection(ClientChunkCache chunkSource, ChunkPos pos, LevelChunk chunk) {
        try {
            java.lang.reflect.Field storageField = ClientChunkCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(chunkSource);

            if (storage != null) {
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
