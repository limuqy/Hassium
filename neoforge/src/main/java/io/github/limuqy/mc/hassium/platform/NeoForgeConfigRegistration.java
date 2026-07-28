package io.github.limuqy.mc.hassium.platform;

#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
#else
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
#endif
public final class NeoForgeConfigRegistration {
    private NeoForgeConfigRegistration() {
    }

#if MC_VER < MC_1_20_2
    // 1.20.1: NeoForge still ships net.minecraftforge.fml.ModLoadingContext with registerConfig(Type, ...).
    public static void register(NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, backend.serverSpec(), serverFile);
    }
#else
    // 1.20.2+: registerConfig moved to ModContainer, injected through the mod constructor.
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        modContainer.registerConfig(ModConfig.Type.SERVER, backend.serverSpec(), serverFile);
    }
#endif
}
