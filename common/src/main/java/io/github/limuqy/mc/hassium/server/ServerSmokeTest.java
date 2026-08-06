package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlanePoCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 开发环境服务端冒烟测试：启动时 VD=20，第一个玩家退出后切换 VD=8。
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.serverSmokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd1=20} 第一轮视距（默认 20）</li>
 *   <li>{@code -Dhassium.serverSmokeTest.vd2=8} 第二轮视距（默认 8）</li>
 *   <li>{@code -Dhassium.smokePhases=classic} 阶段选择（默认 classic）；PoC 时期的
 *       {@code dataplane} phase 已在 Task 10b §2.1 退役，多通道数据面已切换到 UDP/KCP
 *       （见 {@link io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer}），
 *       用户若仍传入 {@code dataplane} / {@code all} 会被忽略并告警。后续 §2.3 将重新加入
 *       {@code udp-failover} phase。</li>
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
    /** R2 方块变化注入已执行（离线窗口一次性） */
    private static volatile boolean blockChangeInjected = false;

    /** 阶段选择：classic = 现有两轮连服（VD 切换）。Task 10b §2.1 退役 dataplane 后仅剩 classic。 */
    private static volatile boolean runClassic = true;
    /** 阶段选择：udp-failover = 单路连服 + UDP 数据面 + 控制面 failover 观测（§2.3）。不切 VD。
     *  服务端不主动断主控 TCP（mono-JVM 不可行）：production 的 onPrimaryDisconnected 由
     *  客户端主动 disconnect 间接触发，smoke 仅聚合 production 注入的 UDP_FAILOVER* markers
     *  作为 PASS 判据。 */
    private static volatile boolean runUdpFailover = false;
    /** 阶段选择：pregen = 预生成大片区块后停服（冒烟前一次性执行，消除 worldgen 供给波动）。 */
    private static volatile boolean runPregen = false;
    /** 预生成半径（块）：49×49 覆盖 VD20（41×41）首轮需求 */
    private static final int PREGEN_RADIUS = 24;
    /** 每 tick 提交的区块生成请求数（主线程轻量：future 异步） */
    private static final int PREGEN_BATCH = 64;
    private static volatile boolean pregenDone = false;
    private static volatile int pregenCursor = 0;
    private static volatile int pregenDoneCount = 0;
    private static volatile CompletableFuture<?>[] pregenFutures;
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
        // 阶段选择解析（逗号分隔；classic 缺省）。PoC 期间的 dataplane/all 取值已退役：
        // Task 10b §2.1 把多通道数据面 phase 下线（数据面生产路径切到 UDP/KCP），
        // 保留 classic 视距切换 phase。`dataplane` / `all` 中除 classic 外被忽略并告警。
        String phases = System.getProperty("hassium.smokePhases", "classic");
        Set<String> phaseSet = new HashSet<>();
        for (String p : phases.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) phaseSet.add(t);
        }
        boolean dataplaneRequested = phaseSet.contains("dataplane") || phaseSet.contains("all");
        runClassic = phaseSet.contains("classic") || phaseSet.contains("all");
        runUdpFailover = phaseSet.contains("udp-failover");
        runPregen = phaseSet.contains("pregen");
        if (runPregen) {
            // 预生成阶段：不起玩家相关逻辑，只加载区块
            runClassic = false;
            runUdpFailover = false;
            int total = (2 * PREGEN_RADIUS + 1) * (2 * PREGEN_RADIUS + 1);
            pregenFutures = new CompletableFuture[total];
            pregenCursor = 0;
            pregenDoneCount = 0;
            pregenDone = false;
            LOGGER.info("{} PREGEN phase accepted: radius={} total={} chunks", MARKER, PREGEN_RADIUS, total);
        }
        if (dataplaneRequested && !phaseSet.contains("classic") && !runUdpFailover) {
            // 用户显式传 dataplane 但未带 classic —— 仍默认跑 classic 以保证后续能切换 VD。
            runClassic = true;
        }
        if (dataplaneRequested) {
            LOGGER.warn("{} phases={} 中 dataplane/all 已退役（Task 10b §2.1）；仅运行 classic",
                    MARKER, phases);
        }
        if (runUdpFailover) {
            LOGGER.info("{} UDP_FAILOVER phase accepted: 不切换视距，仅注入 markers 由 client 聚合判断",
                    MARKER);
        }
        enabled = true;
        armed = true;
        initialVdSet = false;
        switched = false;
        lastPlayerCount = 0;
        NetworkStats.setEnabled(true);
        LOGGER.info("{} enabled vd1={} vd2={} phases={}", MARKER, vd1, vd2, phases);
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
            if ((runClassic || runUdpFailover) && !initialVdSet && server.getPlayerList() != null) {
                try {
                    server.getPlayerList().setViewDistance(vd1);
                    initialVdSet = true;
                    LOGGER.info("{} initial view-distance set to {}", MARKER, vd1);
                } catch (Throwable t) {
                    LOGGER.error("{} failed to set initial view-distance", MARKER, t);
                    initialVdSet = true; // 避免重复尝试
                }
            }

            if (runPregen && !pregenDone && server.getPlayerList() != null) {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    tickPregen(overworld);
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
                    // 玩家离线窗口注入方块变化：R2 客户端缓存 hash mismatch → section delta →
                    // 客户端增量分段光照重算路径（[LIGHT-SEG]）真实触发（无变化时该路径不会走）。
                    injectR2BlockChange(server);
                }
                lastPlayerCount = currentCount;
            }
        } catch (Throwable t) {
            LOGGER.error("{} tick error", MARKER, t);
        }
    }

    /**
     * R2 方块变化注入：第一个玩家退出后（离线窗口）在世界出生点上方放置一堵 4 格高的
     * 石墙（横跨 VD8 全宽）。R2 客户端缓存读回时该区域 chunkHash 不一致 →
     * section hash 请求 → 服务端回 SectionDeltaS2CPacket → 客户端 merge 后 NBT 中变化
     * section 缺光字段 → 增量分段光照重算（[LIGHT-SEG]）。仅冒烟（hassium.serverSmokeTest）启用。
     */
    private static void injectR2BlockChange(MinecraftServer server) {
        if (blockChangeInjected) {
            return;
        }
        blockChangeInjected = true;
        try {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                return;
            }
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            // 墙：x ∈ [-128, 128)（VD8 半径内全部 chunk），z ∈ [2, 4)（避开出生点 z≈0.5），y ∈ [64, 68)
            for (int x = -128; x < 128; x++) {
                for (int z = 2; z < 4; z++) {
                    for (int y = 64; y < 68; y++) {
                        pos.set(x, y, z);
                        overworld.setBlock(pos, stone, 3);
                    }
                }
            }
            LOGGER.info("{} injected stone wall x=[-128,128) y=[64,68) z=[2,4) for R2 section-delta light path",
                    MARKER);
        } catch (Throwable t) {
            LOGGER.error("{} block-change injection failed", MARKER, t);
        }
    }

    /**
     * pregen 阶段：按行扫描逐批提交区块生成请求（future 异步），全部完成后输出 PREGEN_DONE。
     */
    private static void tickPregen(ServerLevel overworld) {
        if (pregenCursor < pregenFutures.length) {
            int end = Math.min(pregenCursor + PREGEN_BATCH, pregenFutures.length);
            for (int i = pregenCursor; i < end; i++) {
                int dz = i / (2 * PREGEN_RADIUS + 1) - PREGEN_RADIUS;
                int dx = i % (2 * PREGEN_RADIUS + 1) - PREGEN_RADIUS;
                pregenFutures[i] = overworld.getChunkSource().getChunkFuture(
                        dx, dz, ChunkStatus.FULL, true);
            }
            pregenCursor = end;
            LOGGER.info("{} PREGEN submitted {}/{} chunks", MARKER, pregenCursor, pregenFutures.length);
        } else {
            int done = 0;
            for (CompletableFuture<?> f : pregenFutures) {
                if (f.isDone()) {
                    done++;
                }
            }
            if (done >= pregenFutures.length) {
                pregenDone = true;
                LOGGER.info("{} PREGEN_DONE total={} chunks", MARKER, pregenFutures.length);
            }
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
