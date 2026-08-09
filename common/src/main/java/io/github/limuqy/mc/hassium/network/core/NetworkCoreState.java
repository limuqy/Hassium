package io.github.limuqy.mc.hassium.network.core;

/**
 * 网络核心（网关）状态机。
 *
 * <pre>
 *   IDLE ──onLogin/connect──▶ CONNECTING ──onOpen(握手已发)──▶ HANDSHAKING
 *   HANDSHAKING ──onHandshakeAccepted──▶ ACTIVE
 *   ACTIVE ──migrate──▶ MIGRATING ──onHandshakeAccepted(新主控)──▶ ACTIVE
 *   任意 ──onDisconnect/onError──▶ IDLE
 * </pre>
 *
 * <p>MIGRATING：网关内部切换 outbound 主控（L1 迁移引擎，REQ C 节）期间保持，
 * 客户端原版 Connection 状态零变化。
 */
public enum NetworkCoreState {
    /** 未连接（登录前 / 断连后）。 */
    IDLE,
    /** 正在建立到主控的 outbound 连接。 */
    CONNECTING,
    /** outbound 已建立、握手请求已发出，等待握手响应。 */
    HANDSHAKING,
    /** 握手接受，网关会话激活。 */
    ACTIVE,
    /** 主控切换中（旧 outbound 已断开 / 新 outbound 连接中），世界侧无感。 */
    MIGRATING
}
