package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.Objects;

/**
 * Task 7 — 一个可重连的控制面候选项（play state TCP 入口）。
 *
 * <p>不可变值类型；{@code priority} 越大越优先；{@code host} 非空，{@code port} 1..65535，
 * {@code priority} ≥ 0。
 *
 * <p>比较基于 (host, port) —— 同坐标视为相同入口；{@code priority} 不影响 equality 以便候选去重统一。
 */
public record ControlEndpoint(String host, int port, int priority) {

    public ControlEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range 1..65535: " + port);
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be non-negative: " + priority);
        }
    }

    /** Coordinate key for de-duplication: host case-folded + port. */
    String coordinateKey() {
        return host.toLowerCase() + ":" + port;
    }
}
