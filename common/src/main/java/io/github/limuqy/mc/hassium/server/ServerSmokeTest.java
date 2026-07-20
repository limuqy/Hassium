package io.github.limuqy.mc.hassium.server;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发环境服务端冒烟测试：启动时 VD=20，第一个玩家退出后切换 VD=8。
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.serverSmokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd1=20} 第一轮视距（默认 20）</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd2=8} 第二轮视距（默认 8）</li>
 * </ul>
 * 配合 {@link io.github.limuqy.mc.hassium.client.ClientSmokeTest} 使用：
 * 客户端第一轮连服（VD=20）→ 断开 → 服务端切换 VD=8 → 客户端第二轮连服（VD=8）。
 */
public final class ServerSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ServerSmokeTest");
    private static final String MARKER = "HassiumSmokeTest:SERVER";

    private static volatile boolean enabled;
    private static volatile boolean armed;
    private static volatile boolean initialVdSet;
    private static volatile boolean switched;
    private static volatile int vd1 = 20;
    private static volatile int vd2 = 8;
    private static volatile int lastPlayerCount = 0;

    private ServerSmokeTest() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("hassium.serverSmokeTest", "false"));
    }

    public static void initIfEnabled(MinecraftServer server) {
        if (!isEnabled() || server == null) {
            return;
        }
        vd1 = parseInt(System.getProperty("hassium.serverSmokeTest.vd1"), 20);
        vd2 = parseInt(System.getProperty("hassium.serverSmokeTest.vd2"), 8);
        enabled = true;
        armed = true;
        initialVdSet = false;
        switched = false;
        lastPlayerCount = 0;
        LOGGER.info("{} enabled vd1={} vd2={}", MARKER, vd1, vd2);
        // 初始视距在 onServerTick 中设置（此时 PlayerList 可能还未初始化）
    }

    /**
     * 在服务端 tick 中驱动：
     * 1. 第一次检测到 PlayerList 不为 null 时设置初始 VD=vd1
     * 2. 检测玩家数从 >0 变为 0 时切换视距为 vd2
     */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled || !armed || server == null) {
            return;
        }

        try {
            // 延迟设置初始视距（PlayerList 在 initServer 后才可用）
            if (!initialVdSet && server.getPlayerList() != null) {
                try {
                    server.getPlayerList().setViewDistance(vd1);
                    initialVdSet = true;
                    LOGGER.info("{} initial view-distance set to {}", MARKER, vd1);
                } catch (Throwable t) {
                    LOGGER.error("{} failed to set initial view-distance", MARKER, t);
                    initialVdSet = true; // 避免重复尝试
                }
            }

            if (switched) {
                return;
            }

            int currentCount = server.getPlayerList() != null ? server.getPlayerList().getPlayerCount() : 0;
            if (lastPlayerCount > 0 && currentCount == 0) {
                // 第一个玩家退出，切换视距
                switched = true;
                LOGGER.info("{} player disconnected, switching view-distance from {} to {}",
                        MARKER, vd1, vd2);
                server.getPlayerList().setViewDistance(vd2);
                LOGGER.info("{} view-distance switched to {}", MARKER, vd2);
            }
            lastPlayerCount = currentCount;
        } catch (Throwable t) {
            LOGGER.error("{} tick error", MARKER, t);
        }
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
