package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;
#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
#else
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
#endif

import java.nio.file.Path;

/**
 * NeoForge 平台辅助器实现
 * <p>
 * 1.20.1: NeoForge 仍使用 net.minecraftforge 包名
 * 1.20.2+: 切换到 net.neoforged 包名
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
