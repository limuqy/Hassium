package io.github.limuqy.mc.hassium.utils;

/**
 * 服务器标识（serverId）目录名工具。
 * <p>
 * review-fix: T8-27: serverId sanitize 逻辑曾在 ClientLifecycleHelper（两处）与
 * HassiumCommandHandler 各复制一份——新增调用点忘 sanitize 即重开任意写入口（路径穿越）。
 * 收敛为单一工具方法，所有调用点必须经此构造。
 */
public final class ServerIdUtil {
    private ServerIdUtil() {}

    /**
     * 将服务器 IP:Port 转换为缓存目录名。
     * <ul>
     *   <li>白名单 {@code [a-zA-Z0-9._-]}，其余字符替换为 {@code _}（防路径分隔符/穿越）</li>
     *   <li>固定 {@code server_} 前缀（防空串/相对路径退化为任意目录）</li>
     * </ul>
     * 例：{@code 127.0.0.1:25565} → {@code server_127.0.0.1_25565}。
     */
    public static String sanitize(String serverIp) {
        String sanitized = serverIp.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "server_" + sanitized;
    }
}
