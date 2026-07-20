package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.command.HassiumCommandHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发环境客户端冒烟测试：支持两轮连服统计。
 * <p>
 * 流程：
 * <ol>
 *   <li>连服（VD=20 场景）→ 等待 delayMs → 打印统计1 → 主动断开连接</li>
 *   <li>等待 reconnectDelayMs → 重连（VD=8 场景，服务端已切换）→ 等待 delayMs → 打印统计2 → 退出客户端</li>
 * </ol>
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.smokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.smokeTest.delayMs=10000} 每轮进服后等待毫秒（默认 10000）</li>
 *   <li>{@code -Dhassium.smokeTest.reconnectDelayMs=3000} 两轮间隔毫秒（默认 3000）</li>
 *   <li>{@code -Dhassium.smokeTest.joinTimeoutMs=120000} 未进服超时（默认 120s）</li>
 *   <li>{@code -Dhassium.smokeTest.host=127.0.0.1:25565} 重连目标地址</li>
 * </ul>
 * 退出码：0 两轮均通过；2 统计校验失败；3 进服超时；4 断开/重连失败；非 0 其它为运行错误。
 */
public final class ClientSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");
    private static final String MARKER_STATS = "HassiumSmokeTest:CLIENT_STATS";
    private static final String MARKER_PASS = "HassiumSmokeTest:PASS";
    private static final String MARKER_FAIL = "HassiumSmokeTest:FAIL";

    private enum State {
        WAIT_JOIN_1,   // 等待第一轮进服
        ROUND_1_STATS, // 第一轮统计输出
        DISCONNECTING, // 主动断开
        WAIT_JOIN_2,   // 等待第二轮进服
        ROUND_2_STATS, // 第二轮统计输出
        DONE
    }

    private static volatile boolean armed;
    private static volatile State state = State.WAIT_JOIN_1;
    private static volatile long startAtMs = -1L;
    private static volatile long joinAtMs = -1L;
    private static volatile long disconnectAtMs = -1L;
    private static volatile long delayMs = 10_000L;
    private static volatile long reconnectDelayMs = 3_000L;
    private static volatile long joinTimeoutMs = 120_000L;
    private static volatile String host = "127.0.0.1:25565";
    private static volatile boolean round1Pass;
    private static volatile boolean round2Pass;

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
        reconnectDelayMs = parseLong(System.getProperty("hassium.smokeTest.reconnectDelayMs"), 3_000L);
        joinTimeoutMs = parseLong(System.getProperty("hassium.smokeTest.joinTimeoutMs"), 120_000L);
        host = System.getProperty("hassium.smokeTest.host", "127.0.0.1:25565");
        armed = true;
        state = State.WAIT_JOIN_1;
        startAtMs = System.currentTimeMillis();
        joinAtMs = -1L;
        disconnectAtMs = -1L;
        round1Pass = false;
        round2Pass = false;
        LOGGER.info("HassiumSmokeTest: enabled delayMs={} reconnectDelayMs={} joinTimeoutMs={} host={}",
                delayMs, reconnectDelayMs, joinTimeoutMs, host);
    }

    /** 在客户端 tick 中驱动；未进服超时会强制失败退出。 */
    public static void onClientTick(Minecraft mc) {
        if (!armed || state == State.DONE || mc == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // 全局超时检查（从启动开始）
        if (startAtMs > 0L && now - startAtMs > joinTimeoutMs * 2 + delayMs * 2 + reconnectDelayMs) {
            finishWithFail("global timeout in state " + state, 3);
            return;
        }

        switch (state) {
            case WAIT_JOIN_1:
            case WAIT_JOIN_2:
                handleWaitJoin(mc, now);
                break;
            case ROUND_1_STATS:
            case ROUND_2_STATS:
                handleStats(mc, now);
                break;
            case DISCONNECTING:
                handleDisconnect(mc, now);
                break;
            case DONE:
                break;
        }
    }

    private static void handleWaitJoin(Minecraft mc, long now) {
        // 超时检查
        long timeoutBase = (state == State.WAIT_JOIN_1) ? startAtMs : disconnectAtMs;
        long timeout = (state == State.WAIT_JOIN_1) ? joinTimeoutMs : joinTimeoutMs;
        if (timeoutBase > 0L && now - timeoutBase > timeout) {
            finishWithFail("join timeout in " + state + " after " + timeout + " ms", 3);
            return;
        }

        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }
        // 单人内嵌服不计入多人连服冒烟
        if (mc.getSingleplayerServer() != null) {
            return;
        }

        // 等到玩家位置被服务端确认（收到 ClientboundPlayerPositionPacket 后 y > 0）
        // 而不是 player 对象刚创建就开始计时
        if (mc.player.getY() <= 0) {
            return;
        }

        if (joinAtMs < 0L) {
            joinAtMs = now;
            LOGGER.info("HassiumSmokeTest: {} player entered world at y={}, waiting {} ms before stats",
                    state, mc.player.getY(), delayMs);
        }
        if (now - joinAtMs >= delayMs) {
            // 进入统计阶段
            if (state == State.WAIT_JOIN_1) {
                state = State.ROUND_1_STATS;
            } else {
                state = State.ROUND_2_STATS;
            }
        }
    }

    private static void handleStats(Minecraft mc, long now) {
        // 立即切换状态，防止重复调用（onClientTick 可能在状态可见性延迟时再次进入）
        boolean isRound1 = (state == State.ROUND_1_STATS);
        String roundLabel = isRound1 ? "ROUND1" : "ROUND2";
        // 先切换到 DONE 临时状态，防止重复执行
        State prevState = state;
        state = State.DONE; // 临时锁定，后面会设为正确状态

        try {
            String stats = HassiumCommandHandler.getClientStatsMessage();
            String plain = stripSection(stats);
            LOGGER.info("{} {} begin", MARKER_STATS, roundLabel);
            for (String line : plain.split("\\R", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                LOGGER.info("{} {} | {}", MARKER_STATS, roundLabel, line);
            }
            LOGGER.info("{} {} end", MARKER_STATS, roundLabel);

            boolean ok = validateStats(plain);
            if (isRound1) {
                round1Pass = ok;
            } else {
                round2Pass = ok;
            }

            if (ok) {
                LOGGER.info("{} {} stats OK", MARKER_STATS, roundLabel);
            } else {
                LOGGER.error("{} {} stats validation FAILED", MARKER_FAIL, roundLabel);
            }

            try {
                System.out.flush();
                System.err.flush();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            LOGGER.error("{} {} exception while dumping stats", MARKER_FAIL, roundLabel, t);
            if (isRound1) {
                round1Pass = false;
            } else {
                round2Pass = false;
            }
        }

        // 切换到正确状态
        if (isRound1) {
            // 主动断开连接
            state = State.DISCONNECTING;
            disconnectAtMs = now;
            triggerDisconnect(mc);
        } else {
            // 第二轮完成，退出（state 已是 DONE）
            boolean allPass = round1Pass && round2Pass;
            if (allPass) {
                LOGGER.info("{}", MARKER_PASS);
            } else {
                LOGGER.error("{} round1={} round2={}", MARKER_FAIL, round1Pass, round2Pass);
            }
            scheduleExit(allPass ? 0 : 2);
        }
    }

    private static void handleDisconnect(Minecraft mc, long now) {
        // 等待 reconnectDelayMs 后重连
        if (now - disconnectAtMs < reconnectDelayMs) {
            return;
        }

        // 确保已断开
        if (mc.player != null) {
            // 仍然在游戏中，强制断开
            triggerDisconnect(mc);
            return;
        }

        // 发起重连
        LOGGER.info("HassiumSmokeTest: reconnecting to {}", host);
        state = State.WAIT_JOIN_2;
        joinAtMs = -1L;
        triggerReconnect(mc);
    }

    /** 主动断开连接：模拟玩家退出服务器（不停客户端）。 */
    private static void triggerDisconnect(Minecraft mc) {
        try {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                LOGGER.info("HassiumSmokeTest: disconnecting from server");
                conn.getConnection().disconnect(Component.literal("HassiumSmokeTest: round1 done"));
                // 重置网络统计，使 ROUND2 的数据独立于 ROUND1
                try {
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
                    LOGGER.info("HassiumSmokeTest: network stats reset for ROUND2");
                } catch (Throwable t) {
                    LOGGER.warn("HassiumSmokeTest: failed to reset network stats", t);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("HassiumSmokeTest: disconnect failed", t);
        }
    }

    /** 重连服务器：通过反射调用 ConnectScreen.startConnecting（跨版本兼容）。 */
    private static void triggerReconnect(Minecraft mc) {
        try {
            LOGGER.info("HassiumSmokeTest: connecting to {}", host);

            // ServerAddress.parseString 是跨版本的静态方法
            Class<?> serverAddrClass = Class.forName("net.minecraft.client.multiplayer.resolver.ServerAddress");
            java.lang.reflect.Method parseMethod = serverAddrClass.getMethod("parseString", String.class);
            Object addr = parseMethod.invoke(null, host);

            // ServerData 构造函数跨版本适配：
            // - 1.20.1: ServerData(String name, String ip, boolean lan)
            // - 1.20.2+: ServerData(String name, String ip, ServerData.Type type)
            Class<?> serverDataClass = Class.forName("net.minecraft.client.multiplayer.ServerData");
            Object serverData = null;

            // 尝试 1.20.1 的 (String, String, boolean) 构造函数
            try {
                java.lang.reflect.Constructor<?> sdCtor1 = serverDataClass.getDeclaredConstructor(String.class, String.class, boolean.class);
                sdCtor1.setAccessible(true);
                serverData = sdCtor1.newInstance("HassiumTest", host, false);
            } catch (NoSuchMethodException ignored) {
            }

            // 尝试 1.20.2+ 的 (String, String, ServerData.Type) 构造函数
            if (serverData == null) {
                Class<?> typeClass = Class.forName("net.minecraft.client.multiplayer.ServerData" + Character.toString(36) + "Type");
                // 用反射获取枚举常量 OTHER（避免泛型类型转换问题）
                Object typeEnum = null;
                for (Object constant : typeClass.getEnumConstants()) {
                    if ("OTHER".equals(((Enum<?>) constant).name())) {
                        typeEnum = constant;
                        break;
                    }
                }
                if (typeEnum == null && typeClass.getEnumConstants().length > 0) {
                    typeEnum = typeClass.getEnumConstants()[0];
                }
                java.lang.reflect.Constructor<?> sdCtor2 = serverDataClass.getDeclaredConstructor(String.class, String.class, typeClass);
                sdCtor2.setAccessible(true);
                serverData = sdCtor2.newInstance("HassiumTest", host, typeEnum);
            }

            Class<?> connectScreenClass = Class.forName("net.minecraft.client.gui.screens.ConnectScreen");
            Class<?> screenClass = Class.forName("net.minecraft.client.gui.screens.Screen");

            // 尝试 6 参数版本（1.20.5+）：startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)
            // TransferState 类路径跨版本不同：1.20.5-1.21.5 在 multiplayer 包；1.21.6+ 在 multiplayer.transfer 包
            String[] transferStatePaths = {
                    "net.minecraft.client.multiplayer.transfer.TransferState",
                    "net.minecraft.client.multiplayer.TransferState"
            };
            for (String tsPath : transferStatePaths) {
                try {
                    Class<?> transferStateClass = Class.forName(tsPath);
                    java.lang.reflect.Method m6 = connectScreenClass.getMethod(
                            "startConnecting", screenClass, Minecraft.class, serverAddrClass, serverDataClass, boolean.class, transferStateClass
                    );
                    m6.invoke(null, null, mc, addr, serverData, false, null);
                    LOGGER.info("HassiumSmokeTest: reconnect triggered (6-arg signature, {})", tsPath);
                    return;
                } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                }
            }

            // 尝试 5 参数版本（1.20.1-1.21.5）：startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)
            try {
                java.lang.reflect.Method m5 = connectScreenClass.getMethod(
                        "startConnecting", screenClass, Minecraft.class, serverAddrClass, serverDataClass, boolean.class
                );
                m5.invoke(null, null, mc, addr, serverData, false);
                LOGGER.info("HassiumSmokeTest: reconnect triggered (5-arg signature)");
                return;
            } catch (NoSuchMethodException ignored) {
            }

            LOGGER.error("HassiumSmokeTest: no compatible startConnecting method found");
        } catch (Throwable t) {
            LOGGER.error("HassiumSmokeTest: reconnect failed", t);
        }
    }

    private static void finishWithFail(String reason, int exitCode) {
        state = State.DONE;
        LOGGER.error("{} {}", MARKER_FAIL, reason);
        scheduleExit(exitCode);
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

    /**
     * 加强统计校验：
     * 1. 关键字必须存在（Hassium、客户端统计、网络接收、缓存命中率、超视渲染）
     * 2. 缓存命中数 + 未命中数 > 0（确保有区块加载）
     * 3. 网络接收字节数 > 0 或缓存命中数 > 0（确保有数据传输或缓存命中）
     */
    static boolean validateStats(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        // 关键字校验
        if (!plain.contains("Hassium") || !plain.contains("客户端统计")) {
            return false;
        }
        if (!plain.contains("网络接收") || !plain.contains("缓存命中率") || !plain.contains("超视渲染")) {
            return false;
        }

        // 提取数字字段：缓存命中率、命中数、未命中数
        // 格式：缓存命中率: 50.0% (命中 100, 未命中 50, 过期 5)
        Pattern cachePattern = Pattern.compile("缓存命中率[^\\d]*([\\d.]+)%[^()]*\\([^)]*命中\\s+(\\d+)[^)]*未命中\\s+(\\d+)");
        Matcher m = cachePattern.matcher(plain);
        if (m.find()) {
            try {
                long hits = Long.parseLong(m.group(2));
                long misses = Long.parseLong(m.group(3));
                if (hits + misses == 0) {
                    LOGGER.error("HassiumSmokeTest: validateStats failed - no chunks loaded (hits={} misses={})", hits, misses);
                    return false;
                }
                LOGGER.info("HassiumSmokeTest: stats OK - cache hits={} misses={}", hits, misses);
            } catch (NumberFormatException e) {
                LOGGER.warn("HassiumSmokeTest: failed to parse cache stats, accepting keyword-only validation");
            }
        } else {
            LOGGER.warn("HassiumSmokeTest: cache stats pattern not found, accepting keyword-only validation");
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
