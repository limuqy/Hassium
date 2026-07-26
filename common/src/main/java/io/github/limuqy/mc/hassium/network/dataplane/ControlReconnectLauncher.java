package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * Task 9 — 控制面重连 launcher seam（plan §991-993）。
 *
 * <p>common orchestrator 只调用这个接口；Fabric 客户端侧实现（
 * {@code FabricControlReconnectLauncher}）使用现有 Fabric multiplayer connect 调用，
 * <b>不反射复制 login 协议</b>（plan §1003 红线）。
 *
 * <p>语义：{@code connect(endpoint, onFailure)} 启动到该候选的全新 Minecraft multiplayer
 * 连接（与原版手动从多人列表加入同一入口）。失败时调用 {@code onFailure} —— 可能是同步或异步
 * 由实现决定；orchestrator 通过此回调处理"候选失败→下一候选→…→耗尽"。
 */
public interface ControlReconnectLauncher {
    /**
     * 启动一次到 {@code endpoint}的全新连接尝试。
     *
     * @param endpoint   控制面候选（host/port 间毫秒级偏好已由 {@link ControlEndpointManager} 排序选好）
     * @param onFailure  实现侧在确认该 endpoint 连接失败（被拒、不可达、login 失败）时调用；幂等安全
     */
    void connect(ControlEndpoint endpoint, Runnable onFailure);
}
