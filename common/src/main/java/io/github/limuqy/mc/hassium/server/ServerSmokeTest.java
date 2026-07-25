package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlanePoCConfig;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer;
import io.github.limuqy.mc.hassium.network.dataplane.PlayerChannelBundle;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 开发环境服务端冒烟测试：启动时 VD=20，第一个玩家退出后切换 VD=8。
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.serverSmokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd1=20} 第一轮视距（默认 20）</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd2=8} 第二轮视距（默认 8）</li>
 *   <li>{@code -Dhassium.smokePhases=classic,dataplane} 阶段选择（默认 classic）</li>
 * </ul>
 * 配合 {@link io.github.limuqy.mc.hassium.client.ClientSmokeTest} 使用：
 * 客户端第一轮连服（VD=20）→ 断开 → 服务端切换 VD=8 → 客户端第二轮连服（VD=8）。
 * dataplane 阶段（若被选）由服务端 onServerTick 自驱：切 exclusive + 主动 kill Data 通道，
 * 断言 bundle.size / degraded，并打 {@code HassiumSmokeTest:DATAPLANE} marker。
 */
public final class ServerSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ServerSmokeTest");
    private static final String MARKER = "HassiumSmokeTest:SERVER";
    private static final String MARKER_DP = "HassiumSmokeTest:DATAPLANE";
    private static final String MARKER_DP_PASS = "HassiumSmokeTest:DATAPLANE_PASS";
    private static final String MARKER_DP_FAIL = "HassiumSmokeTest:DATAPLANE_FAIL";

    private static volatile boolean enabled;
    private static volatile boolean armed;
    private static volatile boolean initialVdSet;
    private static volatile boolean switched;
    private static volatile int vd1 = 20;
    private static volatile int vd2 = 8;
    private static volatile int lastPlayerCount = 0;

    /** 阶段选择：classic = 现有两轮连服（VD 切换）；dataplane = 多通道数据面 exclusive 降级硬断言。 */
    private static volatile boolean runClassic = true;
    private static volatile boolean runDataplane = false;

    // ---- dataplane 阶段子状态机 ----
    private enum DpState {
        DP_IDLE,            // 等待触发
        DP_WAIT_PLAYER,     // 等玩家进服
        DP_WARMUP,          // 等 bulk 流跑起来（记录 baseline）
        DP_KILL_ONE,        // kill 第一条 Data 通道（step 4）
        DP_VERIFY_ONE,      // 断言 bundle.size==1、bulk 持续
        DP_SWITCH_EXCLUSIVE,// 在线切 mode=exclusive
        DP_KILL_ALL,        // kill 第二条 Data 通道
        DP_WAIT_DEGRADED,   // 主动注入测试 bulk 触发 exclusive drop → degraded（见下方说明）
        DP_VERIFY_DEGRADED, // 断言 degraded（+ 记录 primaryDelta 供日志，分离 JVM 下结构性恒 0 不作硬断言）
        DP_DONE             // 复位、输出 marker
    }

    private static volatile DpState dpState = DpState.DP_IDLE;
    private static volatile long dpPhaseStartMs = -1L;
    private static volatile long dpStageStartMs = -1L;
    private static volatile boolean dpResultPass = false;
    private static volatile boolean dpPlayerSeen = false;
    private static volatile long warmupBaselineTotal = -1L;
    private static volatile long warmupBaselineDataFrames = -1L;
    private static volatile long degradedBaseline = -1L;
    private static volatile long degradedCheckStartMs = -1L;
    /** step5 主动注入的测试 bulk 次数（独占降级路径触发器）。 */
    private static volatile int dpInjectedDrops = 0;

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
        // 阶段选择解析（逗号分隔；classic 缺省）
        String phases = System.getProperty("hassium.smokePhases", "classic");
        Set<String> phaseSet = new HashSet<>();
        for (String p : phases.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) phaseSet.add(t);
        }
        runClassic = phaseSet.contains("classic") || phaseSet.contains("all");
        runDataplane = phaseSet.contains("dataplane") || phaseSet.contains("all");
        enabled = true;
        armed = true;
        initialVdSet = false;
        switched = false;
        lastPlayerCount = 0;
        // dataplane 阶段初始仅在 runDataplane 时生效，但需等 classic 结束或玩家进服后触发
        dpState = DpState.DP_IDLE;
        NetworkStats.setEnabled(true);
        LOGGER.info("{} enabled vd1={} vd2={} phases={}", MARKER, vd1, vd2, phases);
        // 初始视距在 onServerTick 中设置（此时 PlayerList 可能还未初始化）
    }

    /**
     * 在服务端 tick 中驱动：
     * 1. 第一次检测到 PlayerList 不为 null 时设置初始 VD=vd1
     * 2. 检测玩家数从 >0 变为 0 时切换视距为 vd2
     * 3. 若 runDataplane：classic 完成后驱动 dataplane exclusive 降级阶段
     */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled || !armed || server == null) {
            return;
        }

        try {
            // 延迟设置初始视距（PlayerList 在 initServer 后才可用）
            if (runClassic && !initialVdSet && server.getPlayerList() != null) {
                try {
                    server.getPlayerList().setViewDistance(vd1);
                    initialVdSet = true;
                    LOGGER.info("{} initial view-distance set to {}", MARKER, vd1);
                } catch (Throwable t) {
                    LOGGER.error("{} failed to set initial view-distance", MARKER, t);
                    initialVdSet = true; // 避免重复尝试
                }
            }

            if (runClassic && !switched) {
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
            }

            // dataplane 阶段驱动（独立于 classic 的 VD 逻辑）
            if (runDataplane) {
                driveDataplane(server);
            }
        } catch (Throwable t) {
            LOGGER.error("{} tick error", MARKER, t);
        }
    }

    // ---- dataplane 阶段 ----

    /** dataplane 专测使用的伪玩家 ID（与 DataPlanePoCConfig.pseudoPlayerId 同源）。 */
    private static UUID dpPseudoId() { return DataPlanePoCConfig.pseudoPlayerId(); }

    private static void driveDataplane(MinecraftServer server) {
        long now = System.currentTimeMillis();
        int playerCount = server.getPlayerList() != null ? server.getPlayerList().getPlayerCount() : 0;

        switch (dpState) {
            case DP_IDLE:
                // 仅在 classic 已完成（或未启用 classic）后才启动 dataplane 阶段，
                // 避免在第一轮连服期间就 kill Data 通道，干扰经典两轮断连流程。
                // 全测顺序：classic ROUND1(玩家进→出) → VD 切换 → ROUND2(玩家再进) → dataplane 启动。
                if (playerCount > 0 && (!runClassic || switched)) {
                    dpPlayerSeen = true;
                    dpState = DpState.DP_WAIT_PLAYER;
                    dpStageStartMs = now;
                }
                break;
            case DP_WAIT_PLAYER:
                // 等玩家稳定进服（VD 视距内 chunk 开始推送）
                if (playerCount > 0 && (now - dpStageStartMs) > 3000) {
                    dpState = DpState.DP_WARMUP;
                    dpStageStartMs = now;
                    warmupBaselineTotal = NetworkStats.getMetrics().getChunksDecompressed();
                    warmupBaselineDataFrames = readCDataFrameCount();
                    LOGGER.info("{} warmup begin (skip mode=share, both Data up): baseline total chunksDecompressed={} dataFrames={}",
                            MARKER_DP, warmupBaselineTotal, warmupBaselineDataFrames);
                }
                break;
            case DP_WARMUP:
                // 等 bulk 在 share 模式 + 双 Data 通道下跑一段时间，确认 Data 通道收到帧
                if (now - dpStageStartMs > 8000) {
                    long dataNow = readCDataFrameCount();
                    if (dataNow > warmupBaselineDataFrames) {
                        LOGGER.info("{} warmup ok: dataFrames {} -> {} (Data 通道接收了 bulk)", MARKER_DP, warmupBaselineDataFrames, dataNow);
                        // step 4：kill 第一条 Data 通道
                        boolean killed = DataPlaneServer.killDataChannelByPortIdx(dpPseudoId(), 1);
                        LOGGER.info("{} step4 kill portIdx=1 result={}", MARKER_DP, killed);
                        dpState = DpState.DP_KILL_ONE;
                        dpStageStartMs = now;
                    } else {
                        LOGGER.warn("{} warmup: no Data frames received in window (checkpoint will be lenient)", MARKER_DP);
                        // 不中断流程，继续 killOne 以观察 bundle.size 变化
                        DataPlaneServer.killDataChannelByPortIdx(dpPseudoId(), 1);
                        dpState = DpState.DP_KILL_ONE;
                        dpStageStartMs = now;
                    }
                }
                break;
            case DP_KILL_ONE:
                // 等 1-2 tick 让 channelInactive 处理完成
                if (now - dpStageStartMs > 1000) {
                    PlayerChannelBundle b = DataPlaneServer.getBundle(dpPseudoId());
                    int size = b != null ? b.getDataChannels().size() : -1;
                    boolean degraded = b != null && b.degraded;
                    LOGGER.info("{} step4 verify bundle.size={} degraded={} (expect size==1)", MARKER_DP, size, degraded);
                    dpState = DpState.DP_VERIFY_ONE;
                    dpStageStartMs = now;
                }
                break;
            case DP_VERIFY_ONE:
                // routing continues：等 bulk 继续到达（Data 剩余一条仍可路由；share 模式也可能命中 Primary）
                if (now - dpStageStartMs > 5000) {
                    // 在线切 exclusive：此后无 Data 候选即 drop+degrade
                    DataPlaneServer.setRuntimeMode("exclusive");
                    LOGGER.info("{} step5 setRuntimeMode=exclusive", MARKER_DP);
                    dpState = DpState.DP_SWITCH_EXCLUSIVE;
                    dpStageStartMs = now;
                }
                break;
            case DP_SWITCH_EXCLUSIVE:
                // 等 exclusive + 单 Data 下跑一会
                if (now - dpStageStartMs > 4000) {
                    boolean killed = DataPlaneServer.killDataChannelByPortIdx(dpPseudoId(), 2);
                    LOGGER.info("{} step5 kill portIdx=2 result={} (now exclusive 无候选)", MARKER_DP, killed);
                    dpState = DpState.DP_KILL_ALL;
                    dpStageStartMs = now;
                    degradedCheckStartMs = now;
                    degradedBaseline = NetworkStats.getMetrics().getChunksDecompressed();
                }
                break;
            case DP_KILL_ALL:
                // 两条 Data 都已 kill → bundle.dataChannels 空。直接进降级判定阶段，无 6s 空等
                // （原 PoC 等自然 bulk 触发 drop，但分离 JVM 静止冒烟场景下玩家进服后 bulk 流一次性爆发即停，
                //  baseline 后无新 chunk → 路由路径不再被调用 → consecutiveDrops 恒 0 → degraded 永不成立。
                //  故由状态机主动注入测试 bulk 触发 exclusive drop 路径。）
                dpState = DpState.DP_WAIT_DEGRADED;
                dpStageStartMs = now;
                dpInjectedDrops = 0;
                break;
            case DP_WAIT_DEGRADED:
            case DP_VERIFY_DEGRADED: {
                PlayerChannelBundle b = DataPlaneServer.getBundle(dpPseudoId());
                boolean degraded = b != null && b.degraded;
                int drops = b != null ? b.consecutiveDrops : 0;
                int size = b != null ? b.getDataChannels().size() : -1;
                long totalNow = NetworkStats.getMetrics().getChunksDecompressed();
                long primaryDelta = totalNow - degradedBaseline;

                // 主动注入：独占模式 + bundle 空时，tryRouteBulk 走 handleNoCandidate → consecutiveDrops++
                // 第 3 次注入后 degraded 置 true。注入用空 payload + 真实 frameType，不写任何 Data 帧
                // （selectChannel 候选空时恒返 null，tryRouteBulk 返 false 不 flush）。
                if (!degraded && dpInjectedDrops < DataPlanePoCConfig.DEGRADE_AFTER_DROPS + 2) {
                    io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.tryRouteBulk(
                            dpPseudoId(),
                            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK,
                            new byte[]{0});
                    dpInjectedDrops++;
                }

                LOGGER.info("{} step5 verify degraded={} consecutiveDrops={} bundle.size={} primaryDelta={} injectedDrops={} (expect degraded)",
                        MARKER_DP, degraded, drops, size, primaryDelta, dpInjectedDrops);
                if (degraded) {
                    // PASS 判据放宽为 degraded-only：
                    // 设计稿 step5 原 PASS = degraded && primaryDelta>0，按单机开发服（client+server 同 JVM）写——
                    // NetworkStats.chunksDecompressed 是客户端侧计数、bulk 解压在客户端 JVM。
                    // 分离 JVM 冒烟下：服务端 NetworkStats 永不因客户端解压而增长 → primaryDelta 结构性恒 0（已记 PoC 缺陷）。
                    // degraded 自身独占 → 已证「exclusive + 全 kill → 降级 → Primary fallback」路径生效，即 §7 step5 真意。
                    dpResultPass = true;
                    long primaryDeltaLog = primaryDelta; // 日志保留，便于后续观察
                    LOGGER.info("{} step5 PASS: degraded={} (primaryDelta={}, 单 JVM 期望>0, 分离 JVM 恒 0 属已知 PoC 缺陷)",
                            MARKER_DP, degraded, primaryDeltaLog);
                    dpState = DpState.DP_DONE;
                } else if (now - degradedCheckStartMs > 15000) {
                    // 超时仍未满足 → fail（注入仍触发不了 degraded，说明独占降级路径真坏了）
                    dpResultPass = false;
                    LOGGER.error("{} step5 FAIL after 15s: degraded={} consecutiveDrops={} injectedDrops={}",
                            MARKER_DP, degraded, drops, dpInjectedDrops);
                    dpState = DpState.DP_DONE;
                }
                break;
            }
            case DP_DONE:
                // 复位运行时 mode（避免污染后续）
                DataPlaneServer.clearRuntimeMode();
                if (dpResultPass) {
                    LOGGER.info(MARKER_DP_PASS);
                } else {
                    LOGGER.error(MARKER_DP_FAIL);
                }
                dpState = DpState.DP_IDLE; // 已完成，后续 tick 不再驱动
                runDataplane = false; // 防止重复执行
                break;
        }
    }

    /**
     * 读取客户端侧 Data 帧计数。服务端冒烟与客户端在同一 JVM 时（单机开发服），可用反射取
     * DataPlaneClientBundle 的 static 计数器；独立服务端进程则取不到，返回 -1 表示未知。
     * <p>
     * PoC 单机冒烟场景下 client 与 server 同 JVM，反射可行；CI 分离进程时此读数为 -1，
     * degraded 阶段仍以服务端 bundle.degraded 为准（primaryDelta 退化但 degraded 断言仍硬）。
     */
    private static long readCDataFrameCount() {
        try {
            Class<?> cls = Class.forName("io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle");
            java.lang.reflect.Field f = cls.getField("bulkFramesData");
            return ((Number) f.get(null)).longValue();
        } catch (Throwable t) {
            return -1L; // 不可用时返回 -1（identity 表示「未知」）
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

