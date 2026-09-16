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
     * <p>
     * 1.21.11+：resource-loader v1 把 Creator 挪到
     * {@code impl.resource.pack.ModResourcePackCreator}，且
     * {@code PackRepositoryMixin} 在 PackRepository 构造时对 ServerPacksSource
     * 自动挂载——此处只传原版源即可。
     */
    @Override
    public net.minecraft.server.packs.repository.RepositorySource[] serverPackSources(
            net.minecraft.server.packs.repository.RepositorySource vanilla) {
#if MC_VER < MC_1_21_11
        return new net.minecraft.server.packs.repository.RepositorySource[] {
                vanilla,
                new net.fabricmc.fabric.impl.resource.loader.ModResourcePackCreator(
                        net.minecraft.server.packs.PackType.SERVER_DATA)
        };
#else
        return new net.minecraft.server.packs.repository.RepositorySource[] {vanilla};
#endif
    }
}
