package io.github.limuqy.mc.promethium.light;

/**
 * 并行光照引擎配置注入面。
 * <p>
 * 由宿主（Hassium）实现，引擎仅消费。装配于 {@code configure(...)}，引擎内部
 * 每次读取（而非缓存副本），宿主侧配置热更即时生效。
 */
public interface LightEngineConfig {

    /** 并行光照后台线程数。 */
    int lightThreads();

    /** 是否启用验算（官方引擎从零重算对照，纯观察）。 */
    boolean lightVerifyEnabled();

    /** 是否启用光照缓存（关闭时剥离路径不写缓存）。 */
    boolean lightCacheEnabled();
}
