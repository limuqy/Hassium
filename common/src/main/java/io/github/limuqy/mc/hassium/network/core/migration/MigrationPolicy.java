package io.github.limuqy.mc.hassium.network.core.migration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 迁移策略（L1 骨架，REQ C 节）：负载阈值 + 维护窗口 + 心跳/故障参数 + 空闲窗口。
 *
 * <p>纯数据不可变记录。默认值即骨架可用配置；运行时替换经
 * {@link MigrationEngine#setPolicy}。B2 全链接线：策略字段经
 * {@code master.migration*} 配置键族接线（TOML/cloth-ui），引擎启动时由
 * {@link MigrationEngine#applyMigrationPolicyFromConfig} 按「字段仍为默认值才覆盖」
 * 规则应用配置快照（程序化 setPolicy 优先）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code minTps}：主控 TPS 低于此值触发策略迁移（默认 15.0）</li>
 *   <li>{@code maxLoadAverage}：主控系统负载均值高于此值触发策略迁移
 *       （默认 4.0；{@code getSystemLoadAverage} 返回 -1（平台不支持）视为无信号）</li>
 *   <li>{@code maintenanceWindow}：维护窗口 "HH:MM-HH:MM"（本地时区，含端点），
 *       空串 = 禁用（默认）。窗口内策略判定恒触发（维护期主动迁移）</li>
 *   <li>{@code heartbeatIntervalMs}：客户端 HEARTBEAT 发送周期（默认 5000）</li>
 *   <li>{@code silentTimeoutMs}：outbound 入站静默超时（默认 10000，N2 快速失效：
 *       默认失效识别 ≤15s）。显式配置（≠默认）时优先；未配置时回退
 *       {@code faultTimeoutMs}（既有 {@code master.migrationFaultTimeoutMs} 语义，
 *       见 {@link #resolvedSilentTimeoutMs()}）</li>
 *   <li>{@code faultTimeoutMs}：legacy 静默超时（默认 60000，沿用既有
 *       {@code master.migrationFaultTimeoutMs} 语义；silentTimeout 未配置时的回退值）</li>
 *   <li>{@code idleWindowMs}：空闲窗口判定时长（默认 10000；原 MigrationEngine 常量移入）</li>
 *   <li>{@code idleMoveThresholdBps}：空闲判定位移阈值，方块/秒（默认 0.5；原
 *       MigrationEngine 常量移入，无独立配置键）</li>
 *   <li>{@code prewarmEnabled}：迁移前是否先建立目标主控会话（预热，默认 true）</li>
 * </ul>
 */
public record MigrationPolicy(
        double minTps,
        double maxLoadAverage,
        String maintenanceWindow,
        long heartbeatIntervalMs,
        long silentTimeoutMs,
        long faultTimeoutMs,
        long idleWindowMs,
        double idleMoveThresholdBps,
        boolean prewarmEnabled
) {

    public static final MigrationPolicy DEFAULT =
            new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, true);

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
        if (silentTimeoutMs <= 0) {
            throw new IllegalArgumentException("silentTimeoutMs must be positive");
        }
        if (faultTimeoutMs <= 0) {
            throw new IllegalArgumentException("faultTimeoutMs must be positive");
        }
        if (idleWindowMs <= 0) {
            throw new IllegalArgumentException("idleWindowMs must be positive");
        }
        if (!(idleMoveThresholdBps > 0)) {
            throw new IllegalArgumentException("idleMoveThresholdBps must be positive");
        }
    }

    /**
     * 生效静默超时（N2 失效判定用）：
     * <ul>
     *   <li>silentTimeoutMs 显式配置（≠默认 10000）→ 用之（新键优先）；</li>
     *   <li>否则 faultTimeoutMs 显式配置（≠默认 60000）→ 用之（既有
     *       {@code master.migrationFaultTimeoutMs} 兼容语义）；</li>
     *   <li>两者均未配置 → 默认 10000ms（默认失效识别 ≤15s）。</li>
     * </ul>
     */
    public long resolvedSilentTimeoutMs() {
        if (silentTimeoutMs != DEFAULT.silentTimeoutMs()) {
            return silentTimeoutMs;
        }
        if (faultTimeoutMs != DEFAULT.faultTimeoutMs()) {
            return faultTimeoutMs;
        }
        return DEFAULT.silentTimeoutMs();
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
