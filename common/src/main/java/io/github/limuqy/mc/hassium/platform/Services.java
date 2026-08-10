package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.services.IClientChunkApplier;
import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;
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
    // review-fix: T8-31: volatile + 双重检查锁——原非 volatile 非同步懒加载在多线程首次并发
    // 访问时会重复 load（无害但非确定性），且存在重排序暴露半初始化引用的理论风险。
    private static volatile IClientChunkApplier clientChunkApplier;

    // 网络管理器：发送 chunkHash / 区块数据请求等
    public static final IConfigBackend CONFIG = load(IConfigBackend.class);
    public static final INetworkManagerService NETWORK_MANAGER = load(INetworkManagerService.class);

    /**
     * 获取客户端区块应用器（仅物理客户端可用）
     */
    public static IClientChunkApplier getClientChunkApplier() {
        IClientChunkApplier applier = clientChunkApplier;
        if (applier == null) {
            synchronized (Services.class) {
                applier = clientChunkApplier;
                if (applier == null) {
                    if (PLATFORM.isPhysicalClient()) {
                        applier = load(IClientChunkApplier.class);
                        clientChunkApplier = applier;
                    } else {
                        throw new UnsupportedOperationException("getClientChunkApplier() 仅在客户端可用");
                    }
                }
            }
        }
        return applier;
    }

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