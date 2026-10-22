package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.platform.Services;

/** Sleep is enabled only on the two validated Minecraft/loader combinations. */
public final class SleepSupportCompat {
    private SleepSupportCompat() {}
    public static boolean supported() {
#if MC_VER < MC_1_21_1
        return Services.PLATFORM.getPlatformName().equals("Forge");
#elif MC_VER < MC_1_21_2
        return Services.PLATFORM.getPlatformName().equals("NeoForge");
#else
        return false;
#endif
    }
}
