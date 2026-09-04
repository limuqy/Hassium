package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshakeManager;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录期能力握手——服务端 1.20.1 login query 载体（1.20.2+ 走配置阶段
 * PreHandshakePayload，见各 loader 注册；原版 codec 对未知 login query 应答体
 * 逐字节丢弃，登录期无法携带能力体）。
 *
 * <p><b>query 发送时机（帧化竞态修复）</b>：query 必须在 vanilla
 * {@code handleAcceptedLogin} 的 LoginCompression flush <b>之后</b>、GameProfile 之前发出。
 * 曾在 {@code handleHello} HEAD 发送——此刻客户端尚未装压缩编码器，应答是裸包；若服务端
 * 已 flush LoginCompression 并装上解码器（flush 回调即刻安装），裸应答的首个 varint
 * （packetId 0x02）被当作解压尺寸 → {@code Badly compressed packet - size of 2 is below
 * server threshold of 256} 断连（冒烟 pullagg2 实证，负载下偶发）。业界同题两解：
 * Fabric API 在首个 query tick 先发 compression（源码注释 "so clients receive compressed
 * login queries"），Forge 用 NEGOTIATING 状态把 LoginCompression 推迟到应答收齐——共同
 * 原则是 query 往返与压缩切换不重叠，本修复取 Fabric 式先行。注入点选在
 * {@code ClientboundGameProfilePacket} 构造之前：事件循环 FIFO 保证 query 写入晚于
 * 解码器安装；客户端 listener 仍是握手类（GameProfile 未发）；单机/内网
 * （isMemoryConnection 跳过 compression）分支同样覆盖。
 *
 * <p>vanilla 客户端恒回空应答 → 原版路径，不依赖超时。应答在
 * {@code handleCustomQueryPacket} HEAD 解析（仅消费本模组 transactionId）。
 * <p>仅专用服 + master.enabled 时发送（单人/局域网/影子端不发）。
 *
 * <p><b>T2-91 历史注记</b>：旧实现曾以「应答后向 server 线程投递受 state 守卫的
 * handleAcceptedLogin 兜底」对抗 C2 LICM 导致的 slow_login 陈旧读。query 挪至
 * handleAcceptedLogin 后：accept 不再依赖应答（query 在 state=ACCEPTED 之后才发出），
 * 兜底守卫（READY_TO_ACCEPT）恒不成立；且 Hello→accept 之间已无任何模组代码，
 * 原触发面消失，兜底随之移除。
 */
#if MC_VER < MC_1_21_1
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleAcceptedLogin()V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/network/protocol/login/ClientboundGameProfilePacket;<init>(Lcom/mojang/authlib/GameProfile;)V"))
    private void hassium$onAcceptedSendQuery(CallbackInfo ci) {
        if (!RuntimeServerContext.isDedicatedServerContext()
                || !HassiumConfigService.getInstance().isMasterEnabled()) {
            return;
        }
        if (!connection.isConnected()) {
            return;
        }
        int serverCaps = LoginCaps.buildServerCaps();
        LoginHandshakeManager.onQuerySent(connection, serverCaps);
        // 1.20.1 query 体可读，但客户端不消费服务端声明位（协商结果由 Play 期
        // play_init_s2c 下发），body 留空最小化；空体不影响 vanilla 客户端应答行为。
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        try {
            connection.send(new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(
                    LoginHandshake.TRANSACTION_ID,
                    new net.minecraft.resources.ResourceLocation(Constants.MOD_ID, LoginHandshake.HELLO_CHANNEL),
                    body));
            DebugLogger.info(LogType.NETWORK, "[LOGIN_HELLO] query sent post-compression (serverCaps=0x{})",
                    Integer.toHexString(serverCaps));
        } catch (Exception e) {
            body.release();
            Constants.LOG.warn("Hassium: Failed to send login hello query", e);
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
/**
 * 1.20.2+ 无 login query 载体（原版 codec 丢弃未知应答体；协商走配置阶段 payload）。
 * 保留空壳 mixin 注册位，避免 mixins.json 分版本维护。
 */
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public class MixinServerLoginPacketListenerImpl {
}
#endif
