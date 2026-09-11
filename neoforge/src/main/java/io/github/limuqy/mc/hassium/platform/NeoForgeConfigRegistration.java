package io.github.limuqy.mc.hassium.platform;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
public final class NeoForgeConfigRegistration {
    private NeoForgeConfigRegistration() {
    }

    // 1.21.1+: registerConfig moved to ModContainer, injected through the mod constructor.
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        // 按物理端二选一注册（对齐 Fabric 单文件行为）：客户端只出 client toml，专用服只出 server toml。
        if (Services.PLATFORM.isPhysicalClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
        } else {
            modContainer.registerConfig(ModConfig.Type.COMMON, backend.serverSpec(), serverFile);
        }
    }
}
