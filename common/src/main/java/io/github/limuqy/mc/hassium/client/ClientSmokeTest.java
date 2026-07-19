package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.command.HassiumCommandHandler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发环境客户端冒烟测试：进服后等待固定时间，打印
 * {@link HassiumCommandHandler#getClientStatsMessage()}，再计划退出客户端。
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.smokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.smokeTest.delayMs=10000} 进服后等待毫秒（默认 10000）</li>
 *   <li>{@code -Dhassium.smokeTest.joinTimeoutMs=120000} 未进服超时（默认 120s）</li>
 * </ul>
 * 退出码：0 表示统计通过；2 表示统计异常/为空；3 表示进服超时；非 0 其它为运行错误。
 */
public final class ClientSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");
    private static final String MARKER_STATS = "HassiumSmokeTest:CLIENT_STATS";
    private static final String MARKER_PASS = "HassiumSmokeTest:PASS";
    private static final String MARKER_FAIL = "HassiumSmokeTest:FAIL";

    private static volatile boolean armed;
    private static volatile boolean finished;
    private static volatile long startAtMs = -1L;
    private static volatile long joinAtMs = -1L;
    private static volatile long delayMs = 10_000L;
    private static volatile long joinTimeoutMs = 120_000L;

    private ClientSmokeTest() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"));
    }

    public static void initIfEnabled() {
        if (!isEnabled()) {
            return;
        }
        delayMs = parseLong(System.getProperty("hassium.smokeTest.delayMs"), 10_000L);
        joinTimeoutMs = parseLong(System.getProperty("hassium.smokeTest.joinTimeoutMs"), 120_000L);
        armed = true;
        finished = false;
        startAtMs = System.currentTimeMillis();
        joinAtMs = -1L;
        LOGGER.info("HassiumSmokeTest: enabled delayMs={} joinTimeoutMs={}", delayMs, joinTimeoutMs);
    }

    /** 在客户端 tick 中驱动；未进服超时会强制失败退出。 */
    public static void onClientTick(Minecraft mc) {
        if (!armed || finished || mc == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (joinAtMs < 0L && startAtMs > 0L && now - startAtMs > joinTimeoutMs) {
            finished = true;
            LOGGER.error("{} join timeout after {} ms (player/level not ready)", MARKER_FAIL, joinTimeoutMs);
            forceExit(3);
            return;
        }

        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }
        // 单人内嵌服不计入多人连服冒烟
        if (mc.getSingleplayerServer() != null) {
            return;
        }

        if (joinAtMs < 0L) {
            joinAtMs = now;
//            LOGGER.info("HassiumSmokeTest: joined multiplayer, waiting {} ms before stats dump", delayMs);
            return;
        }
        if (now - joinAtMs < delayMs) {
            return;
        }

        finished = true;
        try {
            String stats = HassiumCommandHandler.getClientStatsMessage();
            String plain = stripSection(stats);
            // 逐行打标：避免多行消息中间行无 logger 前缀，在刷屏/过滤时「像没打印」
            LOGGER.info("{} begin", MARKER_STATS);
            for (String line : plain.split("\\R", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                LOGGER.info("{} | {}", MARKER_STATS, line);
            }
            LOGGER.info("{} end", MARKER_STATS);

            boolean ok = validateStats(plain);
            if (ok) {
                LOGGER.info("{}", MARKER_PASS);
            } else {
                LOGGER.error("{} stats validation failed", MARKER_FAIL);
            }
            // 立刻 flush，降低 stop/interrupt 时缓冲未落盘的概率
            try {
                System.out.flush();
                System.err.flush();
            } catch (Throwable ignored) {
            }
            scheduleExit(ok ? 0 : 2);
        } catch (Throwable t) {
            LOGGER.error("{} exception while dumping stats", MARKER_FAIL, t);
            forceExit(1);
        }
    }

    private static void scheduleExit(int exitCode) {
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                Minecraft.getInstance().execute(() -> {
                    try {
                        Minecraft.getInstance().stop();
                    } catch (Throwable t) {
                        LOGGER.error("HassiumSmokeTest: stop() failed, forcing exit", t);
                        forceExit(exitCode);
                    }
                });
                Thread.sleep(8_000L);
                LOGGER.warn("HassiumSmokeTest: force System.exit({}) after stop()", exitCode);
                forceExit(exitCode);
            } catch (Throwable t) {
                LOGGER.error("HassiumSmokeTest: shutdown path failed", t);
                forceExit(exitCode == 0 ? 1 : exitCode);
            }
        }, "hassium-smoke-shutdown");
        shutdown.setDaemon(false);
        shutdown.start();
    }

    private static void forceExit(int code) {
        try {
            System.out.flush();
            System.err.flush();
        } catch (Throwable ignored) {
        }
        System.exit(code);
    }

    static boolean validateStats(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        if (!plain.contains("Hassium") || !plain.contains("客户端统计")) {
            return false;
        }
        if (!plain.contains("网络接收") || !plain.contains("缓存命中率") || !plain.contains("超视渲染")) {
            return false;
        }
        return true;
    }

    private static String stripSection(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§.", "");
    }

    private static long parseLong(String raw, long def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
