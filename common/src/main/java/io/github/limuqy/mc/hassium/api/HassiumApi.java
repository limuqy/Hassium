package io.github.limuqy.mc.hassium.api;

import io.github.limuqy.mc.hassium.compression.CompressionCodec;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.metrics.HassiumMetrics;

import java.util.Optional;

/**
 * Hassium 模组的公共 API 接口
 */
public interface HassiumApi {

    /**
     * 获取 Hassium 版本
     */
    String getVersion();

    /**
     * 检查 Hassium 是否已启用
     */
    boolean isEnabled();

    /**
     * 获取配置
     */
    HassiumConfig getConfig();

    /**
     * 获取性能指标
     */
    HassiumMetrics getMetrics();

    /**
     * 获取压缩编解码器
     *
     * @param algorithmId 算法标识符，例如 "hassium:zstd"
     * @return 编解码器实例，如果算法不可用则返回空
     */
    Optional<CompressionCodec> getCompressionCodec(String algorithmId);
}
