package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.platform.Services;

/** Loader availability is checked before advertising the sleep capability. */
public final class SleepSupportCompat {
    private SleepSupportCompat() {}
    public static boolean supported() {
#if MC_VER < MC_1_21_1
        return Services.PLATFORM.getPlatformName().equals("Forge");
#else
        return true;
#endif
    }
}
