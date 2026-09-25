package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.client.ChunkAuthorityClient;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.protocol.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.server.ChunkAuthorityS2CPacket;
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
     * dictionary_sync（客户端）：校验 + 登记聚合字典快照（聚合包解码前置条件）。
     * <p>
     * 内容 hash 与服务端 id 不符的字典拒绝安装（截断/损坏不得静默上线）。
     * <p>
     * ACK 语义 =「index + 字典均已就绪」：激活 ACK 由 {@code ClientActivation.handleIndexSync}
     * 在 index 安装后统一发出（携带当前字典 epoch）；激活完成后的 dictionary_sync 是热推
     * offer，立即回 ACK 参与 rollout 门控。激活前不单独回 ACK，避免「字典已装、索引未装」
     * 时过早触发服务端 ENABLE+flush（缓冲帧因索引缺失解码失败）。
     */
    public static void handleDictionarySync(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        try {
            DictionarySyncPayload payload = DictionarySyncPayload.decode(buf);
            byte[] dict = payload.dictionary();
            if (payload.hasDictionaryInfo() && payload.dictionaryId() != 0) {
                long actual = DictionarySnapshot.hash(dict);
                if (actual != payload.dictionaryId()) {
                    LOGGER.error("Hassium: Dictionary sync content hash mismatch (id={}, actual={}, {} bytes); refusing install",
                            payload.dictionaryId(), actual, dict.length);
                    return;
                }
            }
            // 尾部扩展字段是否存在 = 服务端是否 epoch 感知（DICT 帧头写 epoch 的判定依据）
            DictionaryManager.markServerEpochAware(payload.hasDictionaryInfo());
            DictionaryManager.installAggregationSnapshot(
                    DictionarySnapshot.of(payload.dictionaryEpoch(), dict));
            LOGGER.debug("Hassium: Received aggregation dictionary ({} bytes, epoch={}, id={}, extended={})",
                    dict != null ? dict.length : 0, payload.dictionaryEpoch(),
                    payload.dictionaryId(), payload.hasDictionaryInfo());
            if (payload.hasDictionaryInfo()
                    && io.github.limuqy.mc.hassium.client.ClientActivation.isActivationAckSent()) {
                io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER
                        .sendAggregationReady(payload.dictionaryEpoch(), payload.dictionaryId());
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle dictionary sync", e);
        } finally {
            buf.release();
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
        } catch (DictionaryMissingException e) {
            // 断连拆解窗口：resetClientSession 已清字典，队列残余在途帧解码必失败——
            // 会话已结束，降噪跳过、不请求重同步（review 冒烟 forge 场实证）
            if (connection != null && !connection.isConnected()) {
                LOGGER.debug("Hassium: aggregation frame dropped after disconnect (dictionary session cleared)");
                return;
            }
            // 字典缺失/未知 epoch：请求服务端重发当前字典（节流），恢复当前 epoch 解码；
            // 恢复前的不可解帧丢弃并降噪记录
            if (io.github.limuqy.mc.hassium.client.ClientActivation.requestDictionaryResync()) {
                LOGGER.error("Hassium: aggregation frame rejected, dictionary missing ({}); "
                        + "requested dictionary resync from server", e.getMessage());
            } else {
                LOGGER.debug("Hassium: aggregation frame dropped (dictionary missing, resync in flight): {}",
                        e.getMessage());
            }
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
     * dictionary_sync body 字节（{@link DictionarySyncPayload} 布局；null 快照按
     * 「无字典」发送（空内容 + epoch 0 + id 0），与 Forge/NeoForge 端原守卫一致）。
     */
    public static byte[] encodeDictionarySyncBody(DictionarySnapshot snapshot) {
        byte[] dict = snapshot != null ? snapshot.data() : new byte[0];
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            // 扩展字段恒写（epoch/id 为 0 表示「无字典」的显式声明，客户端据此清空状态）
            new DictionarySyncPayload(dict, false,
                    snapshot != null ? snapshot.epoch() : 0,
                    snapshot != null ? snapshot.id() : 0L,
                    true).encode(buf);
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
