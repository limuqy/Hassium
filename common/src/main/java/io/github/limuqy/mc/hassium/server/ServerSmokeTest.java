package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
#if MC_VER < MC_1_21_1
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
 *   <li>{@code -Dhassium.serverSmokeTest.vd2=10} 第二轮视距（默认 10）</li>
 *   <li>{@code -Dhassium.smokePhases=classic,pregen} 阶段选择（逗号分隔；
 *       缺省 classic：两轮连服 VD 切换。{@code pregen}：预生成大片区块后停服）</li>
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
    private static volatile int vd2 = 10;
    private static volatile int lastPlayerCount = 0;
    /** R2 方块变化注入已执行（离线窗口一次性） */
    private static volatile boolean blockChangeInjected = false;
    /** T7：{@code hassium.serverSmokeScenario} 非空 → 冒烟场景运行期玩家 join 即 OP。 */
    private static volatile boolean opOnJoin = false;

    /** 阶段选择：classic = 现有两轮连服（VD 切换）。 */
    private static volatile boolean runClassic = true;
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
        vd2 = parseInt(System.getProperty("hassium.serverSmokeTest.vd2"), 10);
        // T7 dimension 场景：hassium.serverSmokeScenario 非空（脚本注入）→ 玩家 join 即 OP
        //（/execute in 切维需权限等级 2，dev 服玩家默认无 OP）。
        opOnJoin = !System.getProperty("hassium.serverSmokeScenario", "").isBlank();
        String phases = System.getProperty("hassium.smokePhases", "classic");
        // 阶段选择解析（逗号分隔；classic 缺省）：classic = 现有两轮连服（VD 切换），
        // pregen = 预生成大片区块后停服（冒烟前一次性执行）。
        Set<String> phaseSet = new HashSet<>();
        for (String p : phases.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) phaseSet.add(t);
        }
        runClassic = phaseSet.contains("classic");
        runPregen = phaseSet.contains("pregen");
        if (runPregen) {
            // 预生成阶段：不起玩家相关逻辑，只加载区块
            runClassic = false;
            int total = (2 * PREGEN_RADIUS + 1) * (2 * PREGEN_RADIUS + 1);
            pregenFutures = new CompletableFuture[total];
            pregenCursor = 0;
            pregenDoneCount = 0;
            pregenDone = false;
            LOGGER.info("{} PREGEN phase accepted: radius={} total={} chunks", MARKER, PREGEN_RADIUS, total);
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
     * T7 dimension 场景服务端配合：冒烟场景运行期把在线玩家全部提升为 OP
     * （{@code /execute in <dim> run tp} 需权限等级 2，dev 服玩家默认无 OP）。
     * 幂等轻量（每 tick 扫描在线列表，仅对非 OP 玩家动作）；
     * 仅 {@code hassium.serverSmokeTest=true} 且 {@code hassium.serverSmokeScenario} 非空时生效。
     */
    private static void opSmokePlayers(MinecraftServer server) {
        if (!opOnJoin) {
            return;
        }
        try {
            var playerList = server.getPlayerList();
            if (playerList == null) {
                return;
            }
            for (ServerPlayer p : playerList.getPlayers()) {
#if MC_VER < MC_1_21_9
                if (!playerList.isOp(p.getGameProfile())) {
                    playerList.op(p.getGameProfile());
                    LOGGER.info("{} op'd smoke player {} (scenario needs level 2 commands)",
                            MARKER, p.getGameProfile().getName());
                }
#else
                // 1.21.9+ PlayerList op/isOp 改收 NameAndId（GameProfile 退役）
                if (!playerList.isOp(p.nameAndId())) {
                    playerList.op(p.nameAndId());
                    LOGGER.info("{} op'd smoke player {} (scenario needs level 2 commands)",
                            MARKER, p.nameAndId().name());
                }
#endif
            }
        } catch (Throwable t) {
            LOGGER.warn("{} op smoke players failed", MARKER, t);
        }
    }

    /**
     * 在服务端 tick 中驱动：
     * 1. 第一次检测到 PlayerList 不为 null 时设置初始 VD=vd1
     * 2. 检测玩家数从 >0 变为 0 时切换视距为 vd2
     */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled || server == null) {
            return;
        }
        // T7：OP 提升只依赖 enabled（hassium.serverSmokeTest=true），不随阶段 armed 关闭失效
        opSmokePlayers(server);
        if (!armed) {
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
     * 石墙（横跨 VD10 全宽）。R2 客户端缓存读回时该区域 chunkHash 不一致 →
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
            // 墙：x ∈ [-160, 160)（VD10 半径内全部 chunk），z ∈ [2, 4)（避开出生点 z≈0.5），y ∈ [64, 68)
            for (int x = -160; x < 160; x++) {
                for (int z = 2; z < 4; z++) {
                    for (int y = 64; y < 68; y++) {
                        pos.set(x, y, z);
                        overworld.setBlock(pos, stone, 3);
                    }
                }
            }
            LOGGER.info("{} injected stone wall x=[-160,160) y=[64,68) z=[2,4) for R2 section-delta light path",
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
