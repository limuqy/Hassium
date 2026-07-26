package io.github.limuqy.mc.hassium.network.dataplane;

import io.jpower.kcp.netty.Kcp;
import io.jpower.kcp.netty.KcpMetric;
import io.jpower.kcp.netty.KcpOutput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 有界 KCP 可靠数据报会话（Task 2）。
 *
 * <p>把 {@link Kcp}（kcp-netty 1.6.2）封装成面向应用层的可靠数据报 façade。
 * 路由 / Minecraft 业务代码 MUST NOT 直接调用 {@link Kcp} —— 只走本类公共接口。
 *
 * <p>组合：
 * <ul>
 *   <li>应用帧（type 1–9 + payload）先经 {@link UdpFrameCodec#seal} 加密封装成 {@code sequence[u64明文头] + AEAD}，
 *       再交给 {@code kcp.send} 作可靠、有界、按 MTU 分片、按序、去重、有拥塞控制的传输。</li>
 *   <li>对端经 {@code kcp.input} 喂回 KCP 线字节；KCP 重组出完整密封帧后 {@code kcp.recv}，
 *       本类再 {@link UdpFrameCodec#open} 解封并校验单调序列号、通过 {@link #receiveHandler} 投递至调用方。</li>
 *   <li>{@link DatagramSink} 注入实际线字节发送；本类不打开任何真实 socket —— socket/router 接线归 Task 3。</li>
 * </ul>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>MTU=1200、{@code nodelay(true, 10, 2, nocwnd=false)} 启用拥塞控制、本地发送/接收窗口有界。</li>
 *   <li>KCP {@code stream=false}（消息模式）：每个密封帧作为一条完整消息一次 {@code recv} 回来。</li>
 *   <li>每条应用帧的 sequence 单调递增且每个方向不复用；同一 (key,direction) 下 nonce 不重复。</li>
 *   <li>{@link #isWritable()} 在关闭或待可靠投递的应用字节 {@code >= maxQueuedAppBytes} 时为 false。</li>
 *   <li>{@link #isHealthy()} 要求未关闭 @{@code +} 可写 @{@code +} SRTT {@code <= hardRttMs}。</li>
 *   <li>{@link #close()} 幂等，释放 {@link Kcp} 与本类持有的 ByteBuf；不占用调用方传入的 payload/ByteBuf 所有权。</li>
 *   <li>本类 MUST NOT 从 UDP 事件循环调用任何 Minecraft handler —— 只能回调注入的 {@link Consumer}。</li>
 * </ul>
 */
public final class ReliableDatagramSession implements BulkRouteTarget {

    /** 投递单个 KCP 线字节的发送口；本类持有 {@code ByteBuf} 的所有权以保持显式释放语义。 */
    public interface DatagramSink {
        void send(ByteBuf datagram);
    }

    /** 已解封、已鉴权的应用帧，投递给 {@link #receiveHandler(Consumer)} 设定的消费者。 */
    public record Received(long sequence, int type, byte[] payload) {}

    /** 会话观测指标。 {@code packetsLost} 取自 KCP 累计重传次数（每次重传代表一次确认此前丢包）。 */
    public record Metrics(long srttMs, long packetsLost, int queuedBytes, boolean writable) {}

    private static final int TICK_INTERVAL = 10; // ms — 与 nodelay(interval=10) 一致

    private final UUID playerId;
    private final long epoch;
    private final UdpEndpoint endpoint;
    private final InetSocketAddress remote;
    private final byte[] key;                 // 16-byte AES key; held defensively
    private final DatagramSink sink;
    private final UdpFrameCodec.Direction sealDirection;
    private final int conv;
    private final int maxReassemblyBytes;
    private final int maxQueuedAppBytes;
    private final long hardRttMs;
    // Task 4 — bulk router 所需的身份/权重；旧构造默认 endpointId=-1, weight=1（兼容 Task 2 测试）。
    private final int endpointId;
    private final int weight;
    /**
     * 仅供同包测试覆盖的 Allocator 缝隙：非空时使用它，否则用 {@link PooledByteBufAllocator#DEFAULT}。
     * 包私有（非 public surface），不影响 Task 3 公共接口；生产路径恒为 null。
     */
    static volatile ByteBufAllocator ALLOC_OVERRIDE = null;
    private final ByteBufAllocator alloc = (ALLOC_OVERRIDE != null) ? ALLOC_OVERRIDE : PooledByteBufAllocator.DEFAULT;

    private final Kcp kcp;
    private final KcpOutput kcpOutput;

    private Consumer<Received> consumer = r -> {};
    private long sealSequence = 0L;       // 单调；每次 seal 自增
    private long minReceivedServerToClient = 0L;
    private long minReceivedClientToServer = 0L;
    private long outstandingAppBytes = 0L; // 待可靠投递的应用字节（payload 长度口径）
    private long packetsLost = 0L;         // 累计重传次数代理丢包
    private long lastXmitSnap = -1L;
    private boolean closed = false;
    // Task 3 UDP lease：主 TCP 断开后保留会话以排干已 accepted 的帧；非 lease 期恒 false。
    private volatile long leaseExpireAt = Long.MAX_VALUE;

    public ReliableDatagramSession(UUID playerId, long epoch, UdpEndpoint endpoint,
                                   InetSocketAddress remote, byte[] key, DatagramSink sink) {
        this(playerId, epoch, endpoint, remote, key, sink, -1, 1);
    }

    /**
     * Task 4 完整构造：注入 {@code endpointId}/{@code weight}（由 {@link DataPlaneUdpServer} dispatcher
     * 在 Bind 成功路径传入），供 {@link UdpBulkRouter} 作 WRR base weight。
     */
    public ReliableDatagramSession(UUID playerId, long epoch, UdpEndpoint endpoint,
                                   InetSocketAddress remote, byte[] key, DatagramSink sink,
                                   int endpointId, int weight) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.epoch = epoch;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.remote = Objects.requireNonNull(remote, "remote");
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }
        this.key = key.clone();
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sealDirection = endpoint.role().sealDirection();
        this.conv = computeConv(playerId, epoch);
        this.maxReassemblyBytes = endpoint.maxReassemblyBytes();
        this.maxQueuedAppBytes = endpoint.maxQueuedAppBytes();
        this.hardRttMs = endpoint.hardRttMs();
        this.endpointId = endpointId;
        this.weight = weight < 1 ? 1 : weight;

        this.kcpOutput = this::onKcpOutput;
        this.kcp = new Kcp(conv, kcpOutput);
        configureKcp(kcp, endpoint);
        // KCP 的输出/分段 buffer 统一走本类的 alloc（生产恒为 PooledByteBufAllocator.DEFAULT，与 KCP 默认一致；
        // 测试可经 ALLOC_OVERRIDE 注入计数包装以观测 F1 输出 buffer 泄漏）。
        kcp.setByteBufAllocator(alloc);
    }

    private static void configureKcp(Kcp kcp, UdpEndpoint ep) {
        kcp.setMtu(ep.mtu());
        // nodelay(true, interval, resend, nocwnd=false => congestion control ENABLED)
        kcp.nodelay(true, TICK_INTERVAL, 2, false);
        kcp.setRxMinrto(30);
        kcp.wndsize(ep.sndWindow(), ep.rcvWindow());
        kcp.setStream(false); // 消息模式：保留应用消息边界
        kcp.setAutoSetConv(false);
    }

    /** 注册解封后应用帧投递回调；调用方线程无关，但本类绝不在此回调内调 Minecraft handler。 */
    public synchronized ReliableDatagramSession receiveHandler(Consumer<Received> consumer) {
        this.consumer = Objects.requireNonNullElse(consumer, r -> {});
        return this;
    }

    /** 接收一个对端发出的 KCP 线字节 datagram，喂入 KCP 并立即尝试解出完整应用帧。 */
    public synchronized void receive(ByteBuf datagram, long nowMs) {
        if (closed || datagram == null || !datagram.isReadable()) {
            return;
        }
        try {
            kcp.input(datagram);
        } catch (Throwable ignored) {
            // 损坏/不可解析 datagram 静默丢弃——KCP 自身对恶意输入有保护；这里不传播至事件循环。
            return;
        } finally {
            // KCP input 通过 readRetainedSlice 取走自己的 retain，调用方仍持所有权——释放调用方传入的 buf。
            datagram.release();
        }
        drainReceived();
    }

    /**
     * 入队一条已加密待发应用帧：先 seal，再 kcp.send。
     *
     * <p>Task 4 起返回 boolean：{@code true} 已入队；{@code false} 在已关闭、不可写或 KCP 拒收时
     * 返回（不抛）。原 throws-IllegalState 语义在 router 路径下会污染 bulk 主路径；改成显式 false 后
     * 由 {@link UdpBulkRouter} 视为一次 drop。
     */
    public synchronized boolean enqueueAuthenticated(int type, byte[] payload) {
        if (closed) {
            return false;
        }
        if (!isWritable()) {
            return false;
        }
        byte[] ownedPayload = payload == null ? new byte[0] : payload;
        long seq = sealSequence++;
        byte[] sealed = UdpFrameCodec.seal(key, sealDirection, seq, type, ownedPayload);
        ByteBuf buf = alloc.buffer(sealed.length);
        try {
            buf.writeBytes(sealed);
            int code = kcp.send(buf);
            if (code < 0) {
                // KCP 发送队列拒收：不再回退 sealSequence —— 哪怕该帧未出门也绝不复用该序列号/nonce
                // （GCM nonce 复用是不可接受的灾难。重试时分配新的 seq 即可）。
                return false;
            }
            outstandingAppBytes += ownedPayload.length;
            drainReceived();
            return true;
        } finally {
            buf.release();
        }
    }

    /** 推进 KCP 定时器，触发重传/ACK 探测/PM 发包。 */
    public synchronized void tick(long nowMs) {
        if (closed) {
            return;
        }
        try {
            kcp.update(msInt(nowMs));
        } catch (Throwable ignored) {
            // KCP update 不应抛；吞掉防止事件循环炸裂
        }
        drainReceived();
        releaseAcked();
        refreshLossMetric();
    }

    public synchronized boolean isWritable() {
        if (closed) {
            return false;
        }
        return outstandingAppBytes < maxQueuedAppBytes;
    }

    public synchronized boolean isHealthy() {
        if (closed || !isWritable()) {
            return false;
        }
        try {
            KcpMetric m = kcp.getMetric();
            long srtt = m.srtt();
            // SSTT==0 表示尚未采样——青年会话仍可视为健康。
            if (srtt > 0 && srtt > hardRttMs) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized Metrics metrics() {
        long srtt = 0L;
        try {
            srtt = Math.max(0L, kcp.getMetric().srtt());
        } catch (Throwable ignored) {}
        int queued = (int) Math.min(Integer.MAX_VALUE, outstandingAppBytes);
        return new Metrics(srtt, packetsLost, queued, isWritable());
    }

    /** 幂等关闭：释放 {@link Kcp} 与已保留的 ByteBuf；不再接收/投递任何东西。 */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { kcp.release(); } catch (Throwable ignored) {}
        outstandingAppBytes = 0;
    }

    // ---------- internals ----------

    /**
     * KCP flush 回调：把「待发往 wire」的 datagram 交给 {@link DatagramSink}。
     * <p><b>所有权（已由 javap 确认）</b>：KCP {@code output(ByteBuf,Kcp)} 在回调返回后
     * <b>不会</b> release 原 {@code buf}——它只是把内存交给回调。故本类对 {@code buf} 负责显式释放。
     * 规则：先 {@code retainedDuplicate()} 给 sink 一份独立 retain（sink 自行处置该 dup），
     * {@code sink.send} 返回后无论成败一律 {@code buf.release()} 释放本类持有的原 buf，
     * 避免 KCP 不释放导致每个发出的 datagram 泄漏一个 pool direct buffer（直内存耗尽）。
     * dup 的所有权在 {@link DatagramSink}：sink 若不再持有须自行 release。
     */
    private void onKcpOutput(ByteBuf buf, Kcp k) {
        if (closed) {
            // 会话已关闭：直接释放 KCP 交来的 buf，避免泄漏。
            buf.release();
            return;
        }
        ByteBuf dup = buf.retainedDuplicate();
        try {
            sink.send(dup);
        } catch (Throwable ignored) {
            // sink 异常：dup 未被 sink 接管，本类负责释放 dup 自身的 retain。
            dup.release();
        } finally {
            // KCP 不 release 原 buf（javap 确认），本类统一释放，杜绝单 datagram 直内存泄漏。
            buf.release();
        }
    }

    /** 把已重组完整的应用帧 drain 出来并解封投递。 */
    private void drainReceived() {
        while (!closed && kcp.canRecv()) {
            int peek;
            try {
                peek = kcp.peekSize();
            } catch (Throwable t) {
                break;
            }
            if (peek <= 0) {
                break;
            }
            if (peek > maxReassemblyBytes) {
                // 超出重组预算：PREVENTIVE 丢弃整条，绝不先申请 peek-sized buffer（否则恶意对端可用
                // 单条超长帧触发 maxReassemblyBytes 级直内存放大）。KCP 在重组完成前其每个分片已是
                // per-MTU(~1200) 的 pooled buffer；这里用 kcp.recv(List)「移交既有分片」而非 alloc.buffer(peek)
                // 「重申请整块」——逐分片接收后立即 release，回收 KCP 已占用的分片直内存，不新增任何分配。
                drainOversizedMessage();
                continue;
            }
            ByteBuf recvBuf = alloc.buffer(peek);
            int read;
            try {
                read = kcp.recv(recvBuf);
            } catch (Throwable t) {
                recvBuf.release();
                break;
            }
            if (read <= 0) {
                recvBuf.release();
                break;
            }
            // 拷出字节交由 UdpFrameCodec 解封；recvBuf 随即释放。
            byte[] sealed = new byte[read];
            recvBuf.readBytes(sealed);
            recvBuf.release();

            UdpFrameCodec.Opened opened;
            try {
                // 同时支持对端两个方向 seal——按帧方向解封（这里靠 try/switch 两路试探）。
                opened = openAnyDirection(sealed);
            } catch (Throwable t) {
                // 鉴权失败/方向错/重放——丢弃单条帧，不向事件循环传播异常。
                continue;
            }
            Consumer<Received> cb = this.consumer;
            try {
                cb.accept(new Received(opened.sequence(), opened.type(), opened.payload()));
            } catch (Throwable ignored) {
                // 消费者异常不得阻断接收循环
            }
        }
    }

    /**
     * 丢弃 KCP 已重组但超出 {@code maxReassemblyBytes} 的单条超大消息——PREVENTIVE：不申请任何
     * {@code peek}-sized buffer。借助 {@code kcp.recv(List<ByteBuf>)}（kcp-netty 1.6.2）从 KCP 接收队列
     * 「移交」该消息已有的 per-MTU 分片 buffer，逐片立即 {@code release()} 回收其直内存，再清空 CPU-side
     * list。如此既把消息从 KCP 接收窗口移走（防止阻塞后续正常帧），又绝不触发 {@code alloc.buffer(peek)}
     * 这类在恶意峰值（最高 {@code rcvWnd * mss}）上的大块直内存放大。
     *
     * <p>正确性依据（已由 javap 确认）：{@code kcp.recv(List<ByteBuf>)} 不校验 buffer 容量（无 -3 路径），
     * 只是把 head 处完整消息对应的各分片 {@code Segment.data} ByteBuf {@code add} 进 list 并把它们从队列移除、
     * 回收回 KCP 内部对象池；返回首个 {@code frg==0} 时累积的 readableByte 数 (= peek)。
     */
    private void drainOversizedMessage() {
        java.util.List<ByteBuf> fragments = new ArrayList<>();
        try {
            kcp.recv(fragments);
        } catch (Throwable ignored) {
            // KCP recv 异常：尽力释放已取出的分片，避免二次泄漏；丢弃并继续接收循环。
        } finally {
            for (ByteBuf frag : fragments) {
                try {
                    frag.release();
                } catch (Throwable ignored) {}
            }
            fragments.clear();
        }
    }

    /**
     * 用「与本地 seal 相反」的方向解封一条对端来的应用帧；维护对端在该方向上的单调重放门限。
     * （对端角色必与本地相反——SERVER↔CLIENT——因此其 seal 方向也必为本地 sealDirection 的反方向。）
     */
    private UdpFrameCodec.Opened openAnyDirection(byte[] sealed) {
        UdpFrameCodec.Direction peerDir =
                (sealDirection == UdpFrameCodec.Direction.SERVER_TO_CLIENT)
                        ? UdpFrameCodec.Direction.CLIENT_TO_SERVER
                        : UdpFrameCodec.Direction.SERVER_TO_CLIENT;
        long minPeer = (peerDir == UdpFrameCodec.Direction.SERVER_TO_CLIENT)
                ? minReceivedServerToClient : minReceivedClientToServer;
        UdpFrameCodec.Opened opened = UdpFrameCodec.open(key, peerDir, minPeer, sealed);
        if (peerDir == UdpFrameCodec.Direction.SERVER_TO_CLIENT) {
            minReceivedServerToClient = opened.sequence() + 1;
        } else {
            minReceivedClientToServer = opened.sequence() + 1;
        }
        return opened;
    }

    /** 当 KCP 发送队列排空时（waitSnd()==0）回收并清零 outstandingAppBytes；以「整体排空」为粗粒度却诚实的 ack 信号。 */
    private void releaseAcked() {
        try {
            if (outstandingAppBytes > 0 && kcp.waitSnd() == 0) {
                outstandingAppBytes = 0;
            }
        } catch (Throwable ignored) {}
    }

    /** 从 KCP xmit 累计重传指针推导累计丢包次数（重传 == 此前确认丢包）。 */
    private void refreshLossMetric() {
        try {
            long xmit = kcp.getMetric().xmit();
            if (lastXmitSnap < 0) {
                lastXmitSnap = xmit;
            } else if (xmit > lastXmitSnap) {
                packetsLost += (xmit - lastXmitSnap);
                lastXmitSnap = xmit;
            }
        } catch (Throwable ignored) {}
    }

    private static int msInt(long nowMs) {
        return (int) (nowMs & 0xFFFFFFFFL);
    }

    private static int computeConv(UUID playerId, long epoch) {
        long hi = playerId.getMostSignificantBits();
        long lo = playerId.getLeastSignificantBits();
        long mix = hi ^ lo ^ Long.hashCode(epoch) ^ ((epoch << 32) | (epoch >>> 32));
        return (int) (mix ^ (mix >>> 32));
    }

    UUID playerId() { return playerId; }
    long epoch() { return epoch; }
    UdpEndpoint endpoint() { return endpoint; }
    InetSocketAddress remote() { return remote; }

    // ---------- BulkRouteTarget ----------
    // isHealthy / isWritable / metrics / enqueueAuthenticated / isLeaseActive 与本类 public 同签名方法自动实现接口；
    // 仅 endpointId / weight 需新增。
    @Override public int endpointId() { return endpointId; }
    @Override public int weight() { return weight; }

    /** 会话是否已关闭（独立于 isWritable，幂等 close 后为 true）。 */
    public boolean isClosed() { return closed; }

    /**
     * 标记 lease 起算与时长；非 lease 期恒为 {@code Long.MAX_VALUE}，{@code isLeaseActive} 返回 false。
     * 务实并发读：leaseExpireAt 用 volatile，{@code markLease} 由 registry 单线程（event loop）串行调用。
     */
    public void markLease(long nowMs, long leaseMs) {
        leaseExpireAt = (leaseMs <= 0L) ? Long.MAX_VALUE : nowMs + leaseMs;
    }

    /**
     * 进入排干状态直到绝对截止时刻。排干会话仍可由 KCP 投递已接受的帧，但不得再承接新的 bulk。
     * 仅 {@link DataPlaneSessionRegistry} 调用，确保 deadline 由单一注册表维护。
     */
    void markLeaseUntil(long expireAtMs) {
        leaseExpireAt = expireAtMs;
    }

    /** 是否已进入 lease 排干状态（无论 deadline 是否已经过期；过期关闭由 registry 统一执行）。 */
    boolean isLeaseDraining() {
        return leaseExpireAt != Long.MAX_VALUE;
    }

    /** 当前时刻是否处于 lease 期（主 TCP 已断但已 accepted 的帧仍可排干的窗口内）。 */
    public boolean isLeaseActive(long nowMs) {
        return nowMs < leaseExpireAt;
    }
}
