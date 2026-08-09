package io.github.limuqy.mc.hassium.network.core.migration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 迁移策略（L1 骨架，REQ C 节）：负载阈值 + 维护窗口 + 心跳/故障参数。
 *
 * <p>纯数据不可变记录。默认值即骨架可用配置；运行时替换经
 * {@link MigrationEngine#setPolicy}。HassiumConfig 全链配置接线（TOML/cloth-ui）
 * 为后续波交接项——本记录 = 程序化配置入口。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code minTps}：主控 TPS 低于此值触发策略迁移（默认 15.0）</li>
 *   <li>{@code maxLoadAverage}：主控系统负载均值高于此值触发策略迁移
 *       （默认 4.0；{@code getSystemLoadAverage} 返回 -1（平台不支持）视为无信号）</li>
 *   <li>{@code maintenanceWindow}：维护窗口 "HH:MM-HH:MM"（本地时区，含端点），
 *       空串 = 禁用（默认）。窗口内策略判定恒触发（维护期主动迁移）</li>
 *   <li>{@code heartbeatIntervalMs}：客户端 HEARTBEAT 发送周期（默认 5000）</li>
 *   <li>{@code faultTimeoutMs}：outbound 入站静默超时（默认 60000，沿用既有
 *       {@code network.dataPlane.recoveryWindowMs} 语义；引擎启动时从配置读取覆盖）</li>
 *   <li>{@code prewarmEnabled}：迁移前是否先建立目标主控会话（预热，默认 true）</li>
 * </ul>
 */
public record MigrationPolicy(
        double minTps,
        double maxLoadAverage,
        String maintenanceWindow,
        long heartbeatIntervalMs,
        long faultTimeoutMs,
        boolean prewarmEnabled
) {

    public static final MigrationPolicy DEFAULT = new MigrationPolicy(15.0, 4.0, "", 5000L, 60000L, true);

    public MigrationPolicy {
        if (!(minTps > 0)) {
            throw new IllegalArgumentException("minTps must be positive");
        }
        if (!(maxLoadAverage > 0)) {
            throw new IllegalArgumentException("maxLoadAverage must be positive");
        }
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
        }
        if (faultTimeoutMs <= 0) {
            throw new IllegalArgumentException("faultTimeoutMs must be positive");
        }
    }

    /** 当前时刻是否处于维护窗口（窗口格式 "HH:MM-HH:MM"；空串/非法格式恒 false）。 */
    public boolean inMaintenanceWindow(long nowMs) {
        if (maintenanceWindow == null || maintenanceWindow.isBlank()) {
            return false;
        }
        int dash = maintenanceWindow.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        try {
            LocalTime now = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs),
                    java.time.ZoneId.systemDefault());
            LocalTime start = LocalTime.parse(maintenanceWindow.substring(0, dash), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end = LocalTime.parse(maintenanceWindow.substring(dash + 1), DateTimeFormatter.ofPattern("HH:mm"));
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            // 跨午夜窗口（如 22:00-06:00）
            return !now.isBefore(start) || now.isBefore(end);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
