package io.github.limuqy.mc.hassium.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * Check if the current environment is a physical client (has rendering capability).
     *
     * @return True if running on physical client, false on dedicated server.
     */
    boolean isPhysicalClient();

    /**
     * Gets the configuration directory for the current platform.
     *
     * @return The configuration directory path.
     */
    Path getConfigDirectory();

    /**
     * 把加载器 mod 数据包挂进影子端 {@code PackRepository}（SERVER_DATA）。
     * <p>
     * 影子 {@code ServerPacksSource} 默认只有原版服务端包；自定义维度（TF/AoA 等）
     * 的 {@code data/<ns>/dimension/*.json} 在 mod jar 里，不挂 mod pack 则
     * {@code datapackDimensions} 的 LEVEL_STEM 查不到、只能透传。
     * 默认空实现（无 mod 数据包贡献的平台）。
     */
    default void contributeServerDataPacks(
            net.minecraft.server.packs.repository.PackRepository repository) {
    }

    /**
     * 影子 PackRepository 的 RepositorySource 列表（构造期注入）。
     * <p>
     * Fabric 的 {@code ResourcePackManagerMixin} 在部分 ServerPacksSource 形态下
     * 不会自动挂 {@code ModResourcePackCreator}（tf3 实证 available 仅
     * vanilla/bundle/trade_rebalance），需在 common 构造时由平台显式追加。
     */
    default net.minecraft.server.packs.repository.RepositorySource[] serverPackSources(
            net.minecraft.server.packs.repository.RepositorySource vanilla) {
        return new net.minecraft.server.packs.repository.RepositorySource[] {vanilla};
    }

    /**
     * 影子虚拟玩家 stub 连接的平台网络协商。
     * <p>
     * NeoForge：{@code NetworkRegistry.configureMockConnection} 认为 stub 已协商全部
     * payload，否则 {@code placeNewPlayer} 期间 mod 发包（TF {@code sync_quests} 等）
     * 抛 {@code UnsupportedOperationException} 导致 tracking 会话创建失败 → OVD 恒 0。
     * 默认 no-op（Fabric 原版无此校验）。
     */
    default void prepareShadowStubConnection(net.minecraft.network.Connection connection) {
    }
}