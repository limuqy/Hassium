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
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, serverFile);
    }
}
