package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshakeManager;
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
@org.spongepowered.asm.mixin.Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public class MixinServerLoginPacketListenerImpl {
}
