package io.github.limuqy.mc.hassium.mixin;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerGatewayInfoSender;
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
        PlayerCompressionTracker.setConnected(self);
        // 仅消费 login/config 预握手标记；GatewayPlayerSession 尚未建立前保持原版首包，
        // 避免 CHUNK_HASH 走到 1.20.1 未注册的 custom payload 通道。完整握手后再启用压缩。
        PlayerCompressionTracker.tryEnableOnPlayerJoin(self);
        // M1 bootstrap：玩家物化后经 vanilla 通道下发 gateway_info（connection 未挂时登记待发，
        // 由 MixinMinecraftServer tick 泵补发；仅专用服 + master.enabled，见 CONTRACTS §2）。
        ServerGatewayInfoSender.onPlayerInit(self);
    }

#if MC_VER < MC_1_21_1
    /** 1.20.1：真实服务端不直接向 Hassium 客户端发送区块。 */
    @Inject(method = "trackChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$onTrackChunk(ChunkPos pos, Packet<?> chunkPacket, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        if (PlayerCompressionTracker.isCompressionEnabled(self)) {
            ci.cancel();
        }
    }
#endif
}

