package io.github.limuqy.mc.hassium.shadow.server;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** Directory ownership survives registry replacement and an old instance's asynchronous save. */
public final class ShadowServerOwnership {
    private static final ConcurrentHashMap<Path, Owner> WORLDS = new ConcurrentHashMap<>();

    private ShadowServerOwnership() {}

    public static final class Owner implements AutoCloseable {
        private final Path world;
        private volatile ShadowSeedServer server;

        private Owner(Path world) { this.world = world; }
        public ShadowSeedServer server() { return server; }
        public void attach(ShadowSeedServer server) { this.server = server; }
        @Override public void close() { WORLDS.remove(world, this); }
    }

    /** Register before LevelStorageAccess/loadLevel can open any region, including POI/entities. */
    public static Owner register(Path world) {
        Owner owner = new Owner(world.toAbsolutePath().normalize());
        WORLDS.put(owner.world, owner);
        return owner;
    }

    public static Owner find(Path directory) {
        if (directory == null) return null;
        Path path = directory.toAbsolutePath().normalize();
        for (Owner owner : WORLDS.values()) {
            if (path.startsWith(owner.world)) return owner;
        }
        return null;
    }

    public static void release(ShadowSeedServer server) {
        WORLDS.values().removeIf(owner -> owner.server == server);
    }
}
