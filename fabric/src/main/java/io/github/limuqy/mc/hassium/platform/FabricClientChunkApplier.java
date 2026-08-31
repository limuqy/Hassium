package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/** Fabric 平台的客户端区块注入实现。 */
public class FabricClientChunkApplier implements IClientChunkApplier {
    @Override
    public void applyToLevelFromByteBuf(ClientLevel level, ChunkPos pos, FriendlyByteBuf buf) {
        try {
#if MC_VER < MC_1_21_1
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(buf);
#else
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC
                    .decode(new net.minecraft.network.RegistryFriendlyByteBuf(buf, level.registryAccess()));
#endif
            if (packet.getX() != pos.x || packet.getZ() != pos.z) {
                throw new IllegalStateException("Chunk position mismatch");
            }
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null || listener.getLevel() == null) {
                return;
            }
            listener.handleLevelChunkWithLight(packet);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply chunk [{}, {}] from ByteBuf", pos.x, pos.z, e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }
}
