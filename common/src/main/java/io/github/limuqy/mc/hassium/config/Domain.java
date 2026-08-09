package io.github.limuqy.mc.hassium.config;

/**
 * 配置键所属功能域（2.0.0 三核心 + 支撑域，见 key-mapping.md / domain-naming.md）。
 * <p>
 * 驱动文档分组呈现，为未来自动化核对配置键归属铺路。
 */
public enum Domain {
    /** 网络核心（客户端进程内网关与帧连接）。 */
    NETWORK_CORE,
    /** 区块核心（客户端缓存/超视渲染/光照/SeedGen + 服务端光照剥离）。 */
    CHUNK_CORE,
    /** 主控核心（服务端网络行为：压缩/聚合/推送/端点 + L1 迁移故障超时）。 */
    MASTER_CORE,
    /** 存储域。 */
    STORAGE,
    /** UDP 数据面（支撑域）。 */
    DATAPLANE,
    /** 兼容性（支撑设施）。 */
    COMPAT,
    /** 调试（支撑设施）。 */
    DEBUG
}
