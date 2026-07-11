package io.github.limuqy.mc.hassium.concurrent;

/**
 * 异步任务类别
 * <p>
 * 用于区分在登出/断开连接时是否应取消后台任务。
 */
public enum TaskCategory {
    /**
     * 必须完成 —— 不允许取消（如缓存数据持久化）
     */
    MISSION_CRITICAL,

    /**
     * 可安全取消 —— 结果在断开连接后不再需要（如光照扫描、网络解压）
     */
    SAFE_TO_CANCEL,

    /**
     * 尽力完成 —— 可以取消，但完成有利于下次连接（如 SQLite 初始化、元数据读取）
     */
    BEST_EFFORT
}
