package io.github.limuqy.mc.hassium.storage;

/**
 * 存储模式枚举
 */
public enum StorageMode {
    /**
     * 只读原版：只读取原版可识别 payload，不读取 Hassium 扩展数据
     */
    READONLY_VANILLA("readonly_vanilla"),

    /**
     * 镜像模式：原版数据作为权威存储，Hassium 扩展数据作为旁路缓存
     */
    MIRROR("mirror"),

    /**
     * Hassium 专属：优先读取 Hassium envelope，失败时按配置决定是否回退原版
     */
    HASSIUM_ONLY("hassium_only");

    private final String configName;

    StorageMode(String configName) {
        this.configName = configName;
    }

    /**
     * 从配置字符串解析存储模式
     */
    public static StorageMode fromConfig(String config) {
        for (StorageMode mode : values()) {
            if (mode.configName.equalsIgnoreCase(config)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown storage mode: " + config);
    }

    /**
     * 获取配置名称
     */
    public String getConfigName() {
        return configName;
    }
}
