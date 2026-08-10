package io.github.limuqy.mc.hassium.config;

/**
 * L1 迁移策略配置快照（B2 全链接线）。
 * <p>
 * 由 {@link HassiumConfigService#getMigrationPolicyConfig()} 从 {@code master.migration*}
 * 键组装，交给 {@link io.github.limuqy.mc.hassium.network.core.migration.MigrationEngine
 * #applyMigrationPolicyFromConfig}：policy 中仍为默认值的字段用本快照覆盖（程序化
 * {@code setPolicy} 优先）。silentTimeout 与 faultTimeout 的兼容回退见
 * {@link io.github.limuqy.mc.hassium.network.core.migration.MigrationPolicy
 * #resolvedSilentTimeoutMs()}。
 */
public record MigrationPolicyConfig(
        double minTps,
        double maxLoadAverage,
        String maintenanceWindow,
        long heartbeatIntervalMs,
        long idleWindowMs,
        long silentTimeoutMs,
        long faultTimeoutMs
) {
}
