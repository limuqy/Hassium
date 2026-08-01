package io.github.limuqy.mc.hassium.platform;

#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
#elif MC_VER < MC_1_20_5
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
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
        // SERVER 级会被写到 world/serverconfig（随存档删除、启动被重写）；
        // 改 COMMON 级写到 config/，与其他加载器（fabric）的 config/hassium/ 位置统一。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
    }
#elif MC_VER < MC_1_20_5
    // 1.20.2-1.20.4: ModContainer.registerConfig 尚不存在（NeoForge 20.5 才加入），
    // ModLoadingContext（net.neoforged.fml 包）仍提供 registerConfig，调用方式与 1.20.1 相同。
    // modContainer 参数仅为匹配 HassiumNeoForge 构造器入参签名，本分支不使用。
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        // 同上：COMMON 级统一到 config/hassium/，避免 world/serverconfig 随存档删除。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
    }
#else
    // 1.20.5+: registerConfig moved to ModContainer, injected through the mod constructor.
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        // 同上：COMMON 级统一到 config/hassium/，避免 world/serverconfig 随存档删除。
        modContainer.registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
    }
#endif
}
