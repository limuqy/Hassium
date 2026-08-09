package io.github.limuqy.mc.hassium.network.core.outbound;

import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;

/**
 * 网关 outbound UDP 数据面门面（bulk 区块）。
 *
 * <p>复用现有 {@link DataPlaneClientLifecycle}（KCP 可靠会话 + UdpFrameCodec AES-GCM + 端点候选），
 * 不改其原挂载：客户端收到 S2C 握手尾部后经 {@link #start} 登记，由主线程按既有
 * {@code deferUdpStart/takePendingUdpStart} 语义消费；bulk 区块接收仍走
 * {@code DataPlaneClientBundle} 的 ChunkDispatcher 注入缝（T5 改指向 NetworkCore#dispatchS2C）。
 */
public final class UdpDataPlane {

    private static final UdpDataPlane INSTANCE = new UdpDataPlane();

    private UdpDataPlane() {
    }

    public static UdpDataPlane getInstance() {
        return INSTANCE;
    }

    /** 握手尾部带 UDP 数据面：登记启动（主线程消费；重复登记以最后一次为准）。 */
    public void start(UdpDataPlaneHandshakeTail.S2CTail tail) {
        DataPlaneClientLifecycle.getInstance().deferUdpStart(tail);
        DebugLogger.debug(LogType.NETWORK,
                "Hassium: Gateway deferred UDP dataplane start (epoch={}, udp={}, controlFailover={})",
                tail.connectionEpoch(), tail.hasUdpDataplane(), tail.hasControlFailover());
    }

    /** 停止 UDP 数据面（保留 lease；重连编排语义与现有 stopUdp 一致）。 */
    public void stop() {
        DataPlaneClientLifecycle.getInstance().stopUdp(false);
    }

    public boolean isBound() {
        return DataPlaneClientLifecycle.getInstance().isBound();
    }

    public long currentEpoch() {
        return DataPlaneClientLifecycle.getInstance().currentEpoch();
    }

    /** 心跳 tick（主线程侧周期调用；驱动 KCP update 与 lease 检查）。 */
    public void tick(long nowMs) {
        DataPlaneClientLifecycle.getInstance().tick(nowMs);
    }
}
