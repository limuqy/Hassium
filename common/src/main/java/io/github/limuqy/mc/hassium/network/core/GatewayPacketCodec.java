package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关帧 payload 编解码（T5 定义；主控侧 T11 对称实现）。
 *
 * <p><b>payload 统一格式</b>（PACKET_C2S / PACKET_S2C / LOGIN_C2S / LOGIN_S2C 共用）：
 * <pre>
 *   [varint kind] [kind 负载]
 *   kind=0 VANILLA : [varint vanillaId] [body]   — 完整原版包编码（含协议包 ID VarInt）
 *   kind=1 HASSIUM : [varint hassiumSubId] [body] — Hassium 业务包（本波仅 S2C）
 * </pre>
 *
 * <p>原版包编解码跨版本（MCP mojmap 源码核实）：
 * <ul>
 *   <li>&lt;1.20.5：{@code ConnectionProtocol.PLAY/LOGIN.getPacketId(flow, packet)}（1.20.1）/
 *       {@code codec(flow).packetId(packet)}（1.20.2–1.20.4）+ {@code packet.write(buf)}；
 *       解码 {@code createPacket(flow, id, buf)}（1.20.1）/ {@code codec(flow).createPacket(id, buf)}。</li>
 *   <li>1.20.5+：PLAY 走 {@code GameProtocols.*TEMPLATE.bind(RegistryFriendlyByteBuf.decorator)}，
 *       LOGIN 走 {@code LoginProtocols.CLIENTBOUND/SERVERBOUND.codec()}（FriendlyByteBuf），
 *       StreamCodec 完整 encode（含 id）/ decode（先写 id 再 body）。与
 *       {@link PacketCodecCompat#deserializeClientbound} 同款 cast 模式。</li>
 * </ul>
 *
 * <p>线程：本类无状态，任意线程可调；payload 所有权由调用方管理。
 */
public final class GatewayPacketCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayPacketCodec");

    /** 帧 payload 首字节 kind：原版包（id = 协议数字 ID）。 */
    public static final int KIND_VANILLA = 0;
    /** 帧 payload 首字节 kind：Hassium 业务包（id = {@link HassiumSub}）。 */
    public static final int KIND_HASSIUM = 1;

    /** 网关转发用的协议面（决定编解码表与包 ID 空间）。 */
    public enum GatewayProtocol {
        /** PLAY 阶段（PACKET_C2S / PACKET_S2C）。 */
        PLAY,
        /** 登录阶段（LOGIN_C2S / LOGIN_S2C）。 */
        LOGIN,
        /** 配置阶段（CONFIG_C2S / CONFIG_S2C，1.20.2+；1.20.1 无此协议面，调用方必须不触发）。 */
        CONFIG
    }

    /** Hassium 业务 S2C 包子类型注册表（append-only；与主控侧 T11 协商固定）。 */
    public enum HassiumSub {
        CHUNK_HASH(1),
        SECTION_DELTA(2),
        LIGHT_DELTA(3),
        SEED_REF(4),
        BLOCK_ENTITY_DATA(5);

        private final int id;

        HassiumSub(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        /** 按 id 查；未知返回 {@code null}。 */
        public static HassiumSub fromId(int id) {
            for (HassiumSub s : values()) {
                if (s.id == id) {
                    return s;
                }
            }
            return null;
        }
    }

    /** 业务包解码结果（{@link #packet()} 按 {@link #sub()} 断言类型）。 */
    public record HassiumPacket(HassiumSub sub, Object packet) {}

    private GatewayPacketCodec() {
    }

    // ==================== 编码（C2S / 登录 C2S） ====================

    /**
     * 编码原版包为帧 payload（kind=0）：{@code [varint 0][varint vanillaId][body]}。
     * 失败抛 {@link IllegalArgumentException}（调用方记日志跳过，不吞包）。
     *
     * @param flow          包方向（C2S = SERVERBOUND）
     * @param protocol      PLAY 或 LOGIN（决定协议表）
     * @param registryAccess 1.20.5+ PLAY 包编码用（可 EMPTY；登录包不需要）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ByteBuf encodeVanilla(Packet<?> packet, PacketFlow flow,
                                        GatewayProtocol protocol, RegistryAccess registryAccess) {
        ByteBuf payload = Unpooled.buffer();
        try {
            payload.writeByte(KIND_VANILLA);
#if MC_VER < MC_1_20_5
            FriendlyByteBuf fbuf = new FriendlyByteBuf(payload);
            net.minecraft.network.ConnectionProtocol proto;
            if (protocol == GatewayProtocol.LOGIN) {
                proto = net.minecraft.network.ConnectionProtocol.LOGIN;
            } else if (protocol == GatewayProtocol.CONFIG) {
#if MC_VER < MC_1_20_2
                throw new IllegalArgumentException("CONFIG protocol unavailable before 1.20.2");
#else
                proto = net.minecraft.network.ConnectionProtocol.CONFIGURATION;
#endif
            } else {
                proto = net.minecraft.network.ConnectionProtocol.PLAY;
            }
#if MC_VER < MC_1_20_2
            fbuf.writeVarInt(proto.getPacketId(flow, packet));
#else
            fbuf.writeVarInt(proto.codec(flow).packetId(packet));
#endif
            packet.write(fbuf);
#else
            net.minecraft.network.ProtocolInfo<?> info;
            if (protocol == GatewayProtocol.LOGIN) {
                info = PacketCodecCompat.loginInfo(flow);
            } else if (protocol == GatewayProtocol.CONFIG) {
                info = PacketCodecCompat.configInfo(flow);
            } else {
                info = PacketCodecCompat.playBound(flow, registryAccess);
            }
            // 完整编码（含协议包 ID VarInt）——帧格式 [varint id][body] 直接对齐
            ((net.minecraft.network.codec.StreamCodec) info.codec()).encode(payload, packet);
#endif
            return payload;
        } catch (Throwable t) {
            payload.release();
            throw new IllegalArgumentException("gateway packet encode failed: "
                    + packet.getClass().getSimpleName() + " (" + protocol + "/" + flow + ")", t);
        }
    }

    // ==================== 解码（S2C / 登录 S2C） ====================

    /** 窥视 payload 的 kind（不消费；payload 为空抛 IllegalArgumentException）。 */
    public static int peekKind(ByteBuf payload) {
        int readerIndex = payload.readerIndex();
        try {
            return ControlFrameCodec.readVarInt(payload);
        } finally {
            payload.readerIndex(readerIndex);
        }
    }

    /**
     * 解码原版包（kind=0）：{@code [varint 0][varint vanillaId][body]} → {@link Packet}。
     *
     * @param flow          包方向（S2C = CLIENTBOUND）
     * @param registryAccess 1.20.5+ PLAY 包解码用（区块包必须真实 registry，见
     *                       {@link io.github.limuqy.mc.hassium.network.ClientChunkHandler#decodeChunkPacket} 先例）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Packet<?> decodeVanilla(ByteBuf payload, PacketFlow flow,
                                          GatewayProtocol protocol, RegistryAccess registryAccess) {
        int kind = ControlFrameCodec.readVarInt(payload);
        if (kind != KIND_VANILLA) {
            throw new IllegalArgumentException("expected KIND_VANILLA, got " + kind);
        }
        int vanillaId = ControlFrameCodec.readVarInt(payload);
        int bodyLen = payload.readableBytes();
        byte[] body = new byte[bodyLen];
        payload.readBytes(body);
#if MC_VER < MC_1_20_5
        FriendlyByteBuf pBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
        try {
            net.minecraft.network.ConnectionProtocol proto;
            if (protocol == GatewayProtocol.LOGIN) {
                proto = net.minecraft.network.ConnectionProtocol.LOGIN;
            } else if (protocol == GatewayProtocol.CONFIG) {
#if MC_VER < MC_1_20_2
                throw new IllegalArgumentException("CONFIG protocol unavailable before 1.20.2");
#else
                proto = net.minecraft.network.ConnectionProtocol.CONFIGURATION;
#endif
            } else {
                proto = net.minecraft.network.ConnectionProtocol.PLAY;
            }
#if MC_VER < MC_1_20_2
            return proto.createPacket(flow, vanillaId, pBuf);
#else
            return proto.codec(flow).createPacket(vanillaId, pBuf);
#endif
        } finally {
            pBuf.release();
        }
#else
        if (protocol != GatewayProtocol.PLAY) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(body.length + 5));
            try {
                buf.writeVarInt(vanillaId);
                buf.writeBytes(body);
                net.minecraft.network.ProtocolInfo<?> info = protocol == GatewayProtocol.LOGIN
                        ? PacketCodecCompat.loginInfo(flow)
                        : PacketCodecCompat.configInfo(flow);
                return (Packet<?>) ((net.minecraft.network.codec.StreamCodec) info.codec()).decode(buf);
            } finally {
                buf.release();
            }
        }
        return PacketCodecCompat.deserializeClientbound(vanillaId, body, registryAccess);
#endif
    }

    /**
     * 解码 Hassium 业务包（kind=1）：{@code [varint 1][varint hassiumSubId][body]}。
     * 未知子类型抛 {@link IllegalArgumentException}。
     */
    public static HassiumPacket decodeHassium(ByteBuf payload) {
        int kind = ControlFrameCodec.readVarInt(payload);
        if (kind != KIND_HASSIUM) {
            throw new IllegalArgumentException("expected KIND_HASSIUM, got " + kind);
        }
        int subId = ControlFrameCodec.readVarInt(payload);
        HassiumSub sub = HassiumSub.fromId(subId);
        if (sub == null) {
            throw new IllegalArgumentException("unknown hassium sub id " + subId);
        }
        FriendlyByteBuf fbuf = new FriendlyByteBuf(payload);
        return switch (sub) {
            case CHUNK_HASH -> new HassiumPacket(sub, io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.decode(fbuf));
            case SECTION_DELTA -> new HassiumPacket(sub, io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket.decode(fbuf));
            case LIGHT_DELTA -> new HassiumPacket(sub, io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.decode(fbuf));
            case SEED_REF -> new HassiumPacket(sub, io.github.limuqy.mc.hassium.network.SeedRefS2CPacket.decode(fbuf));
            case BLOCK_ENTITY_DATA -> new HassiumPacket(sub, io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket.decode(fbuf));
        };
    }

    // ==================== 内部（1.20.5+ 协议表） ====================

    // 协议表绑定收敛为单源：PLAY → PacketCodecCompat.playBound(flow, registryAccess)；
    // LOGIN → PacketCodecCompat.loginInfo(flow)（见上方 encodeVanilla/decodeVanilla 调用点）。
}
