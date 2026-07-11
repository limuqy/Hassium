package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.platform.NeoForgePlatformHelper;
import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;

/**
 * NeoForge 通用初始化适配
 * 注册平台服务实现
 */
public class NeoForgeCommonClass {

    /**
     * 注册 NeoForge 平台实现
     */
    public static void registerServices() {
        // PlatformHelper 通过 ServiceLoader 自动发现
        // 此类用于额外的 NeoForge 特定初始化
    }
}
