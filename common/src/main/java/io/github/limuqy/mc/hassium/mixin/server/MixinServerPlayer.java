package io.github.limuqy.mc.hassium.mixin.server;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.server.PlayerCompressionTracker;
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
        super(level, gameProfile);
    }

    // review-fix: T7-65: 带描述符精确注入主构造器——1.20.2+ 为四参 (…ClientInformation)，
    // 1.20.1 为三参；避免未来版本新增构造器时 <init> 无描述符命中全部重载重复执行
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
    private void hassium$onPlayerInit(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if ((io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer((ServerPlayer) (Object) this) instanceof io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer)) {
            return;
        }
        // 登录期握手协商结果消费：登记协议/压缩能力 + Play 激活入队；是否压制整柱另查区块接管门。
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.onPlayerInit(self);
    }

}

