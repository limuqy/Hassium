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
        // 按物理端二选一注册（对齐 Fabric 单文件行为）：客户端只出 client toml，专用服只出 server toml。
        if (Services.PLATFORM.isPhysicalClient()) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        } else {
            // SERVER 级会被写到 world/serverconfig（随存档删除、启动被重写）；
            // 改 COMMON 级写到 config/，与其他加载器（fabric）的 config/hassium/ 位置统一。
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
        }
    }
#else
    // 1.21.1+: registerConfig moved to ModContainer, injected through the mod constructor.
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        // 同上：按物理端二选一注册，客户端不再生成 hassium-server.toml。
        if (Services.PLATFORM.isPhysicalClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        } else {
            modContainer.registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
        }
    }
#endif
}
