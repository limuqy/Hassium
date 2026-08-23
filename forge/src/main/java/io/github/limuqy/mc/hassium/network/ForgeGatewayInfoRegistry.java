package io.github.limuqy.mc.hassium.network;

#if MC_VER >= MC_1_21_1
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.ForgePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 50+（MC &ge;1.21.1）gateway_info S2C vanilla 直发路径注册。
 * <p>
 * 与 NeoForge {@code NeoForgeNetworkManager#registerPayloads} 的无条件 gateway_info 注册对齐：
 * 服务端 {@code ServerGatewayInfoSender} 经 {@code PacketPayloadCompat.createClientboundPayload}
 * 走 vanilla 通道直发 {@code GATEWAY_INFO_S2C}，不注册则编码回退 DiscardedPayload codec
 * （数据静默丢弃）。但 Forge 与 NeoForge 机制不同（无 RegisterPayloadHandlersEvent / PayloadRegistrar）：
 * <ul>
 *   <li><b>编码</b>：{@code ClientboundCustomPayloadPacket} 对未知 id 回退
 *       {@code ForgeHooks.getCustomPayloadCodec}——id 已注册到任一 channel 时返回的 codec 编码器
 *       静态类型为 {@link ForgePayload}，common 直发的 RawCustomPayload 实例会 checkcast CCE。
 *       因此除注册外还需出站改写：把 common RawCustomPayload 包成同字节流的 ForgePayload。</li>
 *   <li><b>分发</b>：客户端 {@code ClientCommonPacketListenerImpl.handleCustomPayload} 经
 *       {@code ForgeHooks.onCustomPayload} → NetworkRegistry 按 id 找到本 channel → 本类 consumer。</li>
 * </ul>
 * 无条件注册（先于 master.enabled 守卫）：canSend（dedicated + master.enabled）与注册状态可能脱钩，
 * 服务端不发送时注册无副作用。
 */
final class ForgeGatewayInfoRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final ResourceLocation RL = ResourceLocationCompat.create(HassiumPacketIds.GATEWAY_INFO_S2C);
    /** 出站改写 handler 名（per-connection pipeline 内唯一） */
    private static final String REWRITE_HANDLER = "hassium_raw_payload_rewrite";

    // review-fix: T10-10 同口径：commonSetup 主线程赋值、netty 线程读取 → volatile 保证可见性
    private static volatile Channel<net.minecraft.network.protocol.common.custom.CustomPacketPayload> CHANNEL;

    private ForgeGatewayInfoRegistry() {
    }

    /**
     * 注册 gateway_info S2C payload channel + 出站 RawPayload 改写。幂等；
     * 由 {@code ForgeNetworkManager#registerChannels} 在版本段内调用（先于 master.enabled 守卫）。
     */
    static void init() {
        if (CHANNEL != null) {
            return;
        }
        CHANNEL = ChannelBuilder
                .named(RL)
                .optional()
                .connectionHandler(ForgeGatewayInfoRegistry::onConnectionStart)
                .payloadChannel()
                    .any()
                        .clientbound()
                            .add(PacketPayloadCompat.payloadType(RL),
                                    PacketPayloadCompat.rawPayloadCodec(RL),
                                    ForgeGatewayInfoRegistry::onGatewayInfo)
                    .build();
        LOGGER.info("Hassium: Registered gateway_info S2C payload channel (vanilla direct-send path)");
    }

    /**
     * 出站改写：common compat 直发的 {@code PacketPayloadCompat.RawCustomPayload} 在 Forge 的
     * fallback codec 下必 CCE（编码器静态类型为 ForgePayload），改包为等字节流 ForgePayload。
     */
    private static void onConnectionStart(Connection connection) {
        try {
            connection.channel().pipeline().addLast(REWRITE_HANDLER, new RawPayloadRewriter());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to install raw payload rewriter", e);
        }
    }

    private static void onGatewayInfo(PacketPayloadCompat.RawCustomPayload payload, CustomPayloadEvent.Context ctx) {
        byte[] data = payload.data();
        ctx.enqueueWork(() -> {
            try {
                GatewayInfoCodec.GatewayInfo info = GatewayInfoCodec.decode(data);
                NetworkCore.getInstance().onGatewayInfo(info);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle gateway_info", e);
            }
        });
    }

    /**
     * 出站 handler 置于 pipeline 尾部（outbound 自尾向头遍历，先于 packet encoder 看到 Packet 对象），
     * 不依赖 encoder handler 名（encoder/decoder 名称随 configuration/play 重配置切换）。
     */
    private static final class RawPayloadRewriter extends io.netty.channel.ChannelOutboundHandlerAdapter {
        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg,
                io.netty.channel.ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundCustomPayloadPacket packet
                    && packet.payload() instanceof PacketPayloadCompat.RawCustomPayload raw) {
                msg = new ClientboundCustomPayloadPacket(
                        // Consumer 变体：免包装 buf 分配；create(ResourceLocation, ByteBuf) 无此重载
                        ForgePayload.create(raw.id(), out -> out.writeBytes(raw.data())));
            }
            super.write(ctx, msg, promise);
        }
    }
}
#endif
