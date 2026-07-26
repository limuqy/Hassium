package io.github.limuqy.mc.hassium.network.dataplane;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 数据面握手尾部字段编解码（C2S multiChannelSupported / S2C hasDataPlane+endpoints+token）。
 * <p>
 * 旧字段顺序不变；新字段仅尾部 append。读侧一律 {@link FriendlyByteBuf#isReadable()} 门控，
 * 保证旧端忽略 leftover、新端遇旧包安全关闭数据面。
 */
public final class DataPlaneHandshakeTail {

    private DataPlaneHandshakeTail() {}

    /** S2C 解析结果；hasDataPlane=false 时 endpoints/token 为 null。 */
    public record S2CTail(
            boolean hasDataPlane,
            DataPlanePoCConfig.Endpoint[] endpoints,
            byte[] token
    ) {
        public static S2CTail none() {
            return new S2CTail(false, null, null);
        }
    }

    /** C2S：写 multiChannelSupported。 */
    public static void writeC2S(FriendlyByteBuf buf, boolean multiChannelSupported) {
        buf.writeBoolean(multiChannelSupported);
    }

    /**
     * C2S：若还有字节则读 multiChannelSupported，否则 false。
     * 调用前须已读完旧 5 个 boolean 标志。
     */
    public static boolean readC2SMultiChannel(FriendlyByteBuf buf) {
        if (!buf.isReadable()) {
            return false;
        }
        return buf.readBoolean();
    }

    /**
     * S2C：写 hasDataPlane + 可选 endpoints + token。
     * endpoints 线格式仅 (address, port, weight)；token 必须 16 字节。
     */
    public static void writeS2C(
            FriendlyByteBuf buf,
            boolean hasDataPlane,
            DataPlanePoCConfig.Endpoint[] endpoints,
            byte[] token) {
        buf.writeBoolean(hasDataPlane);
        if (!hasDataPlane) {
            return;
        }
        if (endpoints == null || token == null || token.length != 16) {
            throw new IllegalArgumentException("hasDataPlane requires endpoints + 16-byte token");
        }
        buf.writeVarInt(endpoints.length);
        for (DataPlanePoCConfig.Endpoint ep : endpoints) {
            buf.writeUtf(ep.address);
            buf.writeInt(ep.port);
            buf.writeInt(ep.weight);
        }
        buf.writeBytes(token);
    }

    /**
     * S2C：若还有字节则解析 hasDataPlane 段，否则 {@link S2CTail#none()}。
     * 调用前须已读完旧字段 protocol/accepted/useGlobal/useCompact。
     */
    public static S2CTail readS2C(FriendlyByteBuf buf) {
        if (!buf.isReadable()) {
            return S2CTail.none();
        }
        boolean hasDataPlane = buf.readBoolean();
        if (!hasDataPlane) {
            return S2CTail.none();
        }
        int n = buf.readVarInt();
        DataPlanePoCConfig.Endpoint[] eps = new DataPlanePoCConfig.Endpoint[n];
        for (int i = 0; i < n; i++) {
            String host = buf.readUtf();
            int port = buf.readInt();
            int weight = buf.readInt();
            // 客户端不 bind；bindHost/bindPort 占位
            eps[i] = new DataPlanePoCConfig.Endpoint(host, port, weight, "0.0.0.0", port);
        }
        byte[] token = new byte[16];
        buf.readBytes(token);
        return new S2CTail(true, eps, token);
    }

    /**
     * 服务端 hasDataPlane 判定：配置开 + 客户端 multiChannel + Data 端口已 bind + sessionToken 就绪。
     */
    public static boolean shouldOfferDataPlane(boolean accepted, boolean clientMultiChannel) {
        return accepted
                && clientMultiChannel
                && DataPlanePoCConfig.isEnabled()
                && DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE
                && DataPlaneServer.isBound()
                && DataPlaneServer.getSessionToken() != null;
    }
}
