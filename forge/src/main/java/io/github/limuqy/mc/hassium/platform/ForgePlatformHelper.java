package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public class ForgePlatformHelper implements IPlatformHelper {
    @Override public double sleepInteractionRadius(net.minecraft.server.level.ServerPlayer player) {
#if MC_VER < MC_1_21_1
        return Math.max(8,Math.max(player.getAttributeValue(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get()),
                player.getAttributeValue(net.minecraftforge.common.ForgeMod.ENTITY_REACH.get()))+2);
#else
        return 8;
#endif
    }
    @Override public String sleepCompatibilityIssue() {
        return io.github.limuqy.mc.hassium.server.sleep.SleepCompatibility.firstUnsupported(
                ModList.get().getMods().stream().map(info->info.getModId()).toList());
    }

    @Override
    public java.util.List<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>> cacheDataPackRegistryKeys() {
#if MC_VER < MC_1_21_1
        var keys = new java.util.ArrayList<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>>();
        net.minecraftforge.registries.DataPackRegistriesHooks.getDataPackRegistries().forEach(data -> keys.add(data.key()));
        return java.util.List.copyOf(keys);
#else
        // Forge 52+ grabWorldgenRegistries already contributes mod schemas to this vanilla list.
        return IPlatformHelper.super.cacheDataPackRegistryKeys();
#endif
    }

    @Override
    public String getPlatformName() {

        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }

    @Override
    public Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isPhysicalClient() {
        return FMLLoader.getDist().isClient();
    }
}
