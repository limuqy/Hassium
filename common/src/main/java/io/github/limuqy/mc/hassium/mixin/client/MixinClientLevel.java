package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端区块卸载：只作废光桥凭据（§6.0 原版对齐，不拆影子注入表）。 */
@Mixin(ClientLevel.class)
public class MixinClientLevel {
    @Inject(method = "unload", at = @At("HEAD"))
    private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
        ChunkPos pos = chunk.getPos();
        ClientLevel self = (ClientLevel) (Object) this;
        String dim = LevelCompat.getDimensionId(self);
        // 必须用正在卸的 ClientLevel 维 id：切维后 Minecraft.level 已是新世界，
        // 旧世界 unload 会打到错误 DimensionKey，上一维 epoch 残留。
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute
                .onClientChunkUnloaded(pos, dim);
        // 清光桥凭据；若影子仍在 tracking 窗内则入重发队列（§6.0）
        // 必须传入正在卸的 ClientLevel 维 id，与 Light 侧一致
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                .onClientChunkUnloaded(pos, dim);
        hassium$logChunkUnload(pos);
    }

    @Unique
    private static void hassium$logChunkUnload(ChunkPos pos) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_UNLOAD] eventMs={} target=({},{}) player=unavailable",
                    System.currentTimeMillis(), pos.x, pos.z);
            return;
        }
        int playerX = (int) Math.floor(mc.player.getX());
        int playerY = (int) Math.floor(mc.player.getY());
        int playerZ = (int) Math.floor(mc.player.getZ());
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_UNLOAD] eventMs={} target=({},{}) playerBlock=({},{},{}) playerChunk=({},{})",
                System.currentTimeMillis(), pos.x, pos.z, playerX, playerY, playerZ, playerX >> 4, playerZ >> 4);
    }
}
