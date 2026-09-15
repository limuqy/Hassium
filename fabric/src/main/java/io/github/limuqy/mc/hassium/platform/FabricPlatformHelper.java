package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /**
     * 影子 PackRepository：显式追加 Fabric ModResourcePackCreator(SERVER_DATA)。
     * ResourcePackManagerMixin 对本项目 ServerPacksSource 形态未自动挂载（tf3：
     * available 仅 vanilla/bundle/trade_rebalance），TF/AoA 维度 JSON 进不了
     * LEVEL_STEM。
     */
    @Override
    public net.minecraft.server.packs.repository.RepositorySource[] serverPackSources(
            net.minecraft.server.packs.repository.RepositorySource vanilla) {
        return new net.minecraft.server.packs.repository.RepositorySource[] {
                vanilla,
                new net.fabricmc.fabric.impl.resource.loader.ModResourcePackCreator(
                        net.minecraft.server.packs.PackType.SERVER_DATA)
        };
    }
}
