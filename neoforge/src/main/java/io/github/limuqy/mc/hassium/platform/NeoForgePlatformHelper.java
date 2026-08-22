package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;
#if MC_VER < MC_1_21_1
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
 * MC_VER &lt; MC_1_21_1（1.20.1）: NeoForge 仍使用 net.minecraftforge 包名
 * MC_VER &gt;= MC_1_21_1: 切换到 net.neoforged 包名（中间版本段已退役）
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
