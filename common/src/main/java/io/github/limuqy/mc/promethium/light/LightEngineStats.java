package io.github.limuqy.mc.promethium.light;

/**
 * 并行光照引擎指标注入面。
 * <p>
 * 由宿主（Hassium）实现并转发到其指标系统；引擎只负责在正确的事件点调用。
 */
public interface LightEngineStats {

    /** 记录后台线程光照重算耗时（纳秒）。 */
    void recordBackgroundTime(long nanos);

    /** 记录主线程光照落地 / 捕获耗时（纳秒）。 */
    void recordMainThreadTime(long nanos);

    /** 记录验算不一致的区块数。 */
    void recordVerifyMismatch(long count);
}
