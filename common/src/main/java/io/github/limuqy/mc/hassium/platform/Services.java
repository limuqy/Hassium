package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import io.github.limuqy.mc.hassium.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

// Service loaders are a built-in Java feature that allow us to locate implementations of an interface that vary from one
// environment to another. In the context of MultiLoader we use this feature to access a mock API in the common code that
// is swapped out for the platform specific implementation at runtime.
public class Services {

    // In this example we provide a platform helper which provides information about what platform the mod is running on.
    // For example this can be used to check if the code is running on Forge vs Fabric, or to ask the modloader if another
    // mod is loaded.
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    // Client-side chunk applier for injecting cached chunks into the client world.
    // This is lazily loaded only in client environment to avoid server-side class loading errors.
    private static IClientChunkApplier clientChunkApplier;

    // Network manager service for sending cache queries and decisions.
    public static final INetworkManagerService NETWORK_MANAGER = load(INetworkManagerService.class);

    /**
     * Get the client chunk applier (only available on client side)
     */
    public static IClientChunkApplier getClientChunkApplier() {
        if (clientChunkApplier == null) {
            if (PLATFORM.isPhysicalClient()) {
                clientChunkApplier = load(IClientChunkApplier.class);
            } else {
                throw new UnsupportedOperationException("CLIENT_CHUNK_APPLIER is only available on client side");
            }
        }
        return clientChunkApplier;
    }

    // Legacy accessor for compatibility (will throw on server)
    @Deprecated
    public static final IClientChunkApplier CLIENT_CHUNK_APPLIER = null; // Placeholder, use getClientChunkApplier() instead

    // This code is used to load a service for the current environment. Your implementation of the service must be defined
    // manually by including a text file in META-INF/services named with the fully qualified class name of the service.
    // Inside the file you should write the fully qualified class name of the implementation to load for the platform. For
    // example our file on Forge points to ForgePlatformHelper while Fabric points to FabricPlatformHelper.
    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}