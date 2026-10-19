package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;

/** Cache assembly waits for the actual connection's synchronized registries and dimension type. */
public final class ShadowStartupCompat {
    private ShadowStartupCompat() {}

    public static boolean mayStart(Minecraft client) {
        return io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientSeedGenEnabled()
                || client != null && client.level != null;
    }
}
