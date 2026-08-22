package io.github.limuqy.mc.hassium.compat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.21.1+ 包编解码兼容层（StreamCodec / GameProtocols / PacketType）。
 * <p>
 * 段 C：替代已移除的 {@code Packet.write()} 与 {@code ConnectionProtocol.getPacketsByIds()}。
 */
public final class PacketCodecCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PacketCodecCompat");

#if MC_VER >= MC_1_21_1
    private static volatile List<PlayPacketEntry> cachedClientbound;
    private static volatile List<PlayPacketEntry> cachedServerbound;
    /**
     * review-fix: T8-23: playBound 产物按 (flow, registryAccess) 缓存。registryAccess 每连接
     * 恒定，bind 产物可复用；serializePacketBody/Full/deserializePacketById 每包调用一次
     * （ServerChunkPushManager 推送热路径），原实现每包 decorator+bind 造成重复分配。
     * 用 WeakHashMap 键（弱引用）避免随连接生命周期泄漏；值强引用 bind 产物，键存活则命中。
     * equals 语义：RegistryAccess 结构相等（同注册表内容）即视为同键，bind 结果等价。
     * <p>槽位按 flow 分列：CLIENTBOUND/SERVERBOUND 各存一份——同 registryAccess 下
     * 两个 flow 的 ProtocolInfo 是不同协议表（IdDispatchCodec 包集合不同），共用一个
     * 产物会让 SERVERBOUND 编码误用 CLIENTBOUND 表（keep_alive 等 common 包 unknown）。
     */
    private static final Map<RegistryAccess, net.minecraft.network.ProtocolInfo<?>[]> PLAY_BOUND_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
#endif

    private PacketCodecCompat() {}

    /**
     * PLAY 包枚举条目：PacketType.id + 协议内数字 ID。
     */
    public record PlayPacketEntry(
            PacketId id,
            int numericId,
            PacketFlow flow
    ) {}

    /**
     * 从 Connection 解析 RegistryAccess（服务端 player / 客户端 ClientPacketListener）。
     */
    public static RegistryAccess resolveRegistryAccess(Connection connection) {
#if MC_VER < MC_1_21_1
        return null;
#else
        if (connection == null) {
            return RegistryAccess.EMPTY;
        }
        PacketListener listener = connection.getPacketListener();
        if (listener == null) {
            return RegistryAccess.EMPTY;
        }
        if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl server) {
            return server.player.registryAccess();
        }
        try {
            Class<?> clientListener = Class.forName("net.minecraft.client.multiplayer.ClientPacketListener");
            if (clientListener.isInstance(listener)) {
                Object ra = clientListener.getMethod("registryAccess").invoke(listener);
                if (ra instanceof RegistryAccess registryAccess) {
                    return registryAccess;
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("无法从客户端 PacketListener 获取 RegistryAccess: {}", e.toString());
        }
        return RegistryAccess.EMPTY;
#endif
    }

    /**
     * 服务端 RegistryAccess（{@code <1.21.1} 无 registry 形态，恒 {@link RegistryAccess#EMPTY}；
     * {@code >=1.21.1} 返回 {@code server.registryAccess()}，server 为 null 时 EMPTY）。
     * 网关桥 BridgeState 单源。
     */
    public static RegistryAccess serverRegistryAccess(net.minecraft.server.MinecraftServer server) {
#if MC_VER < MC_1_21_1
        return RegistryAccess.EMPTY;
#else
        return server != null ? server.registryAccess() : RegistryAccess.EMPTY;
#endif
    }

    /**
     * 登录完成包判定（{@code <1.21.2 ClientboundGameProfilePacket} /
     * {@code >=1.21.2 ClientboundLoginFinishedPacket} 双类名收口；
     * GatewayPlayerBridge.detectProtocol 与 GatewayS2CRouter.isLoginPhasePacket 同宏分界单源）。
     */
    public static boolean isLoginFinishedPacket(Packet<?> packet) {
#if MC_VER < MC_1_21_2
        return packet instanceof net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
#else
        return packet instanceof net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
#endif
    }

    /**
     * 序列化原版包 body（不含协议包 ID VarInt）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static byte[] serializePacketBody(Packet<?> packet, RegistryAccess registryAccess) {
#if MC_VER < MC_1_21_1
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
#else
        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        PacketFlow flow = packet.type().flow();
        var info = playBound(flow, registryAccess);
        ByteBuf buf = Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) info.codec()).encode(buf, packet);
            FriendlyByteBuf fbuf = new FriendlyByteBuf(buf);
            fbuf.readVarInt(); // 跳过协议包 ID
            byte[] data = new byte[fbuf.readableBytes()];
            fbuf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
#endif
    }

    /**
     * 按协议数字 ID + body 反序列化 CLIENTBOUND 包。
     */
    public static Packet<?> deserializeClientbound(
            int vanillaId,
            byte[] body,
            RegistryAccess registryAccess
    ) {
        return deserializePacketById(PacketFlow.CLIENTBOUND, vanillaId, body, registryAccess);
    }

    /**
     * 按协议数字 ID + body 反序列化包（flow 泛化版；T5 网关 outbound C2S/S2C 解码、T9 ViaFabric 链复用）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Packet<?> deserializePacketById(
            PacketFlow flow,
            int vanillaId,
            byte[] body,
            RegistryAccess registryAccess
    ) {
#if MC_VER < MC_1_21_1
        FriendlyByteBuf pBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
        // 1.20.2–1.20.4 的 ConnectionProtocol.codec(flow) 中间层已随版本支持裁剪删除
        return net.minecraft.network.ConnectionProtocol.PLAY.createPacket(flow, vanillaId, pBuf);
#else
        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        var info = playBound(flow, registryAccess);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(body.length + 5));
        try {
            buf.writeVarInt(vanillaId);
            buf.writeBytes(body);
            return (Packet<?>) ((net.minecraft.network.codec.StreamCodec) info.codec()).decode(buf);
        } finally {
            buf.release();
        }
#endif
    }

    /**
     * 序列化完整原版包（含协议包 ID VarInt）——ViaFabric 转换链的输入线格式。
     * <p>
     * {@code flow} 参数：{@code <1.21.1} 段 {@link net.minecraft.network.ConnectionProtocol#getPacketId}
     * 按 flow 查 ID 必需；{@code >=1.21.1} 段用于绑定协议编解码器。调用方负责传对 flow。
     */
    @SuppressWarnings({"rawtypes"})
    public static byte[] serializePacketFull(
            Packet<?> packet,
            PacketFlow flow,
            RegistryAccess registryAccess
    ) {
#if MC_VER < MC_1_21_1
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(net.minecraft.network.ConnectionProtocol.PLAY.getPacketId(flow, packet));
            packet.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
#else
        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        var info = playBound(flow, registryAccess);
        ByteBuf buf = Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) info.codec()).encode(buf, packet);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
#endif
    }

    /**
     * 反序列化完整原版包（缓冲内含协议包 ID VarInt，解码后 ID 自行消费）——
     * ViaFabric 转换链的输出线格式。未知 ID 返回 null（{@code <1.21.1} 段语义）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Packet<?> deserializePacketFull(
            PacketFlow flow,
            byte[] fullPacketBytes,
            RegistryAccess registryAccess
    ) {
#if MC_VER < MC_1_21_1
        FriendlyByteBuf pBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(fullPacketBytes));
        return net.minecraft.network.ConnectionProtocol.PLAY.createPacket(flow, pBuf.readVarInt(), pBuf);
#else
        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        var info = playBound(flow, registryAccess);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(fullPacketBytes));
        try {
            return (Packet<?>) ((net.minecraft.network.codec.StreamCodec) info.codec()).decode(buf);
        } finally {
            buf.release();
        }
#endif
    }

    /**
     * 枚举 PLAY 协议一侧的全部 PacketType（确定性顺序 = 协议 ID 顺序）。
     */
    public static List<PlayPacketEntry> enumeratePlayPackets(PacketFlow flow) {
#if MC_VER < MC_1_21_1
        return Collections.emptyList();
#else
        if (flow == PacketFlow.CLIENTBOUND) {
            List<PlayPacketEntry> cached = cachedClientbound;
            if (cached != null) {
                return cached;
            }
            cached = loadPlayPackets(flow);
            cachedClientbound = cached;
            return cached;
        } else {
            List<PlayPacketEntry> cached = cachedServerbound;
            if (cached != null) {
                return cached;
            }
            cached = loadPlayPackets(flow);
            cachedServerbound = cached;
            return cached;
        }
#endif
    }

#if MC_VER >= MC_1_21_1
    /**
     * 绑定 PLAY 协议编解码器（单源：网关编解码 GatewayPacketCodec 亦复用）。
     * <ul>
     *   <li>1.20.5–1.21.4：{@code ProtocolInfo.Unbound.bind(decorator)}</li>
     *   <li>1.21.5+：CLIENTBOUND 为 {@code SimpleUnboundProtocol}；
     *       SERVERBOUND 为带 {@code GameProtocols.Context} 的 {@code UnboundProtocol}</li>
     * </ul>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static net.minecraft.network.ProtocolInfo<?> playBound(
            PacketFlow flow,
            RegistryAccess registryAccess
    ) {
        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        // review-fix: T8-23: 弱引用小缓存——命中即复用 bound ProtocolInfo，避免每包 decorator+bind。
        // 槽位按 flow 分列（见 PLAY_BOUND_CACHE javadoc）：CLIENTBOUND/SERVERBOUND 协议表不同。
        int slotIndex = flow == PacketFlow.CLIENTBOUND ? 0 : 1;
        net.minecraft.network.ProtocolInfo<?>[] slot =
                PLAY_BOUND_CACHE.computeIfAbsent(registryAccess, ra -> new net.minecraft.network.ProtocolInfo<?>[2]);
        net.minecraft.network.ProtocolInfo<?> cached = slot[slotIndex];
        if (cached == null) {
            cached = bindPlayBound(flow, registryAccess);
            slot[slotIndex] = cached; // 并发双 bind 无害（幂等覆盖同语义产物）
        }
        return cached;
    }

    /** review-fix: T8-23: 实际 bind 构造（缓存未命中时执行一次）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static net.minecraft.network.ProtocolInfo<?> bindPlayBound(
            PacketFlow flow,
            RegistryAccess registryAccess
    ) {
        @SuppressWarnings("deprecation") // NeoForge 1.21.11+: decorator(1-param) deprecated; 2-param 需 ConnectionType.OTHER(仅 NeoForge classpath)
        var decorator = net.minecraft.network.RegistryFriendlyByteBuf.decorator(registryAccess);
#if MC_VER < MC_1_21_5
        return playUnbound(flow).bind(decorator);
#else
        if (flow == PacketFlow.CLIENTBOUND) {
            return net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator);
        }
        return net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE.bind(
                decorator,
                () -> true);
#endif
    }

#if MC_VER < MC_1_21_5
    /**
     * 1.20.5–1.20.6：{@code GameProtocols.CLIENTBOUND/SERVERBOUND}
     * 1.21.1–1.21.4：{@code CLIENTBOUND_TEMPLATE/SERVERBOUND_TEMPLATE}（仍为 {@code ProtocolInfo.Unbound}）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static net.minecraft.network.ProtocolInfo.Unbound playUnbound(PacketFlow flow) {
#if MC_VER < MC_1_21_1
        return flow == PacketFlow.CLIENTBOUND
                ? net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND
                : net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND;
#else
        return flow == PacketFlow.CLIENTBOUND
                ? net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE
                : net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE;
#endif
    }
#endif

    /**
     * 登录协议已绑定信息（FriendlyByteBuf；登录包无注册表内容）。
     * 单源：网关编解码 GatewayPacketCodec 亦复用（网关 LOGIN 协议仅 1.20.5+ 使用）。
     */
#if MC_VER >= MC_1_21_1
    public static net.minecraft.network.ProtocolInfo<?> loginInfo(PacketFlow flow) {
        return flow == PacketFlow.CLIENTBOUND
                ? net.minecraft.network.protocol.login.LoginProtocols.CLIENTBOUND
                : net.minecraft.network.protocol.login.LoginProtocols.SERVERBOUND;
    }
#endif

    /**
     * 配置协议已绑定信息（FriendlyByteBuf；配置含注册表数据包，但编解码不依赖本地注册表）。
     * 单源：网关编解码 GatewayPacketCodec 复用（T10 CONFIG_C2S/CONFIG_S2C 帧）。
     */
#if MC_VER >= MC_1_21_1
    public static net.minecraft.network.ProtocolInfo<?> configInfo(PacketFlow flow) {
        return flow == PacketFlow.CLIENTBOUND
                ? net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND
                : net.minecraft.network.protocol.configuration.ConfigurationProtocols.SERVERBOUND;
    }
#endif

    @SuppressWarnings("unchecked")
    private static List<PlayPacketEntry> loadPlayPackets(PacketFlow flow) {
        try {
#if MC_VER < MC_1_21_5
            // 1.21.1–1.21.4：Unbound 上有 listPackets
            List<PlayPacketEntry> fromList = new ArrayList<>();
            playUnbound(flow).listPackets((packetType, numericId) ->
                    fromList.add(new PlayPacketEntry(ResourceLocationCompat.toPacketId(packetType.id()), numericId, flow)));
            if (!fromList.isEmpty()) {
                LOGGER.debug("枚举 PLAY {} 包类型: {} 个", flow, fromList.size());
                return Collections.unmodifiableList(fromList);
            }
#else
            List<PlayPacketEntry> fromList = new ArrayList<>();
            net.minecraft.network.ProtocolInfo.Details details = flow == PacketFlow.CLIENTBOUND
                    ? net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.details()
                    : net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE.details();
            details.listPackets((packetType, numericId) ->
                    fromList.add(new PlayPacketEntry(ResourceLocationCompat.toPacketId(packetType.id()), numericId, flow)));
            if (!fromList.isEmpty()) {
                LOGGER.debug("枚举 PLAY {} 包类型: {} 个", flow, fromList.size());
                return Collections.unmodifiableList(fromList);
            }
#endif
            // 回退：反射 IdDispatchCodec.byId（旧路径）
            var info = playBound(flow, RegistryAccess.EMPTY);
            Object codec = info.codec();
            if (!(codec instanceof net.minecraft.network.codec.IdDispatchCodec<?, ?, ?> idCodec)) {
                LOGGER.error("ProtocolInfo.codec() 不是 IdDispatchCodec: {}", codec.getClass().getName());
                return Collections.emptyList();
            }
            // 按类型匹配而非字段名：Fabric（intermediary）/ Forge（SRG）生产运行时字段名不是 "byId"
            Field byIdField = ReflectionCompat.findFieldByType(
                    net.minecraft.network.codec.IdDispatchCodec.class, List.class, false);
            byIdField.setAccessible(true);
            List<?> byId = (List<?>) byIdField.get(idCodec);
            List<PlayPacketEntry> result = new ArrayList<>(byId.size());
            for (int i = 0; i < byId.size(); i++) {
                Object entry = byId.get(i);
                if (entry == null) {
                    // review-fix: T8-24: 列表含 null 洞（反射回退脆弱性）——跳过并记录洞位，
                    // 不再 NPE 冒泡到外层 catch 导致整表返回空列表（调用方得空表更糟）
                    LOGGER.warn("PLAY {} byId 表第 {} 位为 null（洞位），跳过（numericId 后续错位风险已记录）",
                            flow, i);
                    continue;
                }
                Method typeMethod = entry.getClass().getMethod("type");
                Object typeObj = typeMethod.invoke(entry);
                if (!(typeObj instanceof net.minecraft.network.protocol.PacketType<?> packetType)) {
                    continue;
                }
                result.add(new PlayPacketEntry(ResourceLocationCompat.toPacketId(packetType.id()), i, flow));
            }
            LOGGER.debug("枚举 PLAY {} 包类型: {} 个", flow, result.size());
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            LOGGER.error("枚举 PLAY 包类型失败 ({})", flow, e);
            return Collections.emptyList();
        }
    }
#endif

#if MC_VER >= MC_1_21_1
    /**
     * 提取 CustomPacketPayload 字节（Raw / data() / StreamCodec 编码后剥 type 头）。
     */
    public static byte[] extractCustomPayloadBytes(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
            RegistryAccess registryAccess
    ) {
        if (payload == null) {
            return null;
        }
        // Fabric RawPayload / 带 data() 的包装
        try {
            Method dataMethod = payload.getClass().getMethod("data");
            if (dataMethod.getReturnType() == byte[].class) {
                return (byte[]) dataMethod.invoke(payload);
            }
        } catch (NoSuchMethodException ignored) {
            // 继续走 codec
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("调用 payload.data() 失败: {}", e.toString());
        }

        if (registryAccess == null) {
            registryAccess = RegistryAccess.EMPTY;
        }
        @SuppressWarnings("deprecation") // NeoForge 1.21.11+: RegistryFriendlyByteBuf(2-param) deprecated; 3-param 需 ConnectionType.OTHER(仅 NeoForge classpath)
        net.minecraft.network.RegistryFriendlyByteBuf buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
        try {
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC
                    .encode(buf, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload));
#if MC_VER < MC_1_21_11
            buf.readResourceLocation(); // 跳过 type id
#else
            buf.readIdentifier(); // 1.21.11+: ResourceLocation → Identifier
#endif
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } catch (Exception e) {
            LOGGER.warn("无法通过 StreamCodec 提取 payload {}: {}",
                    payload.type().id(), e.toString());
            return null;
        } finally {
            buf.release();
        }
    }
#endif
}
