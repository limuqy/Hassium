package io.github.limuqy.mc.hassium.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Registers the generated Forge-native client and server specs. */
public final class ForgeConfigRegistration {
    public static ForgeConfigSpec CLIENT_SPEC;
    public static ForgeConfigSpec SERVER_SPEC;

    private ForgeConfigRegistration() {
    }

    public static void register(ForgeConfigBackend backend, String clientFile, String serverFile) {
        CLIENT_SPEC = backend.clientSpec();
        SERVER_SPEC = backend.serverSpec();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, clientFile);
        // SERVER 级会被 FML 写到 world/serverconfig（随存档删除、每次启动被 ConfigTracker 重写）；
        // 改 COMMON 级写到 config/，与其他加载器（fabric 自定义 IO）的 config/hassium/ 位置统一。
        // Hassium 自己的 ConfigScope.SERVER 仍由 ForgeConfigBackend.load(SERVER) 消费，不受影响。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SERVER_SPEC, serverFile);
    }
