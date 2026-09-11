package io.github.limuqy.mc.hassium.mixin;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 隔离真实服务端到 Hassium 客户端的原版 chunk admission；Hassium 客户端只接收
 * 影子虚拟服务端通过 vanilla connection 推送的区块包。
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player {

    public MixinServerPlayer(Level level, BlockPos pos, float yRot, GameProfile gameProfile) {
#if MC_VER < MC_1_21_6
        super(level, pos, yRot, gameProfile);
#else
        super(level, gameProfile);
#endif
    }

    // review-fix: T7-65: 带描述符精确注入主构造器——1.20.2+ 为四参 (…ClientInformation)，
    // 1.20.1 为三参；避免未来版本新增构造器时 <init> 无描述符命中全部重载重复执行
#if MC_VER < MC_1_21_1
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V", at = @At("TAIL"))
#else
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
#endif
    private void hassium$onPlayerInit(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        // 登录期握手协商结果消费：启用压缩门（原版区块窗口自此压制）+ Play 激活入队。
        io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.onPlayerInit(self);
    }

#if MC_VER < MC_1_21_1
    /** 1.20.1：把 vanilla tracking 产生的首包转为 Hassium 推送任务。 */
    @Inject(method = "trackChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$onTrackChunk(ChunkPos pos, Packet<?> chunkPacket, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        if (PlayerCompressionTracker.isCompressionEnabled(self)) {
            // Pull 模式：服务端停发 chunk_payload，整柱数据由客户端影子 tracking 统一拉取
            if (io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.hasCaps(
                    self.getUUID(), io.github.limuqy.mc.hassium.network.handshake.LoginCaps.PULL_MODE)) {
                ci.cancel();
            }
            // 非 pull 兼容路径：放行原版 trackChunk（SeedRef 直推已退役）
        }
    }

    /**
     * 影子端跳过出生点探测：{@code fudgeSpawnLocation} 会对影子世界做出生点区块
     * 读/生成（{@code getChunk(FULL)}）——该柱可能被 tracking 悬置（worldgen 压制），
     * 在影子主循环线程上等待永不完成的 future = 自我死锁。虚拟玩家位置随后由
     * 真实玩家位置覆盖，出生点探测无意义。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;fudgeSpawnLocation(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void hassium$skipFudgeSpawn(net.minecraft.server.level.ServerPlayer self,
                                        net.minecraft.server.level.ServerLevel level) {
        if (!io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            ((MixinServerPlayer) (Object) self).invokeFudgeSpawnLocation(level);
        }
    }

    @org.spongepowered.asm.mixin.gen.Invoker
    public abstract void invokeFudgeSpawnLocation(net.minecraft.server.level.ServerLevel level);
#endif
}

