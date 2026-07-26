package io.github.limuqy.mc.hassium.network.dataplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 DataPlane 生命周期（JOIN 握手后 start / DISCONNECT stop）。
 * Fabric / NeoForge 共用，避免各 loader 复制粘贴状态机。
 */
public final class DataPlaneClientLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneClientLifecycle");

    private static volatile DataPlaneClientBundle active;

    private DataPlaneClientLifecycle() {}

    /**
     * 握手 S2C 确认 hasDataPlane 后调用：关闭旧 bundle（若有），用下发 token/endpoints + 玩家 UUID 建立连接。
     * 旧入口 {@link #start(byte[], DataPlanePoCConfig.Endpoint[])}（无 UUID）保留兼容 smoke。
     * @param playerUuid 玩家 UUID；BindRequest v2 必带
     */
    public static synchronized void start(java.util.UUID playerUuid, byte[] token, DataPlanePoCConfig.Endpoint[] endpoints) {
        if (!DataPlanePoCConfig.isEnabled() || !DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE) {
            LOGGER.debug("DataPlaneClientLifecycle: start skipped (disabled)");
            return;
        }
        if (token == null || token.length != 16 || endpoints == null || endpoints.length == 0) {
            LOGGER.warn("DataPlaneClientLifecycle: start skipped (invalid token/endpoints)");
            return;
        }
        if (playerUuid == null) {
            LOGGER.warn("DataPlaneClientLifecycle: start skipped (playerUuid null)");
            return;
        }
        stop();
        DataPlaneClientBundle bundle = new DataPlaneClientBundle();
        bundle.connectAndBind(playerUuid, token, endpoints);
        active = bundle;
        LOGGER.info("DataPlaneClientLifecycle: started (endpoints={} playerId={})", endpoints.length, playerUuid);
    }

    /** 兼容入口：UUID 用 {@link DataPlanePoCConfig#pseudoPlayerId()}（仅供 smoke / 旧测试）。生产走 {@link #start(UUID, byte[], Endpoint[])}。 */
    public static synchronized void start(byte[] token, DataPlanePoCConfig.Endpoint[] endpoints) {
        start(DataPlanePoCConfig.pseudoPlayerId(), token, endpoints);
    }

    /** 从 S2C tail 启动；hasDataPlane=false 时 no-op。UUID 用 pseudoPlayerId（仅供 smoke / 旧测试）。 */
    public static void startFromHandshake(DataPlaneHandshakeTail.S2CTail tail) {
        startFromHandshake(tail, DataPlanePoCConfig.pseudoPlayerId());
    }

    /** 从 S2C tail + 真实 UUID 启动；生产入口。hasDataPlane=false 或 UUID null 时 no-op。 */
    public static void startFromHandshake(DataPlaneHandshakeTail.S2CTail tail, java.util.UUID playerUuid) {
        if (tail == null || !tail.hasDataPlane()) return;
        if (playerUuid == null) return;
        start(playerUuid, tail.token(), tail.endpoints());
    }

    /** Primary 断开或客户端登出时关闭 Data 通道。 */
    public static synchronized void stop() {
        DataPlaneClientBundle bundle = active;
        active = null;
        if (bundle != null) {
            try {
                bundle.shutdown();
            } catch (Exception e) {
                LOGGER.debug("DataPlaneClientLifecycle: shutdown error", e);
            }
            LOGGER.info("DataPlaneClientLifecycle: stopped");
        }
    }

    public static DataPlaneClientBundle getActive() {
        return active;
    }

    public static boolean isActive() {
        DataPlaneClientBundle b = active;
        return b != null && b.isBound();
    }
}
