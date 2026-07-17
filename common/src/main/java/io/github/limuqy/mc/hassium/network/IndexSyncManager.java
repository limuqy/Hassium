package io.github.limuqy.mc.hassium.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 索引同步管理器
 * <p>
 * 管理每个连接的包类型索引表。
 * 服务端在握手时发送索引表，客户端接收后建立本地索引。
 */
public class IndexSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/IndexSyncManager");

    /**
     * 单例实例
     */
    private static volatile IndexSyncManager instance;

    /**
     * 服务端全局索引管理器
     */
    private final NamespaceIndexManager serverIndexManager = new NamespaceIndexManager();

    /**
     * 客户端索引管理器（按连接）
     */
    private final ConcurrentMap<String, NamespaceIndexManager> clientIndexManagers = new ConcurrentHashMap<>();

    /**
     * 是否已初始化服务端索引
     */
    private volatile boolean serverInitialized = false;

    private IndexSyncManager() {
    }

    /**
     * 获取单例实例
     */
    public static IndexSyncManager getInstance() {
        if (instance == null) {
            synchronized (IndexSyncManager.class) {
                if (instance == null) {
                    instance = new IndexSyncManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化服务端索引（注册所有已知的包类型）
     */
    public void initializeServerIndex() {
        if (serverInitialized) {
            return;
        }

        // 自动枚举原版包类型（从 NetworkState.PLAY 运行时提取）
        serverIndexManager.initVanillaPackets();

        // 注册 Hassium 包类型
        registerHassiumPackets();

        serverInitialized = true;
        LOGGER.info("Server index initialized: {} packet types", serverIndexManager.size());
    }

    /**
     * 注册 Hassium 包类型
     */
    private void registerHassiumPackets() {
        String[] hassiumPackets = {
                "hassium:chunk_payload_s2c"
        };

        serverIndexManager.registerAll(java.util.Arrays.asList(hassiumPackets));
    }

    /**
     * 获取服务端索引管理器
     */
    public NamespaceIndexManager getServerIndexManager() {
        return serverIndexManager;
    }

    /**
     * 创建客户端索引管理器
     *
     * @param connectionId 连接标识
     * @return 新的客户端索引管理器
     */
    public NamespaceIndexManager createClientIndexManager(String connectionId) {
        NamespaceIndexManager clientManager = new NamespaceIndexManager();
        clientIndexManagers.put(connectionId, clientManager);
        LOGGER.debug("Created client index manager for connection: {}", connectionId);
        return clientManager;
    }

    /**
     * 获取客户端索引管理器
     *
     * @param connectionId 连接标识
     * @return 客户端索引管理器，如果不存在则返回 null
     */
    public NamespaceIndexManager getClientIndexManager(String connectionId) {
        return clientIndexManagers.get(connectionId);
    }

    /**
     * 获取默认客户端索引管理器
     *
     * @return 客户端索引管理器，如果不存在则返回 null
     */
    public NamespaceIndexManager getClientIndexManager() {
        return clientIndexManagers.get("client");
    }

    /**
     * 移除客户端索引管理器
     *
     * @param connectionId 连接标识
     */
    public void removeClientIndexManager(String connectionId) {
        clientIndexManagers.remove(connectionId);
        LOGGER.debug("Removed client index manager for connection: {}", connectionId);
    }

    /**
     * 生成索引同步包
     */
    public IndexSyncPacket createSyncPacket() {
        return new IndexSyncPacket(serverIndexManager);
    }

    /**
     * 处理接收到的索引同步包
     *
     * @param connectionId 连接标识
     * @param syncPacket   索引同步包
     * @return 客户端索引管理器
     */
    public NamespaceIndexManager handleSyncPacket(String connectionId, IndexSyncPacket syncPacket) {
        NamespaceIndexManager clientManager = createClientIndexManager(connectionId);
        syncPacket.applyTo(clientManager);

        // 初始化原版包映射（用于聚合包解码）
        clientManager.initVanillaPackets();

        LOGGER.debug("Client index synchronized for connection {}: {} packet types",
                connectionId, clientManager.size());
        return clientManager;
    }
}
