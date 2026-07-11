package io.github.limuqy.mc.hassium.api;

import io.github.limuqy.mc.hassium.compression.CompressionCodec;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.metrics.HassiumMetrics;
import io.github.limuqy.mc.hassium.storage.HassiumRegionStorage;

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
     * 获取指定维度的存储实例
     *
     * @param dimension 维度标识符，例如 "minecraft:overworld"
     * @return 存储实例，如果该维度未启用存储则返回空
     */
    Optional<HassiumRegionStorage> getStorage(String dimension);

    /**
     * 获取压缩编解码器
     *
     * @param algorithmId 算法标识符，例如 "hassium:zstd"
     * @return 编解码器实例，如果算法不可用则返回空
     */
    Optional<CompressionCodec> getCompressionCodec(String algorithmId);
}
