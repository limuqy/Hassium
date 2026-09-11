package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge 平台辅助器实现
 * <p>
 * NeoForge ≥1.21.1：net.neoforged 包名（1.20.1 的 net.minecraftforge 兼容线已随 NeoForge 1.20.1 支持退役）
 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
#if MC_VER < MC_1_21_9
        return !FMLLoader.isProduction();
#else
        return !FMLLoader.getCurrent().isProduction();
#endif
    }

    @Override
    public Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isPhysicalClient() {
#if MC_VER < MC_1_21_9
        return FMLLoader.getDist().isClient();
#else
        return FMLLoader.getCurrent().getDist().isClient();
#endif
    }
}
