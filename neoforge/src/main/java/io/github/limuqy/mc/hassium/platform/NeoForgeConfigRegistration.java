package io.github.limuqy.mc.hassium.platform;

#if MC_VER < MC_1_21_1
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
#else
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
#endif
public final class NeoForgeConfigRegistration {
    private NeoForgeConfigRegistration() {
    }

#if MC_VER < MC_1_21_1
    // 1.20.1: NeoForge still ships net.minecraftforge.fml.ModLoadingContext with registerConfig(Type, ...).
    public static void register(NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        // SERVER 级会被写到 world/serverconfig（随存档删除、启动被重写）；
        // 改 COMMON 级写到 config/，与其他加载器（fabric）的 config/hassium/ 位置统一。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
    }
#else
    // 1.21.1+: registerConfig moved to ModContainer, injected through the mod constructor.
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        // 同上：COMMON 级统一到 config/hassium/，避免 world/serverconfig 随存档删除。
        modContainer.registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
    }
#endif
}
