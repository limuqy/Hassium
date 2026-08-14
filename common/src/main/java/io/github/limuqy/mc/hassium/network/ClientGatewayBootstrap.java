package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端网关 bootstrap 接收口（M1，CONTRACTS §3）。
 * <p>
 * 由两个 mixin 的 {@code handleCustomPayload} HEAD 注入回调共用（1.20.1 game 包
 * {@code ClientPacketListener} / 1.20.2+ common 包 {@code ClientCommonPacketListenerImpl}）——
 * 独立非 mixin 类承载静态逻辑（Mixin 0.8.7 不允许 Mixin 类非 private 静态方法，
 * 先例 {@code ClientLifecycleHelper}）。
 * <p>
 * 职责：判定 channel == {@code hassium:gateway_info} → 取裸字节 →
 * {@link GatewayInfoCodec#decode(byte[])} → {@link NetworkCore#onGatewayInfo}。
 * 非 gateway_info 通道直接放行（不 cancel、不解析）；解析失败仅告警不崩客户端。
 */
public final class ClientGatewayBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientGatewayBootstrap");

    private ClientGatewayBootstrap() {
        // 工具类，禁止实例化
    }

    /**
     * custom payload 接收回调（handleCustomPayload HEAD）。
     * gateway_info 通道：解析并消费后 {@code cancel} 回调——该通道未注册进 vanilla
     * payload 注册表，不 cancel 会触发 "Unknown custom packet payload" 告警风暴；
     * 非 gateway_info 通道直接放行（不 cancel、不解析）；解析失败仅告警不崩客户端。
     *
     * @param packet 进入 handleCustomPayload 的客户端 custom payload 包
     * @param ci     注入回调（gateway_info 消费后 cancel）
     */
    public static void handleCustomPayload(Packet<?> packet, CallbackInfo ci) {
        try {
            Object payloadId = PacketPayloadCompat.getPayloadId(packet);
            if (payloadId == null || !HassiumPacketIds.GATEWAY_INFO_S2C.equals(payloadId.toString())) {
                return; // 非 gateway_info：vanilla 正常处理，直接放行
            }
            byte[] data = PacketPayloadCompat.extractPayloadData(packet);
            if (data == null) {
                return;
            }
            GatewayInfoCodec.GatewayInfo info = GatewayInfoCodec.decode(data);
            NetworkCore.getInstance().onGatewayInfo(info);
            ci.cancel(); // 本通道已消费：vanilla 无注册表项，取消避免 Unknown payload 告警
        } catch (Throwable t) {
            LOGGER.warn("Hassium: gateway_info payload handling failed", t);
        }
    }
}
