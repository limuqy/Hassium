package io.github.limuqy.mc.hassium.config;

/**
 * 配置键所属功能域（2.0.0 三核心 + 支撑域，见 key-mapping.md / domain-naming.md）。
 * <p>
 * 驱动文档分组呈现，为未来自动化核对配置键归属铺路。
 */
public enum Domain {
    /** 区块核心（客户端缓存/超视渲染/光照/SeedGen + 服务端光照剥离）。 */
    CHUNK_CORE,
    /** 主控核心（服务端网络行为：压缩/聚合/推送）。 */
    MASTER_CORE,
    /** 存储域。 */
    STORAGE,
    /** 兼容性（支撑设施）。 */
    COMPAT,
    /** 调试（支撑设施）。 */
    DEBUG
}
