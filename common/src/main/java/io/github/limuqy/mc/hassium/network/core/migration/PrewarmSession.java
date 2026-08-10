package io.github.limuqy.mc.hassium.network.core.migration;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预热会话（L1 骨架，REQ §C14）：迁移前先连目标主控、以续流票据建立玩家会话——
 * B 侧 {@code GatewayPlayerBridge} 物化玩家 + {@code resyncTrackedChunks} 预同步
 * （T12 已落地，[RESUME] 日志可验证），迁移时直接接管该连接（增量趋近零）。
 *
 * <p>独立于 {@link NetworkCore} 主监听器：预热握手完成前不触碰主状态机
 * （主 outbound 继续服务）。{@link Callback#onReady} 触发后由迁移引擎决定接管时机。
 *
 * <p><b>UDP 数据面决策（记录）</b>：帧连接即控制连接（T12 udpTail 恒
 * {@code udpSupported=false}、epoch=0）；UDP 数据面的迁移归后续波，预热/续流
 * 均只走 TCP 控制面。
 */
public final class PrewarmSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Prewarm");

    /** 预热结果回调（连接完成/失败各至多一次）。 */
    public interface Callback {
        /** 握手接受（resumeAccepted 可能为 false——票据无效/重放；是否接管由引擎判定）。 */
        void onReady(PrewarmSession session);

        /** 连接/握手失败。 */
        void onFailed(MigrationEndpoint endpoint, Throwable cause);
    }

    private final MigrationEndpoint endpoint;
    private final Callback callback;
    private final AtomicBoolean done = new AtomicBoolean(false);

    /** A-M3: 预热连接超时（10s 从 create 起算；到期未 ready/失败 → onFailed + close）。
     *  预热整体 TTL 沿用既有 B3 语义（master.migrationPrewarmTtlMs，服务端物化会话清扫），
     *  客户端只加连接超时。 */
    private static final long PREWARM_CONNECT_TIMEOUT_MS = 10_000L;

    private volatile OutboundConnection connection;
    private volatile boolean ready;
    private volatile boolean resumeAccepted;
    private volatile HandshakeCodec.ServerResponse handshakeResponse;
    private final AtomicLong prewarmBytesReceived = new AtomicLong();

    private PrewarmSession(MigrationEndpoint endpoint, Callback callback) {
        this.endpoint = endpoint;
        this.callback = callback;
    }

    /** 连接工厂（默认真实 NIO connect；测试注入 EmbeddedChannel 缝）。 */
    @FunctionalInterface
    public interface OutboundConnectionFactory {
        OutboundConnection create(String host, int port,
                                  HandshakeCodec.ClientRequestOptions options,
                                  HandshakeStateTail.C2S tail,
                                  OutboundConnection.Listener listener);
    }

    private static final OutboundConnectionFactory REAL_FACTORY =
            (host, port, options, tail, listener) -> OutboundConnection.connect(host, port, options, listener, tail);

    /** 建立到目标主控的真实预热连接（异步；回调在 event loop 线程触发）。 */
    public static PrewarmSession start(MigrationEndpoint endpoint,
                                       HandshakeStateTail.C2S tail,
                                       Callback callback) {
        return create(endpoint, tail, callback, REAL_FACTORY);
    }

    /** 测试缝：EmbeddedChannel 跑完整握手（core/migration 包测试用）。 */
    public static PrewarmSession openEmbedded(MigrationEndpoint endpoint,
                                              HandshakeStateTail.C2S tail,
                                              Callback callback) {
        return create(endpoint, tail, callback,
                (host, port, options, t, listener) -> OutboundConnection.openEmbedded(options, listener, t));
    }

    /** 经连接工厂创建（迁移引擎注入测试缝；握手请求携带续流状态尾）。 */
    public static PrewarmSession create(MigrationEndpoint endpoint,
                                        HandshakeStateTail.C2S tail,
                                        Callback callback,
                                        OutboundConnectionFactory connectionFactory) {
        PrewarmSession session = new PrewarmSession(endpoint, callback);
        session.connection = connectionFactory.create(endpoint.host(), endpoint.port(),
                HandshakeCodec.ClientRequestOptions.defaults(), tail, new Listener(session));
        // A-M3: 连接超时守卫（TCP connect 挂死/握手无响应 → 到期 onFailed + close）
        session.startConnectTimeout();
        return session;
    }

    public MigrationEndpoint endpoint() {
        return endpoint;
    }

    public OutboundConnection connection() {
        return connection;
    }

    public boolean ready() {
        return ready;
    }

    /** 终态（ready/失败/已关闭；终态会话不可复用，迁移引擎据此重建）。 */
    public boolean isTerminal() {
        return done.get();
    }

    public boolean resumeAccepted() {
        return resumeAccepted;
    }

    /** 预热握手响应（接管时供 NetworkCore applyHandshake：seedgen/udp/zstd）。 */
    public HandshakeCodec.ServerResponse handshakeResponse() {
        return handshakeResponse;
    }

    public void close() {
        OutboundConnection conn = connection;
        if (conn != null) {
            conn.close();
        }
        done.set(true);
    }

    /**
     * A-M3: 连接超时守卫（10s 从 create 起算，守护线程）：到期仍未终态 → 视为连接失败
     * （onFailed + close，回调驱动迁移直连兜底）。终态（ready/失败/关闭）后退出；
     * onFailed CAS 失败 = 已 ready/已失败，不再 close（防 ready 后误关待接管连接）。
     */
    private void startConnectTimeout() {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + PREWARM_CONNECT_TIMEOUT_MS;
            long remaining;
            while (!done.get() && (remaining = deadline - System.currentTimeMillis()) > 0) {
                try {
                    Thread.sleep(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!done.get()
                    && onFailed(new IOException("prewarm connect timed out after "
                            + PREWARM_CONNECT_TIMEOUT_MS + "ms: " + endpoint))) {
                close();
            }
        }, "Hassium-PrewarmTimeout");
        t.setDaemon(true);
        t.start();
    }

    // ---- 内部 ----

    private void onHandshakeAccepted(HandshakeCodec.ServerResponse response, boolean accepted) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        ready = true;
        resumeAccepted = accepted;
        handshakeResponse = response;
        // 主控可能即刻推送（resyncTrackedChunks 等）：接管前先装 ZSTD（与 NetworkCore 对称），
        // 入站数据先 drain（计数；接管时消费者被 NetworkCore 覆盖，数据开始流入世界侧）
        OutboundConnection conn = connection;
        if (conn != null) {
            conn.setS2CPayloadConsumer(buf -> {
                prewarmBytesReceived.addAndGet(buf.readableBytes());
                buf.release();
            });
            conn.setLoginS2CPayloadConsumer(buf -> {
                prewarmBytesReceived.addAndGet(buf.readableBytes());
                buf.release();
            });
            try {
                HassiumConfigService config = HassiumConfigService.getInstance();
                if (response.globalCompressionAccepted() && config.isNetworkCompressionEnabled()) {
                    conn.installZstd(config.getGlobalCompressionThreshold(),
                            config.getGlobalCompressionLevel());
                }
            } catch (Throwable t) {
                LOGGER.warn("[PREWARM] ZSTD install skipped: {}", t.toString());
            }
        }
        LOGGER.info("[PREWARM] session ready at {} (resume={})", endpoint, accepted);
        callback.onReady(this);
    }

    /** 预热期 drain 的入站字节（观测：B 侧预推送数据量）。 */
    public long prewarmBytesReceived() {
        return prewarmBytesReceived.get();
    }

    /** 失败收口（终态 CAS）；返回是否由本调用完成终态（超时守卫据此决定是否 close）。 */
    private boolean onFailed(Throwable cause) {
        if (!done.compareAndSet(false, true)) {
            return false;
        }
        LOGGER.warn("[PREWARM] session failed at {}: {}", endpoint, cause.toString());
        callback.onFailed(endpoint, cause);
        return true;
    }

    /** OutboundConnection.Listener 适配（预热连接独立于主状态机）。 */
    private static final class Listener implements OutboundConnection.Listener {

        private final PrewarmSession session;

        Listener(PrewarmSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(OutboundConnection connection) {
            // 握手请求已由 OutboundConnection 自动发出
        }

        @Override
        public void onHandshakeAccepted(HandshakeCodec.ServerResponse response) {
            onHandshakeAccepted(response, false);
        }

        @Override
        public void onHandshakeAccepted(HandshakeCodec.ServerResponse response, boolean resumeAccepted) {
            session.onHandshakeAccepted(response, resumeAccepted);
        }

        @Override
        public void onHandshakeRejected(String reason) {
            session.onFailed(new IOException("prewarm handshake rejected: " + reason));
            session.close();
        }

        @Override
        public void onError(Throwable cause) {
            session.onFailed(cause);
            session.close();
        }
    }
}
