package io.github.limuqy.mc.hassium.network.core.viafabric;

import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ViaFabric 协议转换桥（T9）：把网关 outbound 解码出的原版 {@link Packet} 先经
 * ViaFabric 的取包处（pipeline {@code via-decoder}，{@code ViaDecodeHandler}）做协议转换，
 * 再交回注入器进 handler（REQ A8）。
 *
 * <p><b>机制</b>：ViaFabric/ViaForge/ViaFabricPlus 客户端在每条原版 Connection pipeline 上挂
 * {@code via-encoder}/{@code via-decoder} 两个 handler（类
 * {@code com.viaversion.viaversion.protocol.ViaEncodeHandler/ViaDecodeHandler}），decode 链 =
 * 服务端协议字节 → 客户端当前版本字节。本桥：
 * <ol>
 *   <li>从客户端原版 Connection pipeline 定位 live {@code via-decoder}（按名字
 *       {@code via-decoder} 或类名 {@code ViaDecodeHandler} 匹配，映射无关）；</li>
 *   <li>反射取出其持有的 {@code UserConnection}（字段按类型匹配，映射无关）；</li>
 *   <li>用同一 {@code UserConnection} 新建一个 {@code ViaDecodeHandler} 实例，装进
 *       {@link EmbeddedChannel} 作为转换通道——不移动 live handler，不碰原版 pipeline；</li>
 *   <li>{@link #translatePacket}：Packet → 完整线字节（含协议 ID，{@link PacketCodecCompat#serializePacketFull}）
 *       → 写进转换通道 → 读出转换后字节 → {@link PacketCodecCompat#deserializePacketFull} 还原 Packet。</li>
 * </ol>
 *
 * <p><b>失败安全</b>：ViaFabric 内部 API 跨版本不确定（ViaVersion 4.x/5.x），任何一步
 * （探测/反射/转换/编解码）失败或 ViaFabric 吞包 → 返回 null，调用方退回原包直接注入，不崩。
 * 桥构建失败会在会话内重试（连接建立晚于类加载）；转换期失败记录一次后整会话降级。
 *
 * <p><b>版本语义</b>：编码侧用客户端当前版本编解码器。主控与客户端同版本（Hassium 默认部署）
 * 时 ViaFabric decode 链为透传，转换正确且保持 ViaFabric 连接状态一致；跨版本主控（服务端
 * 协议 ≠ 当前版本）时编码侧需用服务端版本编解码器（或直接把网关原始 payload 字节喂
 * {@link #translateBytes}），见 work/T9-TASK.md 开放点。
 *
 * <p>线程：由网关 outbound event loop 线程调用（与 ViaVersion 设计一致）；原版 shell pipeline
 * 空闲无并发。
 */
final class ViaDecodeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ViaFabricBridge");

    /** ViaFabric 系列客户端 decode handler 的 pipeline 名（ViaFabric/ViaForge/ViaFabricPlus 共用）。 */
    private static final String VIA_DECODER_PIPELINE_NAME = "via-decoder";
    private static final String VIA_DECODER_CLASS = "com.viaversion.viaversion.protocol.ViaDecodeHandler";
    private static final String USER_CONNECTION_IFACE = "com.viaversion.viaversion.api.connection.UserConnection";

    private final EmbeddedChannel transformChannel;
    private final Connection clientConnection;
    private volatile boolean degraded;

    private ViaDecodeBridge(EmbeddedChannel transformChannel, Connection clientConnection) {
        this.transformChannel = transformChannel;
        this.clientConnection = clientConnection;
    }

    /**
     * 构建转换桥：定位 live via-decoder → 提取 UserConnection → 新建 ViaDecodeHandler 装
     * EmbeddedChannel。任何失败返回 null（调用方直接注入）。
     */
    static ViaDecodeBridge tryBuild() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                LOGGER.debug("Hassium: ViaFabric bridge build skipped (no client connection)");
                return null;
            }
            Connection connection = mc.getConnection().getConnection();
            if (connection == null) {
                return null;
            }
            Channel channel = (Channel) ReflectionCompat.getFieldByType(connection, Channel.class, false);
            if (channel == null) {
                return null;
            }
            ChannelHandler liveDecoder = findDecodeHandler(channel.pipeline());
            if (liveDecoder == null) {
                LOGGER.info("Hassium: ViaFabric active but via-decoder not in vanilla pipeline; "
                        + "fall back to direct injection");
                return null;
            }
            Class<?> userConnectionIface = Class.forName(USER_CONNECTION_IFACE);
            Object userConnection = ReflectionCompat.getFieldByType(liveDecoder, userConnectionIface, true);
            if (userConnection == null) {
                return null;
            }
            Class<?> handlerClass = Class.forName(VIA_DECODER_CLASS);
            ChannelHandler freshDecoder = (ChannelHandler) handlerClass.getConstructor(userConnectionIface)
                    .newInstance(userConnection);
            EmbeddedChannel embedded = new EmbeddedChannel(freshDecoder);
            LOGGER.info("Hassium: ViaFabric decode bridge installed (live {} -> fresh {})",
                    liveDecoder.getClass().getName(), freshDecoder.getClass().getName());
            return new ViaDecodeBridge(embedded, connection);
        } catch (Throwable t) {
            LOGGER.warn("Hassium: ViaFabric bridge build failed, fall back to direct injection", t);
            return null;
        }
    }

    /**
     * 完整包字节（含协议 ID）→ ViaFabric 转换后字节。失败/被吞 → null；不消费输入缓冲；
     * 返回缓冲所有权归调用方（用完 release）。
     */
    ByteBuf translateBytes(ByteBuf fullPacket) {
        ByteBuf copy = Unpooled.copiedBuffer(fullPacket);
        try {
            if (!transformChannel.writeInbound(copy)) {
                return null;
            }
            transformChannel.checkException();
            Object out = transformChannel.readInbound();
            if (out instanceof ByteBuf buf && buf.isReadable()) {
                return buf;
            }
            drainInbound();
            return null;
        } catch (Throwable t) {
            drainInbound();
            degradeOnce(t);
            return null;
        }
    }

    /**
     * Packet → 当前版本线字节 → ViaFabric decode 链 → 转换后字节 → Packet。
     * 任何失败返回 null（调用方退回原包直接注入）。
     */
    Packet<?> translatePacket(Packet<?> packet) {
        if (degraded) {
            return null;
        }
        try {
            byte[] full = PacketCodecCompat.serializePacketFull(
                    packet, PacketFlow.CLIENTBOUND, registryAccess());
            if (full == null || full.length == 0) {
                return null;
            }
            ByteBuf wire = Unpooled.wrappedBuffer(full);
            try {
                ByteBuf translated = translateBytes(wire);
                if (translated == null) {
                    return null;
                }
                try {
                    byte[] bytes = new byte[translated.readableBytes()];
                    translated.readBytes(bytes);
                    return PacketCodecCompat.deserializePacketFull(
                            PacketFlow.CLIENTBOUND, bytes, registryAccess());
                } finally {
                    translated.release();
                }
            } finally {
                wire.release();
            }
        } catch (Throwable t) {
            degradeOnce(t);
            return null;
        }
    }

    private RegistryAccess registryAccess() {
        return PacketCodecCompat.resolveRegistryAccess(clientConnection);
    }

    /** 排空转换通道残留入站（引用计数归还）。 */
    private void drainInbound() {
        Object leftover;
        while ((leftover = transformChannel.readInbound()) != null) {
            if (leftover instanceof ByteBuf buf) {
                buf.release();
            }
        }
    }

    private void degradeOnce(Throwable t) {
        if (!degraded) {
            degraded = true;
            LOGGER.error("Hassium: ViaFabric translation failed, degraded to direct injection "
                    + "for this session", t);
        }
    }

    /** 按 pipeline 名或类名定位 via-decoder（映射无关）。 */
    private static ChannelHandler findDecodeHandler(ChannelPipeline pipeline) {
        ChannelHandler byName = pipeline.get(VIA_DECODER_PIPELINE_NAME);
        if (byName != null) {
            return byName;
        }
        for (java.util.Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (VIA_DECODER_CLASS.equals(entry.getValue().getClass().getName())
                    || entry.getValue().getClass().getSimpleName().equals("ViaDecodeHandler")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
