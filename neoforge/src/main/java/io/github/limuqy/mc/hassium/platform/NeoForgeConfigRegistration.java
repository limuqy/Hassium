package io.github.limuqy.mc.hassium.platform;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
public final class NeoForgeConfigRegistration {
    private NeoForgeConfigRegistration() {
    }

    // 1.21.1+: registerConfig moved to ModContainer, injected through the mod constructor.
    // 26.x FML 12：Config.Type 改名 COMMON→LOCAL / SERVER→SYNCED（CLIENT/STARTUP 不变）。
    public static void register(ModContainer modContainer, NeoForgeConfigBackend backend, String clientFile, String serverFile) {
        // 物理客户端：CLIENT + LOCAL 双注册——client.toml 配客户端行为，server.toml
        // 供集成服务器/局域网读取（UI 不展示服务端键，仅文件可编辑）。
        // 专用服：仅注册 server 侧（LOCAL）。
        if (Services.PLATFORM.isPhysicalClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, backend.clientSpec(), clientFile);
            modContainer.registerConfig(ModConfig.Type.LOCAL, backend.serverSpec(), serverFile);
        } else {
            modContainer.registerConfig(ModConfig.Type.LOCAL, backend.serverSpec(), serverFile);
        }
    }
}
