package io.github.limuqy.mc.hassium.network.core.migration;

/**
 * 主控端点（L1 迁移引擎，REQ C 节）：迁移目标地址。
 *
 * <p>纯数据记录：{@code host:port}。端点列表来源（L1 骨架）：编程注入
 * （{@link MigrationEngine#setTargetEndpoints}）；主控在握手/CONFIG 帧通告端点
 * （T10 CONFIG 帧通道）为后续接线。
 */
public record MigrationEndpoint(String host, int port) {

    public MigrationEndpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("endpoint port out of range: " + port);
        }
    }

    /** 解析 "host:port"（无端口 → 25565）。 */
    public static MigrationEndpoint parse(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        String host = hostPort;
        int port = 25565;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
                host = hostPort.substring(0, colon);
            } catch (NumberFormatException e) {
                // 无端口后缀（或 IPv6 字面量），整体按 host 处理
            }
        }
        return new MigrationEndpoint(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
