package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshakeManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录期能力握手——服务端 1.20.1 login query 载体（1.20.2+ 走配置阶段 PreHandshakePayload）。
 *
 * <p><b>统一两态（专用服 / LAN 远程一致）</b>：
 * <ul>
 *   <li>本机 memory / 未开网络面：不发 query，原版握手立刻 placeNewPlayer</li>
 *   <li>Hassium 客户端：有效应答 → 协商 caps</li>
 *   <li>原版客户端：空应答 / 超时 → 原版</li>
 * </ul>
 *
 * <p><b>握手完成前不推区块</b>：在发 GameProfile / placeNewPlayer 之前等应答
 * （默认 5s 超时）。compression 已在 wait 前 flush，Game 期原版压缩不受影响。
 * 这消除了「先 placeNewPlayer 再收应答」的首连竞态（ClassCastException / 半开 pull）。
 */
#if MC_VER < MC_1_21_1
@org.spongepowered.asm.mixin.Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow
    @Final
    private Connection connection;

    @Shadow
    private com.mojang.authlib.GameProfile gameProfile;

    @Shadow
    @Final
    MinecraftServer server;

    /** 等待 Hassium 应答；true 时不发 GameProfile。 */
    @Unique
    private boolean hassium$deferPlace;

    @Unique
    private long hassium$queryDeadlineMs;

    @Unique
    private static final long HASSIUM_QUERY_TIMEOUT_MS = 3_000L;

    @Inject(method = "handleAcceptedLogin()V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/network/protocol/login/ClientboundGameProfilePacket;<init>(Lcom/mojang/authlib/GameProfile;)V"),
            cancellable = true)
    private void hassium$maybeDeferPlace(CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.ServerNetworkGate.shouldSendLoginHandshake(connection)) {
            return;
        }
        if (LoginHandshakeManager.isAnswerResolved(connection)) {
            return; // 应答已到：继续发 GameProfile + placeNewPlayer
        }
        if (!LoginHandshakeManager.isAwaitingAnswer(connection)) {
            // 首次进入：compression 已发，此刻发 query 并暂缓 place
            int serverCaps = LoginCaps.buildServerCaps();
            LoginHandshakeManager.onQuerySent(connection, serverCaps);
            FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
            try {
                connection.send(new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(
                        LoginHandshake.TRANSACTION_ID,
                        new net.minecraft.resources.ResourceLocation(Constants.MOD_ID, LoginHandshake.HELLO_CHANNEL),
                        body));
                Constants.LOG.info("[LOGIN_HELLO] query sent (serverCaps=0x{}); deferring placeNewPlayer",
                        Integer.toHexString(serverCaps));
            } catch (Exception e) {
                body.release();
                Constants.LOG.warn("Hassium: Failed to send login hello query", e);
                LoginHandshakeManager.markAnswerResolvedVanilla(connection, "query send failed");
                return;
            }
        }
        hassium$deferPlace = true;
        hassium$queryDeadlineMs = System.currentTimeMillis() + HASSIUM_QUERY_TIMEOUT_MS;
        ci.cancel();
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void hassium$pollLoginHandshake(CallbackInfo ci) {
        if (!hassium$deferPlace) {
            return;
        }
        if (LoginHandshakeManager.isAnswerResolved(connection)) {
            hassium$deferPlace = false;
            Constants.LOG.info("[LOGIN_HELLO] answer resolved; completing login");
            hassium$finishPlaceNewPlayer();
            return;
        }
        if (System.currentTimeMillis() >= hassium$queryDeadlineMs) {
            hassium$deferPlace = false;
            LoginHandshakeManager.markAnswerResolvedVanilla(connection, "timeout");
            Constants.LOG.info("[LOGIN_HELLO] answer timeout; completing login as vanilla");
            hassium$finishPlaceNewPlayer();
        }
    }

    /** 补发原版 handleAcceptedLogin 在 GameProfile 之后的剩余步骤。 */
    @Unique
    private void hassium$finishPlaceNewPlayer() {
        try {
            connection.send(new net.minecraft.network.protocol.login.ClientboundGameProfilePacket(gameProfile));
            net.minecraft.server.level.ServerPlayer toPlace =
                    server.getPlayerList().getPlayerForLogin(gameProfile);
            server.getPlayerList().placeNewPlayer(connection, toPlace);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to complete login after handshake", e);
            try {
                connection.disconnect(net.minecraft.network.chat.Component.translatable(
                        "multiplayer.disconnect.invalid_player_data"));
            } catch (Exception ignored) {
            }
        }
    }

    @Inject(method = "handleCustomQueryPacket(Lnet/minecraft/network/protocol/login/ServerboundCustomQueryPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$onLoginQueryAnswer(
            net.minecraft.network.protocol.login.ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        if (packet.getTransactionId() != LoginHandshake.TRANSACTION_ID) {
            return;
        }
        LoginHandshakeManager.handleAnswer(this, connection, packet.getData());
        ci.cancel();
    }
}
#else
@org.spongepowered.asm.mixin.Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public class MixinServerLoginPacketListenerImpl {
}
#endif
