package io.github.limuqy.mc.hassium.network.core.outbound;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 网关 outbound 握手编解码（从三端 NetworkManager 内联线格式提取，纯 ByteBuf 零 MC 依赖）。
 *
 * <p><b>为何不激活 {@code HassiumHandshake}：</b>其线格式（ByteBuffer + HassiumCapabilities）
 * 与三端内联格式（varint/utf + 固定字段 + append-only 尾）不一致；激活需迁移三端发送方，
 * 回归风险大且 T6 未派不可删代码。本类 = 内联格式提取，纯新增、零回归，三端发送方原样保留。
 *
 * <p><b>C2S 请求线格式</b>（T6 前为三端 NetworkManager 内联发送格式；现由本类作为
 * 网关 outbound 唯一生成方）：
 * <pre>
 *   varint protocolVersion | utf modVersion | varint 2
 *   | utf algo | utf algo+"_dict"
 *   | bool clientCache | bool chunkRevision | bool scheme127(false)
 *   | bool globalCompression | bool compactHeader
 *   | [append-only 尾] udpTail flags 1B | double x | double z
 *   | bool seedGenSupported | bool engineEnabled
 *   | [append-only 尾] T7 状态尾（可选）| D-M2 authToken（utf，可选）
 * </pre>
 *
 * <p><b>S2C 响应线格式</b>（对齐三端 {@code completeServerHandshake} 与客户端解码）：
 * <pre>
 *   varint protocolVersion | bool accepted | bool globalCompression | bool compactHeader
 *   | [accepted 时] S2CTail（UdpDataPlaneHandshakeTail）
 *   | [accepted 时 append-only 尾] long worldSeed | varint stemLen + bytes | bool seedGenEnabled
 * </pre>
 * 旧服务端（无任何尾）解码时 S2CTail 为 disabled、SeedGen 字段取默认——向后兼容。
 *
 * <p>T7 续流票据：继续 append-only 尾追加（本类 decode/encode 忽略未知尾字节，天然兼容）。
 */
public final class HandshakeCodec {

    private HandshakeCodec() {
    }

    /** C2S 握手请求参数（与三端内联发送方同口径）。 */
    public record ClientRequestOptions(
            int protocolVersion,
            String modVersion,
            String compressionAlgorithm,
            boolean udpDataplaneSupported,
            boolean controlFailoverSupported,
            double posX,
            double posZ,
            boolean seedGenSupported,
            boolean engineEnabled
    ) {
        /** 从常量与配置构造当前客户端默认参数。 */
        public static ClientRequestOptions defaults() {
            HassiumConfigService config = HassiumConfigService.getInstance();
            return new ClientRequestOptions(
                    Constants.CURRENT_PROTOCOL_VERSION,
                    Constants.MOD_VERSION,
                    Constants.NETWORK_COMPRESSION_ALGORITHM,
                    true,
                    true,
                    0.0,
                    0.0,
                    config.isClientSeedGenEnabled(),
                    config.isHassiumEngineEnabled()
            );
        }
    }

    /** S2C 握手响应解码结果（含 append-only 尾字段；T7 票据字段在此 record 追加）。 */
    public record ServerResponse(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted,
            UdpDataPlaneHandshakeTail.S2CTail udpTail,
            long worldSeed,
            byte[] levelStemNbt,
            boolean seedGenEnabled
    ) {
        /** 拒绝响应（线格式中无拒绝原因字段；{@code udpTail} 为 disabled）。 */
        public static ServerResponse rejected(int protocolVersion) {
            return new ServerResponse(protocolVersion, false, false, false,
                    UdpDataPlaneHandshakeTail.S2CTail.disabled(), 0L, null, false);
        }
    }

    /**
     * 编码 C2S 握手请求（线格式与三端内联发送方一致；append-only 尾字段集中在此）。
     */
    public static ByteBuf encodeClientRequest(ClientRequestOptions opts) {
        return encodeClientRequest(opts, null);
    }

    /**
     * 编码 C2S 握手请求 + T7 续流状态尾（{@link HandshakeStateTail.C2S}：玩家状态 +
     * resumeRequested + 票据字节；T8 迁移引擎续流发起）。tail 为 null 时不追加
     * （旧路径线格式不变）。尾追加在固定字段之后，旧服务端忽略尾字节。
     */
    public static ByteBuf encodeClientRequest(ClientRequestOptions opts, HandshakeStateTail.C2S tail) {
        return encodeClientRequest(opts, tail, "");
    }

    /**
     * 编码 C2S 握手请求 + T7 续流状态尾 + D-M2 握手鉴权 token（{@code master.authToken}
     * 双端同键；非空时服务端校验失败 close("auth failed")）。
     * authToken 以 utf 追加在状态尾之后（append-only：旧服务端忽略尾字节；旧客户端无此字段）。
     * authToken 为空时不追加任何字节——未启用鉴权时线格式与旧版完全一致。
     */
    public static ByteBuf encodeClientRequest(ClientRequestOptions opts, HandshakeStateTail.C2S tail, String authToken) {
        ByteBuf buf = Unpooled.buffer();
        ControlFrameCodec.writeVarInt(buf, opts.protocolVersion());
        ControlFrameCodec.writeUtf(buf, opts.modVersion());
        ControlFrameCodec.writeVarInt(buf, 2); // 支持的算法数量
        ControlFrameCodec.writeUtf(buf, opts.compressionAlgorithm());
        ControlFrameCodec.writeUtf(buf, opts.compressionAlgorithm() + "_dict");
        buf.writeBoolean(true);  // clientCacheSupported
        buf.writeBoolean(true);  // chunkRevisionSupported
        buf.writeBoolean(false); // scheme127Supported
        buf.writeBoolean(true);  // globalPacketCompressionSupported
        buf.writeBoolean(true);  // compactHeaderSupported
        // ---- append-only 尾（旧服务端忽略尾字节） ----
        UdpDataPlaneHandshakeTail.writeC2S(buf,
                new UdpDataPlaneHandshakeTail.C2STail(opts.udpDataplaneSupported(), opts.controlFailoverSupported()));
        buf.writeDouble(opts.posX());
        buf.writeDouble(opts.posZ());
        buf.writeBoolean(opts.seedGenSupported());
        buf.writeBoolean(opts.engineEnabled());
        if (tail != null) {
            HandshakeStateTail.writeC2S(buf, tail);
        }
        if (authToken != null && !authToken.isEmpty()) {
            ControlFrameCodec.writeUtf(buf, authToken);
        }
        return buf;
    }

    /**
     * 读取 C2S 握手尾部的鉴权 token（D-M2；追加在 T7 状态尾之后，调用方须先 readC2S）。
     * 旧客户端无此字段 → 空串；损坏 → 空串（鉴权开启时自然校验失败）。
     */
    public static String readAuthToken(ByteBuf in) {
        if (in == null || !in.isReadable()) {
            return "";
        }
        try {
            return ControlFrameCodec.readUtf(in);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * 解码 C2S 握手请求（master 侧网关端点镜像 {@link #encodeClientRequest}；T11）。
     *
     * <p>固定字段 → UDP tail（flags 1B，不可读 → disabled）→ x/z（需 ≥16B）→
     * seedGen → engine（可读才读）。固定字段之后剩余的 append-only 尾字节（T7
     * {@code HandshakeStateTail.C2S} 等）本方法不消费，由调用方继续 {@code readC2S}
     * 或按需忽略——旧客户端无尾字节时剩余为空。
     */
    public static ClientRequestOptions decodeClientRequest(ByteBuf in) {
        int protocolVersion = ControlFrameCodec.readVarInt(in);
        String modVersion = ControlFrameCodec.readUtf(in);
        int algoCount = ControlFrameCodec.readVarInt(in);
        String firstAlgo = "";
        for (int i = 0; i < algoCount; i++) {
            String algo = ControlFrameCodec.readUtf(in);
            if (i == 0) {
                firstAlgo = algo;
            }
        }
        boolean clientCache = in.readBoolean();
        boolean chunkRevision = in.readBoolean();
        boolean scheme127 = in.readBoolean();
        boolean globalCompression = in.readBoolean();
        boolean compactHeader = in.readBoolean();
        UdpDataPlaneHandshakeTail.C2STail tail = UdpDataPlaneHandshakeTail.readC2S(in);
        double posX = 0.0;
        double posZ = 0.0;
        if (in.readableBytes() >= 16) {
            posX = in.readDouble();
            posZ = in.readDouble();
        }
        boolean seedGenSupported = false;
        if (in.isReadable()) {
            seedGenSupported = in.readBoolean();
        }
        boolean engineEnabled = false;
        if (in.isReadable()) {
            engineEnabled = in.readBoolean();
        }
        return new ClientRequestOptions(protocolVersion, modVersion, firstAlgo,
                tail.udpDataplaneSupported(), tail.controlFailoverSupported(),
                posX, posZ, seedGenSupported, engineEnabled);
    }

    /**
     * 解码 S2C 握手响应（对齐三端客户端内联解码：固定字段 → UDP tail → SeedGen 尾；
     * 缺尾/旧服务端向后兼容，全部取默认）。
     */
    public static ServerResponse decodeServerResponse(ByteBuf in) {
        int protocolVersion = ControlFrameCodec.readVarInt(in);
        boolean accepted = in.readBoolean();
        boolean globalCompressionAccepted = in.readBoolean();
        boolean compactHeaderAccepted = in.readBoolean();

        UdpDataPlaneHandshakeTail.S2CTail udpTail = null;
        long worldSeed = 0L;
        byte[] levelStemNbt = null;
        boolean seedGenEnabled = false;

        if (accepted) {
            if (in.isReadable()) {
                udpTail = UdpDataPlaneHandshakeTail.readS2C(in);
            }
            if (in.isReadable()) {
                worldSeed = in.readLong();
                int stemLen = ControlFrameCodec.readVarInt(in);
                if (stemLen > 0 && stemLen <= in.readableBytes()) {
                    levelStemNbt = new byte[stemLen];
                    in.readBytes(levelStemNbt);
                }
                if (in.isReadable()) {
                    seedGenEnabled = in.readBoolean();
                }
            }
        }
        return new ServerResponse(protocolVersion, accepted, globalCompressionAccepted,
                compactHeaderAccepted, udpTail, worldSeed, levelStemNbt, seedGenEnabled);
    }

    /**
     * 编码 S2C 握手响应（服务端线格式镜像；供单测 roundtrip 与后续 master 侧网关端点复用）。
     * 拒绝时忽略压缩/尾参数，仅写 4 个固定字段。
     */
    public static ByteBuf encodeServerResponse(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted,
            UdpDataPlaneHandshakeTail.S2CTail udpTail,
            long worldSeed,
            byte[] levelStemNbt,
            boolean seedGenEnabled) {
        ByteBuf buf = Unpooled.buffer();
        ControlFrameCodec.writeVarInt(buf, protocolVersion);
        buf.writeBoolean(accepted);
        buf.writeBoolean(globalCompressionAccepted);
        buf.writeBoolean(compactHeaderAccepted);
        if (accepted) {
            UdpDataPlaneHandshakeTail.writeS2C(buf,
                    udpTail != null ? udpTail : UdpDataPlaneHandshakeTail.S2CTail.disabled());
            buf.writeLong(worldSeed);
            byte[] stem = levelStemNbt;
            ControlFrameCodec.writeVarInt(buf, stem != null ? stem.length : 0);
            if (stem != null && stem.length > 0) {
                buf.writeBytes(stem);
            }
            buf.writeBoolean(seedGenEnabled);
        }
        return buf;
    }
}
