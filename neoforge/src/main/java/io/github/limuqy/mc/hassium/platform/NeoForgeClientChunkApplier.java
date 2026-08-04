package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ChunkOutOfViewException;
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

    /**
     * 断连窗口（ClientPacketListener.level 已置 null）的一次性说明日志标志，避免每个 chunk 刷屏。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean listenerLevelTornDownLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf, boolean renderOnly) {
        try {
#if MC_VER < MC_1_20_5
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(buf);
#else
#if MC_VER >= MC_1_21_11
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC
                    .decode(new net.minecraft.network.RegistryFriendlyByteBuf(buf, level.registryAccess(),
                            net.neoforged.neoforge.network.connection.ConnectionType.OTHER));
#else
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC
                    .decode(new net.minecraft.network.RegistryFriendlyByteBuf(buf, level.registryAccess()));
#endif
#endif

            if (packet.getX() != pos.x || packet.getZ() != pos.z) {
                Constants.LOG.error("Hassium: Chunk position mismatch! Expected [{}, {}], got [{}, {}]",
                    pos.x, pos.z, packet.getX(), packet.getZ());
                // 抛异常让 ClientChunkHandler.applyChunkData 的 catch 块统一处理 onRenderOnlyMiss
                throw new IllegalStateException("Chunk position mismatch");
            }

            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener packetListener = mc.getConnection();

            if (packetListener != null) {
                // 断连时序防护（neoforge 1.20.2+ NPE 根因）：Minecraft 的断开流程会先执行
                // connection.close()（ClientPacketListener.level=null），而 mc.level 尚未清空，
                // 断连清理 / tick drain 会在这个窗口继续 apply 缓存区块。此时
                // handleLevelChunkWithLight → updateLevelChunk 内 this.level 为 null 直接 NPE。
                // level 已销毁时这些 chunk 无法进入世界，跳过 apply 是正确语义（非降级）。
                if (packetListener.getLevel() == null) {
                    if (listenerLevelTornDownLogged.compareAndSet(false, true)) {
                        Constants.LOG.info("Hassium: Skipping chunk apply - ClientPacketListener level already torn down (disconnect window)");
                    }
                    // 抛预期竞态异常，让 applyChunkData 的 catch 块统一返回失败（不刷 ERROR 堆栈）
                    throw new ChunkOutOfViewException(pos);
                }
                IClientLevelExtension mixinAccessor = (IClientLevelExtension) level;
                if (!renderOnly) {
                    // 真实区块到达：apply 前清除可能的 renderOnly 标记（边界替换）
                    mixinAccessor.hassium$removeRenderOnlyChunk(pos.toLong());
                } else {
                    ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
                }
                packetListener.handleLevelChunkWithLight(packet);

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

                Constants.LOG.debug("Hassium: NeoForge applied chunk [{}, {}] from ByteBuf (renderOnly={})",
                    pos.x, pos.z, renderOnly);
            } else {
                Constants.LOG.warn("Hassium: ClientPacketListener is null, cannot apply chunk");
            }

        } catch (ChunkOutOfViewException e) {
            // 预期竞态（异步 apply / 视距窗口），不刷 ERROR 堆栈
            throw e;
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
                mixinAccessor.hassium$addRenderOnlyChunk(pos.toLong());
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
