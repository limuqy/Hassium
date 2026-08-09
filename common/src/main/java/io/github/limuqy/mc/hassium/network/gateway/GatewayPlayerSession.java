package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.netty.buffer.ByteBuf;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家会话（UUID-keyed，T3 事实表：per-player 状态进程内存）。
 *
 * <p>一条帧连接（{@link GatewayChannel}）在握手/登录桥确认玩家身份后附着为
 * 玩家会话，登记入 {@link GatewayPlayerRegistry}。会话是 S2C 推送出口
 * （{@link #sendS2CPayload} → PACKET_S2C 帧）与 C2S 注入入口
 * （{@link #setC2SSink} 挂平台处理链）。
 *
 * <p>线程模型：S2C 发送可任意线程（帧写线程安全）；C2S sink 回调在
 * Netty event loop 线程（实现体自行切线程）。
 */
public final class GatewayPlayerSession {

    private final UUID playerId;
    private final GatewayChannel channel;
    private final boolean resume;
    private final long resumeEpoch;
    private final PlayerStateReport stateReport;
    private final long registeredAtMillis = System.currentTimeMillis();
    private final AtomicLong s2cFramesSent = new AtomicLong();

    private volatile C2SPayloadSink c2sSink;

    GatewayPlayerSession(UUID playerId, GatewayChannel channel,
                         boolean resume, long resumeEpoch, PlayerStateReport stateReport) {
        this.playerId = playerId;
        this.channel = channel;
        this.resume = resume;
        this.resumeEpoch = resumeEpoch;
        this.stateReport = stateReport;
    }

    public UUID playerId() {
        return playerId;
    }

    public GatewayChannel channel() {
        return channel;
    }

    /** 续流会话（握手验票通过）？ */
    public boolean resume() {
        return resume;
    }

    /** 续流票据 epoch（非续流 = {@code Long.MIN_VALUE}）。 */
    public long resumeEpoch() {
        return resumeEpoch;
    }

    /** 握手时上报的玩家状态（未上报/登录桥附着 = null）。 */
    public PlayerStateReport stateReport() {
        return stateReport;
    }

    public long registeredAtMillis() {
        return registeredAtMillis;
    }

    /**
     * 发送一条 S2C 包 payload（原版 S2C 包编码后）回网关客户端。
     * payload 所有权移交（本方法负责 release）。计数可验证。
     */
    public void sendS2CPayload(ByteBuf payload) {
        if (payload == null) {
            return;
        }
        s2cFramesSent.incrementAndGet();
        channel.sendS2CPayload(payload);
    }

    public long s2cFramesSent() {
        return s2cFramesSent.get();
    }

    /**
     * 挂载 C2S 帧注入缝（平台侧：解码原版 C2S 包 → 注入玩家处理链）。
     * 未挂载时帧仅计数 + 日志（骨架阶段可验证）。
     */
    public void setC2SSink(C2SPayloadSink sink) {
        this.c2sSink = sink;
    }

    public C2SPayloadSink c2sSink() {
        return c2sSink;
    }

    public String describe() {
        return "session{" + playerId + (resume ? ", resume, epoch=" + resumeEpoch : ", login") + "}";
    }
}
