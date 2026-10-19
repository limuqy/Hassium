package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
#if MC_VER < MC_1_21_1
import io.github.limuqy.mc.hassium.platform.Services;
#endif

/** Forge 1.20.1 replaces the shared registries during its login handshake. */
public final class ShadowStartupCompat {
    private ShadowStartupCompat() {}

    public static boolean mayStart(Minecraft client) {
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientSeedGenEnabled()
                && (client == null || client.level == null)) return false;
#if MC_VER < MC_1_21_1
        return mayStart(client != null && client.level != null,
                !"Fabric".equalsIgnoreCase(Services.PLATFORM.getPlatformName()));
#else
        return true;
#endif
    }

    static boolean mayStart(boolean hasClientLevel, boolean replacesRegistriesDuringLogin) {
        return hasClientLevel || !replacesRegistriesDuringLogin;
    }
}
