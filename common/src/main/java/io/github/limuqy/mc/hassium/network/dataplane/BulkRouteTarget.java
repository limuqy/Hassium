package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * Task 4 — bulk 路由的会话抽象。仅暴露 {@link UdpBulkRouter} 选路与入队所需的最小接口，
 * 让 router 单元测试可以注入 fake 而无需实例化 {@link ReliableDatagramSession}（其构造要起 KCP）。
 *
 * <p>生产实现：{@link ReliableDatagramSession}；测试 fake：在该 task 测试包内的轻量类。
 * 接口不引用 KCP/Netty/Minecraft 类型，可被 {@code common} 任意层调用。
 */
public interface BulkRouteTarget {

    /** 本会话所属 endpoint 的服务端下标（§{@code DataPlaneUdpServer.BoundEndpoint.endpointId}）。 */
    int endpointId();

    /** 配置的基础权重（{@code dataplane.udpListeners[].weight}）；health penalty 在此之上扣减。 */
    int weight();

    /** 未关闭 + 可写 + SRTT 在硬上限内；router 仅选 healthy 候选。 */
    boolean isHealthy();

    /** 关闭或入队字节超限；router 排除。 */
    boolean isWritable();

    /** 已关闭则排除（独立于 {@link #isWritable()}）。 */
    boolean isClosed();

    /** Lease 期内（主 TCP 断开后短暂排干窗口）才可作为 bulk 候选。 */
    boolean isLeaseActive(long nowMs);

    /** 观测指标，用于 health penalty 与 metrics 上报。 */
    ReliableDatagramSession.Metrics metrics();

    /**
     * 入队一条已 authenticated 应用帧（router 选定本 target 后调用）。
     * 实现保证 byte payload 所有权语义与 {@link ReliableDatagramSession#enqueueAuthenticated} 一致。
     *
     * @return 入队成功返回 true；不可写/已关闭返回 false（caller 视为失败，可能触发一次 drop）。
     */
    boolean enqueueAuthenticated(int type, byte[] payload);
}
