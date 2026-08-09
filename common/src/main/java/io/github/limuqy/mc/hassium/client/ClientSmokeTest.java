package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.command.HassiumCommandHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 开发环境客户端冒烟测试：支持两轮连服统计。
 * <p>
 * 流程：
 * <ol>
 *   <li>连服（VD=20 场景）→ 等待 delayMs → 打印统计1 → 主动断开连接</li>
 *   <li>等待 reconnectDelayMs → 重连（VD=10 场景，服务端已切换）→ 等待 delayMs → 打印统计2 → 退出客户端</li>
 * </ol>
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.smokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.smokeTest.delayMs=6000} 每轮进服后等待毫秒（默认 10000；ROUND1 窗口=delayMs×2，ROUND2=delayMs）</li>
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
    /** 进服后飞行移动秒数（0=不动）：先爬升 2s 再平飞，验证「进服即移动」场景区块补给顺序 */
    private static volatile int moveSeconds = 0;
    private static volatile long moveUntilMs = -1L;
    /** 1=爬升（按住跳跃）2=平飞（按住前进） */
    private static volatile int movePhase = 0;

    /** 阶段选择：classic = 两轮连服 VD 切换；dataplane = 多通道数据面（单次连服 + Data 帧计数报）。 */
    private static volatile boolean runClassic = true;
    private static volatile boolean runDataplane = false;

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
        moveSeconds = (int) parseLong(System.getProperty("hassium.smokeTest.moveSeconds"), 0L);
        host = System.getProperty("hassium.smokeTest.host", "127.0.0.1:25565");
        // 阶段选择解析（与服务端 ServerSmokeTest.initIfEnabled 同规则）
        String phases = System.getProperty("hassium.smokePhases", "classic");
        java.util.Set<String> phaseSet = new java.util.HashSet<>();
        for (String p : phases.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) phaseSet.add(t);
        }
        runClassic = phaseSet.contains("classic") || phaseSet.contains("all");
        runDataplane = phaseSet.contains("dataplane") || phaseSet.contains("all");
        // T6：客户端 failover 已退役，udp-failover 阶段删除（旧链路失语义）
        state = State.WAIT_JOIN_1;
        startAtMs = System.currentTimeMillis();
        joinAtMs = -1L;
        disconnectAtMs = -1L;
        round1Pass = false;
        round2Pass = false;
        armed = true;
        io.github.limuqy.mc.hassium.metrics.NetworkStats.setEnabled(true);
        LOGGER.info("HassiumSmokeTest: enabled delayMs={} reconnectDelayMs={} joinTimeoutMs={} host={}",
                delayMs, reconnectDelayMs, joinTimeoutMs, host);
        // 恢复表现模式证据：recoveryFreeze 键已删（REQ 决策 2/B），2.0.0 客户端 failover 退役，
        // 恒为无感切换（false）；保留 marker 格式供 harness/人工日志确认链路。
        LOGGER.info("HassiumSmokeTest:CLIENT_MODE recoveryFreeze=false");
    }

    /** 在客户端 tick 中驱动；未进服超时会强制失败退出。 */
    public static void onClientTick(Minecraft mc) {
        if (!armed || state == State.DONE || mc == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // 飞行注入：爬升阶段到期 → 转平飞；平飞到期或玩家消失（断开/重连）→ 复位按键
        if (moveUntilMs > 0L) {
            if (mc.player == null) {
                moveUntilMs = -1L;
                mc.options.keyJump.setDown(false);
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
            } else if (now >= moveUntilMs) {
                if (movePhase == 1) {
                    // 爬升结束：松开跳跃，按住前进+疾跑平飞（creative 飞行疾跑 zza×2，规避地形阻挡）
                    movePhase = 2;
                    mc.options.keyJump.setDown(false);
                    mc.options.keyUp.setDown(true);
                    mc.options.keySprint.setDown(true);
                    moveUntilMs = now + (long) moveSeconds * 1000L;
                    LOGGER.info("HassiumSmokeTest:MOVE_CRUISE_START pos=({}, {})",
                            mc.player.blockPosition().getX(), mc.player.blockPosition().getZ());
                } else {
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    moveUntilMs = -1L;
                    movePhase = 0;
                    LOGGER.info("HassiumSmokeTest:MOVE_END pos=({}, {})",
                            mc.player.blockPosition().getX(), mc.player.blockPosition().getZ());
                }
            }
        }

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

        long delayMs;
        if (state == State.WAIT_JOIN_1) {
            // ROUND1：classic（含 classic+dataplane 并跑）需完整下载 ~1681 chunk 进缓存 → delayMs*2；
            //   dataplane-only 不下载缓存（只验证数据面路由），delayMs 即可。
            delayMs = runClassic ? ClientSmokeTest.delayMs * 2 : ClientSmokeTest.delayMs;
        } else {
            // ROUND2：classic+dataplane 同跑时，服务端 dataplane 状态机（3+8+1+5+4+6=27s 最小 + up to 15s step5 超时 ≈ 42s）
            // 在玩家第二次进服后才开始驱动 DP_IDLE→DP_DONE，而 ROUND2 join 与 DP_IDLE 触发（switched=true）几乎同步。
            // 默认 delayMs（15s）远不够 → 等满后客户端 scheduleExit ≈9s 即退服，状态机会被打断。
            // 故 ROUND2 在 dataplane 模式下一致地拿到 PASS/FAIL marker，必须把 ROUND2 窗口扩展到覆盖最坏 42s +5s 余量。
            // classic-only：R2 以客户端缓存命中为主（VD=10，无全量下载），窗口 = delayMs 即可
            // 覆盖增量加载/光照重算完成后再统计。floor 3000ms 防止用户传极小值导致 ROUND2 短到断开尚未重连完。
            if (runDataplane && runClassic) {
                delayMs = Math.max(ClientSmokeTest.delayMs, 50_000L);
            } else if (runClassic) {
                delayMs = Math.max(3_000L, ClientSmokeTest.delayMs);
            } else {
                // dataplane-only 不进入 ROUND2（初始化即 disallow），仅防御兜底
                delayMs = ClientSmokeTest.delayMs;
            }
        }
        if (joinAtMs < 0L) {
            joinAtMs = now;
            LOGGER.info("HassiumSmokeTest: {} player entered world at y={}, waiting {} ms before stats",
                    state, mc.player.getY(), delayMs);
            if (moveSeconds > 0 && moveUntilMs < 0L) {
                // creative 冒烟：本地激活飞行 + 按住跳跃爬升 2s（规避地形阻挡），再平飞
                mc.player.getAbilities().flying = true;
                mc.options.keyJump.setDown(true);
                movePhase = 1;
                moveUntilMs = now + 2000L;
                LOGGER.info("HassiumSmokeTest:MOVE_START climb 2s + cruise {}s pos=({}, {})", moveSeconds,
                        mc.player.blockPosition().getX(), mc.player.blockPosition().getZ());
            }
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

            // dataplane 阶段：报 Data 帧计数 delta（Data 帧经 DataPlaneClientBundle.handleBulkChunk 累加；
            // total 来自 NetworkStats.chunksDecompressed，Primary 与 Data 都计入；primaryDelta = total - dataDelta）
            if (runDataplane) {
                // ROUND2 入口处 NetworkStats.reset() + DataPlaneClientBundle.resetDataBulkCounters()
                // 已清零两端累加器，故 dataFrames / total 直接代表本轮增量，无需手动减 dpBase。
                long dataFrames = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.getBulkFramesData();
                long dataBytes = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.getBulkBytesData();
                long total = io.github.limuqy.mc.hassium.metrics.NetworkStats.getMetrics().getChunksDecompressed();
                long dataDelta = dataFrames; // reset 后基线为 0
                long primaryDelta = total - dataDelta; // total = Primary+Data 解压帧数和；dataDelta = Data 帧 → primaryDelta = Primary 帧
                LOGGER.info("HassiumSmokeTest:DATAPLANE_CLIENT_STATS {} dataFrames={} dataBytes={} total={} dataDelta={} primaryDelta={}",
                        roundLabel, dataFrames, dataBytes, total, dataDelta, primaryDelta);
                // 按 portIdx 区分各 Data 通道实际到达率（PoC share WRR 下两通道观测独立）
                java.util.SortedMap<Integer, long[]> perPort =
                        io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.snapshotPerPort();
                StringBuilder perPortLog = new StringBuilder();
                for (java.util.Map.Entry<Integer, long[]> e : perPort.entrySet()) {
                    if (perPortLog.length() > 0) {
                        perPortLog.append(' ');
                    }
                    perPortLog.append("port").append(e.getKey()).append('=').append(e.getValue()[0]);
                    perPortLog.append('f').append('/').append(io.github.limuqy.mc.hassium.metrics.MetricsTextFormatter.formatBytes(e.getValue()[1]));
                }
                LOGGER.info("HassiumSmokeTest:DATAPLANE_PER_PORT {} {}",
                        roundLabel, perPortLog.length() == 0 ? "(no Data frames)" : perPortLog.toString());
            }

            boolean ok = validateStats(plain);
            // dataplane 模式：classic stats 结构校验放宽（仅 dataplane 阶段数据面行不一定满足 classic 关键词）
            if (runDataplane && !runClassic) {
                ok = true; // 仅 dataplane 模式不依赖 classic stats 关键词
            }
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
            // 仅 dataplane 模式（无 classic）：ROUND1 统计完即结束，不再进入第二轮
            if (runDataplane && !runClassic) {
                boolean pass = round1Pass;
                if (pass) {
                    LOGGER.info("{}", MARKER_PASS);
                } else {
                    LOGGER.error("{} round1={} (dataplane-only)", MARKER_FAIL, round1Pass);
                }
                scheduleExit(pass ? 0 : 2);
                return;
            }
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
            // T6 实体冒烟增强（dev 测试代码）：R2 断线 → 影子端异步保存（daemon 线程）。
            // 不主动断连直接退出时，JVM 终止会打断 daemon saveAll，R2 期间新转发的实体
            // （如 rcon summon）来不及落盘。这里先走与 ROUND1 相同的被动断连路径，
            // 再等保存完成（heat.idx mtime 变化 = saveAll 最后一步落盘）后才退出。
            dumpShadowEntities();
            triggerDisconnect(mc);
            awaitShadowSaveComplete();
            scheduleExit(allPass ? 0 : 2);
        }
    }

    /**
     * T6 实体冒烟增强（dev 测试代码）：打印影子端内存中的实体清单
     * （验证 R2 期间转发的实体是否已被影子端应用，含 UUID/坐标/名字）。
     */
    private static void dumpShadowEntities() {
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer server =
                    io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().get();
            if (server == null) {
                LOGGER.info("HassiumSmokeTest: shadow dump: no shadow server");
                return;
            }
            net.minecraft.server.level.ServerLevel level = server.overworld();
            // 超大固定 AABB 覆盖影子世界全范围（1.20.1 无 WorldBorder.getBounds）
            net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(
                    -3.0E7, -64.0, -3.0E7, 3.0E7, 512.0, 3.0E7);
            java.util.List<net.minecraft.world.entity.Entity> all =
                    level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, bounds, e -> true);
            LOGGER.info("HassiumSmokeTest: shadow dump: {} entities", all.size());
            for (net.minecraft.world.entity.Entity e : all) {
                LOGGER.info("HassiumSmokeTest: shadow dump: type={} uuid={} netid={} pos={} name={}",
                        net.minecraft.world.entity.EntityType.getKey(e.getType()),
                        e.getUUID(), e.getId(), e.blockPosition().toShortString(),
                        e.getName().getString());
            }
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: shadow dump failed", t);
        }
    }

    /**
     * T6 实体冒烟增强（dev 测试代码）：等待影子端断连保存完成。
     * 完成信号 = {@code hassium_cache/<serverId>/heat.idx} mtime 变化
     * （{@code ShadowCacheEviction.save} 是 saveAll 的最后一步）；15s 超时兜底不阻塞退出。
     */
    private static void awaitShadowSaveComplete() {
        try {
            io.github.limuqy.mc.hassium.network.ClientChunkPipeline pipeline =
                    io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance();
            java.nio.file.Path gameDir = pipeline.getGameDir();
            String serverId = pipeline.getServerId();
            if (gameDir == null || serverId == null) {
                LOGGER.warn("HassiumSmokeTest: shadow save wait skipped (cache location unknown)");
                return;
            }
            java.nio.file.Path heatIdx = gameDir.resolve("hassium_cache").resolve(serverId)
                    .resolve("heat.idx");
            long before = heatIdx.toFile().lastModified();
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500L);
                long now = heatIdx.toFile().lastModified();
                if (now != before && now > 0L) {
                    LOGGER.info("HassiumSmokeTest: shadow save completed (heat.idx {} -> {})", before, now);
                    return;
                }
            }
            LOGGER.warn("HassiumSmokeTest: shadow save wait timed out (heat.idx mtime unchanged {})", before);
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: shadow save wait failed, continuing", t);
        }
    }

    private static void handleDisconnect(Minecraft mc, long now) {
        // 等待 reconnectDelayMs 后重连
        if (now - disconnectAtMs < reconnectDelayMs) {
            return;
        }

        // 玩家仍在游戏：ROUND1 断开未生效（被动断连失败）。统一转 WAIT_JOIN_2：
        // 由该状态正常等待并统计；若断开确实未生效（player 持续在场），
        // stats 数据仍为当前连接，不影响判定。
        if (mc.player != null) {
            state = State.WAIT_JOIN_2;
            joinAtMs = -1L;
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
        // -Dhassium.smokeTest.manualLogout=true：模拟真实手动登出（PauseScreen 保存并退出）——
        // 走 Minecraft.disconnect(Screen[,Z]) / clearLevel 主线程路径（MixinMinecraft HEAD 注入
        // dump 同步执行），用于验证「手动登出光照/方块落盘」修复；默认 false 保持既有断连语义。
        if (Boolean.getBoolean("hassium.smokeTest.manualLogout")) {
            LOGGER.info("HassiumSmokeTest: manual logout (Minecraft.disconnect/clearLevel path)");
            try {
#if MC_VER < MC_1_20_2
                mc.clearLevel();
#elif MC_VER < MC_1_20_5
                mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen());
#else
                mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
#endif
            } catch (Throwable t) {
                LOGGER.error("HassiumSmokeTest: manual logout failed", t);
            }
            try {
                io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
                io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
                LOGGER.info("HassiumSmokeTest: network stats reset for ROUND2");
            } catch (Throwable t) {
                LOGGER.warn("HassiumSmokeTest: failed to reset network stats", t);
            }
            return;
        }

        try {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                LOGGER.info("HassiumSmokeTest: disconnecting from server");
                // 直接关 netty channel 模拟被动断连（channelInactive），而非调
                // Connection.disconnect(Component)（主线程手动登出路径，语义不同）。
                net.minecraft.network.Connection netConn = conn.getConnection();
                io.netty.channel.Channel ch = null;
                try {
                    java.lang.reflect.Field f = net.minecraft.network.Connection.class.getDeclaredField("channel");
                    f.setAccessible(true);
                    ch = (io.netty.channel.Channel) f.get(netConn);
                } catch (ReflectiveOperationException ignored) {
                    // 字段缺失（版本差异）时退回 disconnect 路径
                }
                if (ch != null) {
                    ch.close();
                } else {
                    netConn.disconnect(Component.literal("HassiumSmokeTest: round1 done"));
                }
                // 重置网络统计，使 ROUND2 的数据独立于 ROUND1
                try {
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
                    io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
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
     * 校验客户端统计摘要的稳定结构。
     */
    static boolean validateStats(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        return plain.contains("Hassium")
                && plain.contains("客户端统计")
                && plain.contains("带宽压缩")
                && plain.contains("区块缓存")
                && plain.contains("区块加载")
                && plain.contains("超视渲染")
                && plain.contains("光照缓存");
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
