package io.github.limuqy.mc.hassium.compat.mods;

/** The sleep window currently filters vanilla compiled terrain meshes only. */
public final class SleepRenderCompat {
    private static final boolean VANILLA = supportsVanillaRenderer(SleepRenderCompat.class.getClassLoader());

    private SleepRenderCompat() {}

    public static boolean supportsVanillaRenderer() { return VANILLA; }

    static boolean supportsVanillaRenderer(ClassLoader loader) {
        // Inspect resources without defining renderer classes during Mixin configuration.
        return loader.getResource("me/jellysquid/mods/sodium/client/SodiumClientMod.class") == null
                && loader.getResource("net/caffeinemc/mods/sodium/client/SodiumClientMod.class") == null
                && loader.getResource("org/embeddedt/embeddium/impl/Embeddium.class") == null;
    }
}
