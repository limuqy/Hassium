package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.client.ChunkAuthorityClient;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.protocol.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.server.ChunkAuthorityS2CPacket;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三端 play 通道共享的 payload 编解码与分发收口（P1b 下沉）。
 * <p>
 * 职责切分：loader 侧只保留「传输面」——通道注册、线程封送
 * （{@code enqueueWork} / {@code client.execute}）、发送载体；本类持有
 * 「解包 + 业务分发」与「发送端编码」，替代此前三端逐字拷贝的 receiver
 * / send 样板。除 {@link #handleShadowPullResponse}（入
 * {@link PullResponseDecodeQueue} 后台 hop）外，方法对调用线程无要求
 * （线程语义由 loader 调用点决定）。
 * <p>
 * 线格式不变：byte[] 的封装（Fabric {@code RawPayload} codec /
 * NeoForge {@code ByteArrayPayload} codec / Forge {@code *Wrapper}）仍在
 * 各 loader 边界，{@link #encodeIndexSyncEnvelope()} 只产出内层信封，
 * 外层封装由各端载体决定（Forge/1.21.1+ payload API 会再加一层 varint
 * 前缀，与重构前逐字节一致）。
 */
public final class PayloadHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PayloadHandlers");

    private PayloadHandlers() {
    }

    // ===== buf ↔ byte[] =====

    /**
     * 读取 buf 全部剩余字节（不释放：调用方持有 buf 生命周期，如 Fabric
     * 1.20.1 receiver 回调、发送路径随后还要 release 的场景）。
     */
    public static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    /**
     * 读取 buf 全部剩余字节并释放（发送侧「buf 落成 byte[]」统一收口，
     * 替代三端 {@code new byte[readableBytes]; readBytes; release} 样板）。
     */
    public static byte[] drain(FriendlyByteBuf buf) {
        try {
            return readAll(buf);
        } finally {
            buf.release();
        }
    }

    private static FriendlyByteBuf wrap(byte[] data) {
        return new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
    }

    // ===== 客户端 S2C 解包分发 =====

    /**
     * shadow_pull_response_s2c：入专用 hop（后台解压/解码），主线程只保留 apply。
     * Loader receiver 与聚合帧子包共用本入口。
     */
    public static void handleShadowPullResponse(byte[] data) {
        PullResponseDecodeQueue.enqueue(data);
    }

    /** 工人线程：ZSTD 解压 + packet 解码 + {@link ShadowPullClient} 业务分发。 */
    static void processShadowPullResponse(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        try {
            ShadowPullClient.handleResponse(ShadowPullResponseS2CPacket.decode(buf));
        } catch (Exception e) {
            LOGGER.warn("[CLIENT] Failed to handle shadowPullV1 response", e);
        } finally {
            buf.release();
        }
    }

    /**
     * chunk_authority_s2c：服务端权威边沿（enter + 权威 hash）→
     * {@link ChunkAuthorityClient}（三分支解析：零请求命中 / 带基线比较 / 空基线 FULL）。
     */
    public static void handleChunkAuthority(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        try {
            ChunkAuthorityClient.handle(ChunkAuthorityS2CPacket.decode(buf));
        } catch (Exception e) {
            LOGGER.warn("[CLIENT] Failed to handle chunk authority edges", e);
        } finally {
            buf.release();
        }
    }

    /**
     * play_init_s2c：登录协商结果 + SeedGen 种子 → {@link PlayInitClient}（不捕获，
     * 与三端原 receiver 一致，异常沿 execute/enqueueWork 通道上抛）。
     */
    public static void handlePlayInit(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        try {
            PlayInitClient.handle(LoginHandshake.PlayInitPayload.decode(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * light_delta_s2c：光照增量 → 影子端 {@link ShadowLightCompute}（任意线程安全）。
     */
    public static void handleLightDelta(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        try {
            ShadowLightCompute.submitLightDelta(LightDeltaS2CPacket.decode(buf));
        } catch (Exception e) {
            LOGGER.error("[CLIENT] Failed to handle light delta", e);
        } finally {
            buf.release();
        }
    }

    /**
     * dictionary_sync（客户端）：聚合字典登记（聚合包解码前置条件）。
     */
    public static void handleDictionarySync(byte[] data) {
        try {
            DictionarySyncPayload payload = DictionarySyncPayload.decode(wrap(data));
            DictionaryManager.setAggregationDict(payload.dictionary());
            LOGGER.debug("Hassium: Received aggregation dictionary ({} bytes)",
                    payload.dictionary() != null ? payload.dictionary().length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle dictionary sync", e);
        }
    }

    /**
     * aggregation_s2c：聚合帧拆包分发（原版子包重建 + 自定义 payload 回灌）。
     * 捕获 {@link Throwable}：review-fix T13-C1（decode 校验抛
     * IllegalArgumentException/Error 均须收敛，防 OOM 后链路悬挂）。
     */
    public static void handleAggregation(byte[] data) {
        var clientConn = Minecraft.getInstance().getConnection();
        if (clientConn == null) {
            LOGGER.error("Received aggregation packet but no client connection");
            return;
        }
        handleAggregation(data, clientConn.getConnection());
    }

    /**
     * 工人/任意线程：解压拆包后对子包 {@code packet.handle}（原版 hop 主线程 apply）。
     */
    public static void handleAggregation(byte[] data, net.minecraft.network.Connection connection) {
        FriendlyByteBuf packetBuf = wrap(data);
        try {
            if (connection == null) {
                LOGGER.error("Received aggregation packet but no connection");
                return;
            }
            NamespaceIndexManager indexManager = IndexSyncManager.getInstance().getClientIndexManager();
            if (indexManager == null) {
                LOGGER.error("Received aggregation packet but client index manager not initialized");
                return;
            }
            HassiumAggregationPacket.decode(packetBuf, indexManager).handle(connection);
        } catch (Throwable e) {
            LOGGER.error("Failed to handle aggregation packet", e);
        } finally {
            packetBuf.release();
        }
    }

    // ===== 服务端 C2S 解包分发 =====

    /**
     * shadow_pull_request_c2s：decode → 权威 Compare+Pull → encode，返回响应
     * 字节（非 null）。不捕获异常（decode 校验失败由 loader 侧 catch 落 warn 日志）；
     * 发送载体由 loader 决定（各端通道封装不同）。
     */
    public static byte[] handleShadowPullRequest(ShadowPullHandler handler, ServerPlayer player,
                                                 byte[] requestBytes) {
        FriendlyByteBuf buf = wrap(requestBytes);
        try {
            ShadowPullRequestC2SPacket request = ShadowPullRequestC2SPacket.decode(buf);
            ShadowPullResponseS2CPacket response = ShadowPullServer.handleRequest(handler, player, request);
            FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                response.encode(out);
                return readAll(out);
            } finally {
                out.release();
            }
        } finally {
            buf.release();
        }
    }

    // ===== 服务端发送端编码 =====

    /**
     * dictionary_sync body 字节（{@link DictionarySyncPayload} 布局；null 字典
     * 按空字典发送，与 Forge/NeoForge 端原守卫一致）。
     */
    public static byte[] encodeDictionarySyncBody(byte[] dictionary) {
        byte[] dict = dictionary != null ? dictionary : new byte[0];
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            new DictionarySyncPayload(dict, false).encode(buf);
            return readAll(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * index_sync 信封字节（varint 长度前缀 + {@link IndexSyncPacket} 编码）。
     * 内层信封；外层封装（1.21.1+ payload codec / Forge Wrapper 的 varint
     * 前缀）由 loader 载体决定，与重构前线格式一致。
     */
    public static byte[] encodeIndexSyncEnvelope() {
        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
        indexSyncManager.initializeServerIndex();
        IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
        byte[] data = syncPacket.encode();
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
            return readAll(buf);
        } finally {
            buf.release();
        }
    }
}
