package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.client.SmokeProbeWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4 数据驱动场景引擎：在客户端 tick 中逐步执行 {@link ScenarioStep} 序列，
 * 替代旧 ClientSmokeTest 硬编码 switch 状态机。
 * <p>
 * 场景选择：JVM 属性 {@code hassium.smokeScenario=<name>}；未设置时
 * {@code hassium.smokeTest.migrateTo} 非空 → {@code migrate}，否则 {@code classic}
 * （与旧状态机默认行为完全一致）。场景文件从 classpath
 * {@code /hassium/smoke/scenario/<name>.scenario} 加载。
 * <p>
 * 退出码语义沿用旧状态机：0 两轮均通过；2 统计校验/迁移失败；3 进服超时；
 * 非 0 其它为运行错误。契约 marker（CLIENT_STATS/GATEWAY_CLIENT/PASS/FAIL/CLIENT_MODE）
 * 输出格式与时机不变。
 */
public final class ScenarioEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");
    static final String MARKER_STATS = "HassiumSmokeTest:CLIENT_STATS";
    static final String MARKER_PASS = "HassiumSmokeTest:PASS";
    static final String MARKER_FAIL = "HassiumSmokeTest:FAIL";
    /** T7 V0 网关断言 marker：每轮统计时 dump NetworkCore 状态/计数，供 runtime-smoke-test.ps1 解析。 */
    static final String MARKER_GATEWAY = "HassiumSmokeTest:GATEWAY_CLIENT";

    private enum Outcome { RUNNING, DONE }

    private static volatile boolean armed;
    private static volatile boolean finished;
    private static List<ScenarioStep> steps = List.of();
    private static int index;

    // 全局参数（init 时由 JVM 属性求值）
    private static long startAtMs = -1L;
    private static long delayMs = 10_000L;
    private static long reconnectDelayMs = 3_000L;
    private static long joinTimeoutMs = 120_000L;
    private static String host = "127.0.0.1:25565";

    // T10 迁移演练参数（语义保留）
    private static String migrateTo;
    private static boolean migrateImmediate;
    private static long migrateWaitTimeoutMs = 120_000L;
    private static long migrateTriggeredAtMs = -1L;
    /** 迁移完成时刻（resumeAccepted=true 检测到；再等 settle 让帧 S2C 流入后统计）。 */
    private static long migratedAtMs = -1L;

    // 步间共享状态
    private static long disconnectAtMs = -1L;
    private static boolean round1Pass;
    private static boolean round2Pass;

    // 当前步骤局部状态（advance 时复位）
    private static long stepStartMs;
    private static boolean joinAnnounced;
    private static boolean dumpWaitAnnounced;

    // 飞行注入：爬升阶段到期 → 转平飞；平飞到期或玩家消失 → 复位按键
    private static long moveUntilMs = -1L;
    /** 1=爬升（按住跳跃）2=平飞（按住前进） */
    private static int movePhase;
    private static long cruiseSeconds;

    private ScenarioEngine() {
    }

    /**
     * 初始化：解析 JVM 属性 → 选择并加载场景 → 解析步骤序列并武装引擎。
     * 由 {@code ClientSmokeTest.initIfEnabled} 在冒烟启用时调用；
     * 解析/加载失败抛异常，由调用方转 {@link #abort}。
     */
    public static void init() {
        delayMs = parseLong(System.getProperty("hassium.smokeTest.delayMs"), 10_000L);
        reconnectDelayMs = parseLong(System.getProperty("hassium.smokeTest.reconnectDelayMs"), 3_000L);
        joinTimeoutMs = parseLong(System.getProperty("hassium.smokeTest.joinTimeoutMs"), 120_000L);
        long moveSeconds = parseLong(System.getProperty("hassium.smokeTest.moveSeconds"), 0L);
        host = System.getProperty("hassium.smokeTest.host", "127.0.0.1:25565");
        migrateTo = System.getProperty("hassium.smokeTest.migrateTo");
        if (migrateTo != null && migrateTo.isBlank()) {
            migrateTo = null;
        }
        migrateWaitTimeoutMs = parseLong(System.getProperty("hassium.smokeTest.migrateWaitTimeoutMs"), joinTimeoutMs);
        migrateImmediate = Boolean.parseBoolean(
                System.getProperty("hassium.smokeTest.migrateImmediate", "false"));
        long migrateMoveSeconds = parseLong(System.getProperty("hassium.smokeTest.migrateMoveSeconds"), 0L);

        String name = System.getProperty("hassium.smokeScenario");
        if (name == null || name.isBlank()) {
            // 默认场景：迁移演练属性存在 → migrate，否则 classic（与旧状态机分支一致）
            name = migrateTo != null ? "migrate" : "classic";
        }
        String resource = "/hassium/smoke/scenario/" + name + ".scenario";
        List<String> lines;
        try (var in = ScenarioEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("scenario not found on classpath: " + resource);
            }
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (Exception e) {
            throw new IllegalStateException("scenario load failed: " + resource, e);
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("delayMs", Long.toString(delayMs));
        vars.put("round1WaitMs", Long.toString(delayMs * 2));
        // T7 dimension 场景：切维段等待（nether/back 默认 20s；END 段注入/算光更慢默认 30s，
        // 可用 hassium.smokeTest.dimWaitMs / endWaitMs 覆盖）
        vars.put("dimWaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.dimWaitMs"), Math.max(20_000L, delayMs * 2))));
        vars.put("endWaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.endWaitMs"), Math.max(30_000L, delayMs * 3))));
        // R2 预览光会把 VD 内柱立刻推进 ready FIFO；OVD 环带排在其后。
        // 与 R1 同量等待，避免 dump 时 loadedRenderOnly 仍为 0。
        vars.put("round2WaitMs", Long.toString(Math.max(3_000L, delayMs * 2)));
        vars.put("reconnectDelayMs", Long.toString(reconnectDelayMs));
        vars.put("joinTimeoutMs", Long.toString(joinTimeoutMs));
        vars.put("moveSeconds", Long.toString(moveSeconds));
        vars.put("host", host);
        vars.put("migrateMoveSeconds", Long.toString(migrateMoveSeconds));
        vars.put("migrateWaitTimeoutMs", Long.toString(migrateWaitTimeoutMs));
        vars.put("migrateImmediate", Boolean.toString(migrateImmediate));
        vars.put("migratedSettleMs", Long.toString(Math.max(3_000L, delayMs)));

        steps = ScenarioStep.parse(lines, vars);
        index = 0;
        stepStartMs = startAtMs = System.currentTimeMillis();
        disconnectAtMs = -1L;
        migrateTriggeredAtMs = -1L;
        migratedAtMs = -1L;
        round1Pass = false;
        round2Pass = false;
        finished = false;
        armed = true;
        io.github.limuqy.mc.hassium.metrics.NetworkStats.setEnabled(true);

        StringBuilder desc = new StringBuilder();
        for (ScenarioStep s : steps) {
            if (desc.length() > 0) {
                desc.append(" -> ");
            }
            desc.append(s.type().name().toLowerCase());
        }
        LOGGER.info("HassiumSmokeTest: scenario={} steps=[{}]", name, desc);
        LOGGER.info("HassiumSmokeTest: enabled delayMs={} reconnectDelayMs={} joinTimeoutMs={} host={}",
                delayMs, reconnectDelayMs, joinTimeoutMs, host);
        // 恢复表现模式证据：recoveryFreeze 键已删（REQ 决策 2/B），2.0.0 客户端 failover 退役，
        // 恒为无感切换（false）；保留 marker 格式供 harness/人工日志确认链路。
        LOGGER.info("HassiumSmokeTest:CLIENT_MODE recoveryFreeze=false");
    }

    /**
     * 场景初始化失败兜底：输出 FAIL marker 并按运行错误退出码 1 调度退出。
     * 由 {@code ClientSmokeTest.initIfEnabled} 捕获解析/加载异常后调用。
     */
    public static void abort(String reason) {
        fail(reason, 1);
    }

    /** 在客户端 tick 中驱动；未进服超时会强制失败退出。 */
    public static void onClientTick(Minecraft mc) {
        if (!armed || finished || mc == null) {
            return;
        }
        long now = System.currentTimeMillis();

        tickMovement(mc, now);

        // 全局超时：两轮 joinTimeout + R1/R2 各 delayMs*2 等待 + 重连间隔 + R2 dump 等 OVD
        if (startAtMs > 0L && now - startAtMs > joinTimeoutMs * 2 + delayMs * 5 + reconnectDelayMs) {
            fail("global timeout in step " + currentDesc(), 3);
            return;
        }

        // 瞬时步骤（fly/disconnect/command/dump/exit）同 tick 内连续推进
        for (int guard = 0; guard < 16 && !finished; guard++) {
            ScenarioStep step = steps.get(index);
            Outcome outcome = execute(step, mc, System.currentTimeMillis());
            if (finished) {
                return; // 步骤内部已触发失败退出
            }
            if (outcome == Outcome.RUNNING) {
                return;
            }
            advance();
            if (index >= steps.size()) {
                return; // exit 步骤已调度退出
            }
        }
    }

    private static void advance() {
        index++;
        stepStartMs = System.currentTimeMillis();
        joinAnnounced = false;
        dumpWaitAnnounced = false;
        migratedAtMs = -1L;
    }

    private static String currentDesc() {
        return index < steps.size() ? steps.get(index).toString() : "(end)";
    }

    private static Outcome execute(ScenarioStep step, Minecraft mc, long now) {
        return switch (step.type()) {
            case JOIN -> execJoin(step, mc, now);
            case WAIT -> execWait(step, mc, now);
            case FLY -> execFly(step, mc, now);
            case COMMAND -> execCommand(step, mc, now);
            case DIMENSION -> execDimension(step, mc, now);
            case DISCONNECT -> execDisconnect(mc, now);
            case RECONNECT -> execReconnect(step, mc, now);
            case DUMP -> execDump(step, mc);
            case ASSERT_PROBE -> execAssertProbe(step, mc);
            case EXIT -> execExit(step, mc);
        };
    }

    // ------------------------------------------------------------------ join

    private static Outcome execJoin(ScenarioStep step, Minecraft mc, long now) {
        String label = label(step);
        boolean sinceDisconnect = "disconnect".equals(step.param("since"));
        long base = sinceDisconnect ? disconnectAtMs : startAtMs;
        long timeout = step.longParam("timeoutMs", joinTimeoutMs);
        if (base > 0L && now - base > timeout) {
            fail("join timeout in " + label + " after " + timeout + " ms", 3);
            return Outcome.DONE;
        }
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return Outcome.RUNNING;
        }
        // 单人内嵌服不计入多人连服冒烟
        if (mc.getSingleplayerServer() != null) {
            return Outcome.RUNNING;
        }
        // 等到玩家位置被服务端确认（收到 ClientboundPlayerPositionPacket 后 y > 0）
        if (mc.player.getY() <= 0) {
            return Outcome.RUNNING;
        }
        if (!joinAnnounced) {
            joinAnnounced = true;
            LOGGER.info("HassiumSmokeTest: {} player entered world at y={}", label, mc.player.getY());
        }
        return Outcome.DONE;
    }

    // ------------------------------------------------------------------ wait

    private static Outcome execWait(ScenarioStep step, Minecraft mc, long now) {
        if (!"migrated".equals(step.param("until"))) {
            return now - stepStartMs >= step.longParam("ms", 0L) ? Outcome.DONE : Outcome.RUNNING;
        }
        // until=migrated：等 NetworkCore 回到 ACTIVE 且 resumeAccepted=true（T10 迁移完成）
        boolean done = false;
        boolean resume = false;
        try {
            io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                    io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
            done = core.state() == io.github.limuqy.mc.hassium.network.core.NetworkCoreState.ACTIVE
                    && core.lastResumeAccepted();
            resume = core.lastResumeAccepted();
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: NetworkCore probe failed", t);
        }
        if (done) {
            long settleMs = step.longParam("settleMs", Math.max(3_000L, delayMs));
            if (migratedAtMs < 0L) {
                migratedAtMs = now;
                LOGGER.info("HassiumSmokeTest: migration completed (resumeAccepted=true) — waiting {} ms for frame S2C inflow",
                        settleMs);
            }
            if (now - migratedAtMs >= settleMs) {
                if (step.boolParam("posAfter", false) && mc.player != null) {
                    // N1 观察点：迁移完成后位置（回退后应回到快照/权威位置）
                    LOGGER.info("HassiumSmokeTest:MIGRATE_POS_AFTER pos=({}, {}, {}) dim={}",
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            dimensionId(mc.player.level().dimension()));
                }
                return Outcome.DONE;
            }
            return Outcome.RUNNING;
        }
        long triggerBase = migrateTriggeredAtMs > 0L ? migrateTriggeredAtMs : stepStartMs;
        if (now - triggerBase > step.longParam("timeoutMs", migrateWaitTimeoutMs)) {
            try {
                LOGGER.error("HassiumSmokeTest:MIGRATE_FAIL timeout ({} ms) state={} resumeAccepted={}",
                        migrateWaitTimeoutMs,
                        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().state(), resume);
            } catch (Throwable t) {
                LOGGER.error("HassiumSmokeTest:MIGRATE_FAIL timeout ({} ms)", migrateWaitTimeoutMs, t);
            }
            fail("migrate wait timeout: resumeAccepted=" + resume, 2);
        }
        return Outcome.RUNNING;
    }

    // ------------------------------------------------------------------ fly

    /** 非阻塞飞行注入：creative 冒烟本地激活飞行，先爬升 2s 再平飞 Ns（seconds=0 不动）。 */
    private static Outcome execFly(ScenarioStep step, Minecraft mc, long now) {
        long seconds = step.longParam("seconds", 0L);
        String tag = step.param("tag");
        if (seconds <= 0L || mc.player == null) {
            cruiseSeconds = 0L;
            return Outcome.DONE;
        }
        if (tag == null) {
            tag = "MOVE_";
        }
        mc.player.getAbilities().flying = true;
        mc.options.keyJump.setDown(true);
        movePhase = 1;
        cruiseSeconds = seconds;
        moveUntilMs = now + 2000L;
        LOGGER.info("HassiumSmokeTest:{}START climb 2s + cruise {}s pos=({}, {})", tag, seconds,
                mc.player.blockPosition().getX(), mc.player.blockPosition().getZ());
        return Outcome.DONE;
    }

    /** 飞行注入每 tick 推进（语义照搬旧状态机 moveUntilMs/movePhase 块）。 */
    private static void tickMovement(Minecraft mc, long now) {
        if (moveUntilMs <= 0L) {
            return;
        }
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
                moveUntilMs = now + cruiseSeconds * 1000L;
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

    // --------------------------------------------------------------- command

    private static Outcome execCommand(ScenarioStep step, Minecraft mc, long now) {
        ClientPacketListener conn = mc.getConnection();
        if ("migrate".equals(step.param("mode"))) {
            // 迁移触发需等连接就绪（ROUND1 统计后连接必然在场，防御性等待）
            if (conn == null || mc.player == null) {
                return Outcome.RUNNING;
            }
            if (step.boolParam("posBefore", false)) {
                // N1 观察点：迁移触发前客户端位置（断线窗口起点）
                LOGGER.info("HassiumSmokeTest:MIGRATE_POS_BEFORE pos=({}, {}, {}) dim={}",
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        dimensionId(mc.player.level().dimension()));
            }
            try {
                if (step.boolParam("immediate", false)) {
                    // N1 路径：immediate 迁移（API 直调——命令面无 immediate 子命令，故障路径内部 API）。
                    // 有真实断线窗口（closeOldOutbound → 续流连接重建），窗口内客户端预测移动可观察回退。
                    LOGGER.info("HassiumSmokeTest: triggering immediate migrate to {} (API migrateToImmediate)", migrateTo);
                    String[] hp = migrateTo.split(":");
                    io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().migrateToImmediate(
                            new io.github.limuqy.mc.hassium.network.core.migration.MigrationEndpoint(hp[0],
                                    Integer.parseInt(hp[1])));
                } else {
                    LOGGER.info("HassiumSmokeTest: triggering client command '/hassium migrate {}'", migrateTo);
                    conn.sendCommand("hassium migrate " + migrateTo);
                }
            } catch (Throwable t) {
                LOGGER.error("HassiumSmokeTest:MIGRATE_FAIL sendCommand failed", t);
                fail("sendCommand(hassium migrate) failed: " + t, 2);
                return Outcome.DONE;
            }
            migrateTriggeredAtMs = now;
            return Outcome.DONE;
        }
        // 通用客户端命令原语
        String text = step.param("text");
        if (text == null || text.isBlank()) {
            fail("command step missing text param at " + step, 1);
            return Outcome.DONE;
        }
        if (conn == null || mc.player == null) {
            return Outcome.RUNNING;
        }
        LOGGER.info("HassiumSmokeTest: sending client command '/{}'", text);
        conn.sendCommand(text);
        return Outcome.DONE;
    }

    // -------------------------------------------------------------- dimension

    /**
     * dimension 原语：to=nether|end|overworld（或完整 id）→ /execute in 切维。
     * T7 e2e 修正：裸 {@code execute in <dim>} 是不完整命令（缺 run 子命令），服务端解析报
     * 「incomplete command」caret 反馈且不切维——必须带 {@code run tp @s ~ ~ ~} 才真正落维。
     */
    private static Outcome execDimension(ScenarioStep step, Minecraft mc, long now) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null) {
            return Outcome.RUNNING;
        }
        String to = step.param("to");
        if (to == null || to.isBlank()) {
            to = "overworld";
        }
        String target = switch (to) {
            case "nether" -> "minecraft:the_nether";
            case "end" -> "minecraft:the_end";
            case "overworld" -> "minecraft:overworld";
            default -> to.contains(":") ? to : "minecraft:" + to;
        };
        LOGGER.info("HassiumSmokeTest:DIM_CHANGE to={}", target);
        // sendCommand 即命令路径（ServerboundChatCommandPacket，无需斜杠）；/execute 需 OP（level 2），
        // 服务端冒烟由 ServerSmokeTest 按 hassium.serverSmokeScenario 自动 op。
        conn.sendCommand("execute in " + target + " run tp @s ~ ~ ~");
        return Outcome.DONE;
    }

    // ------------------------------------------------------------ disconnect / reconnect

    private static Outcome execDisconnect(Minecraft mc, long now) {
        triggerDisconnect(mc);
        disconnectAtMs = now;
        return Outcome.DONE;
    }

    private static Outcome execReconnect(ScenarioStep step, Minecraft mc, long now) {
        long wait = step.longParam("delayMs", reconnectDelayMs);
        if (disconnectAtMs > 0L && now - disconnectAtMs < wait) {
            return Outcome.RUNNING;
        }
        // 玩家仍在游戏：断开未生效（被动断连失败）。跳过重连直接进入后续等待；
        // 若后续统计时 player 持续在场，stats 数据仍为当前连接，不影响判定。
        if (mc.player != null) {
            return Outcome.DONE;
        }
        LOGGER.info("HassiumSmokeTest: reconnecting to {}", host);
        triggerReconnect(mc);
        return Outcome.DONE;
    }

    /** 主动断开连接：模拟玩家退出服务器（不停客户端）。 */
    private static void triggerDisconnect(Minecraft mc) {
        // -Dhassium.smokeTest.manualLogout=true：模拟真实手动登出（PauseScreen 保存并退出）——
        // 走 Minecraft.disconnect(Screen[,Z]) / clearLevel 主线程路径（MixinMinecraft HEAD 注入
        // dump 同步执行），用于验证「手动登出光照/方块落盘」修复；默认 false 保持既有断连语义。
        if (Boolean.getBoolean("hassium.smokeTest.manualLogout")) {
            LOGGER.info("HassiumSmokeTest: manual logout (Minecraft.disconnect/clearLevel path)");
            try {
                // 1.20.2–1.20.4 的 disconnect(Screen) 中间层已随版本支持裁剪删除
#if MC_VER < MC_1_21_1
                mc.clearLevel();
#else
                mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
#endif
            } catch (Throwable t) {
                LOGGER.error("HassiumSmokeTest: manual logout failed", t);
            }
            resetNetworkStatsForRound2();
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
                    // review-fix: T8-32: 复用 ReflectionCompat.findFieldByType（类型匹配、映射无关）——
                    // 字段名 "channel" 反射在 intermediary/SRG 生产环境必 NoSuchFieldException
                    ch = (io.netty.channel.Channel) io.github.limuqy.mc.hassium.compat.ReflectionCompat
                            .getFieldByType(netConn, io.netty.channel.Channel.class, true);
                } catch (ReflectiveOperationException ignored) {
                    // 字段缺失（版本差异）时退回 disconnect 路径
                }
                if (ch != null) {
                    ch.close();
                } else {
                    netConn.disconnect(net.minecraft.network.chat.Component.literal("HassiumSmokeTest: round1 done"));
                }
                // 重置网络统计，使 ROUND2 的数据独立于 ROUND1
                resetNetworkStatsForRound2();
            }
        } catch (Throwable t) {
            LOGGER.error("HassiumSmokeTest: disconnect failed", t);
        }
    }

    private static void resetNetworkStatsForRound2() {
        try {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.resetHashClassify();
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
            io.github.limuqy.mc.hassium.network.seedgen.SmokeChunkTrace.reset();
            LOGGER.info("HassiumSmokeTest: network stats reset for ROUND2");
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: failed to reset network stats", t);
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

            // 6 参数版本（1.20.5+）：startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)
            // TransferState 从未改包：1.20.5+ 全版本在 net.minecraft.client.multiplayer 包（multiplayer.transfer 子包不存在）
            try {
                Class<?> transferStateClass = Class.forName("net.minecraft.client.multiplayer.TransferState");
                java.lang.reflect.Method m6 = connectScreenClass.getMethod(
                        "startConnecting", screenClass, Minecraft.class, serverAddrClass, serverDataClass, boolean.class, transferStateClass
                );
                m6.invoke(null, null, mc, addr, serverData, false, null);
                LOGGER.info("HassiumSmokeTest: reconnect triggered (6-arg signature, net.minecraft.client.multiplayer.TransferState)");
                return;
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            }

            // 5 参数版本（<1.20.5，如 1.20.1）：startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)
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

    // ------------------------------------------------------------------ dump

    private static Outcome execDump(ScenarioStep step, Minecraft mc) {
        int round = (int) step.longParam("round", 1L);
        boolean isRound1 = round <= 1;
        String roundLabel = isRound1 ? "ROUND1" : "ROUND2";
        // classic R2：探针在 ScenarioEngine 里先于本 tick drainReady。预览 FIFO
        // 可能让 ovdLoaded 晚几秒才 >0；dump 等到落地或超时，不削弱门禁。
        if (!isRound1 && step.boolParam("gate", true) && ovdCounter(0) <= 0L) {
            int adopted = io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                    .getInstance().adoptPresentRingChunks();
            if (adopted > 0 || ovdCounter(0) > 0L) {
                LOGGER.info("HassiumSmokeTest: {} dump adopted {} ring chunks, ovdLoaded={}",
                        roundLabel, adopted, ovdCounter(0));
            } else {
                long capMs = Math.max(delayMs * 2, 20_000L);
                if (System.currentTimeMillis() - stepStartMs < capMs) {
                    if (!joinAnnounced) {
                        joinAnnounced = true;
                        io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService ovd =
                                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance();
                        LOGGER.info("HassiumSmokeTest: {} dump waiting for ovdLoaded>0 (cap {}ms) "
                                        + "loaded={} pending={} miss={}",
                                roundLabel, capMs, ovd.getLoadedCount(),
                                ovd.getPendingLoadCount(), ovd.getPendingMissCount());
                    }
                    return Outcome.RUNNING;
                }
            }
        }
        try {
            String stats = io.github.limuqy.mc.hassium.command.HassiumCommandHandler.getClientStatsMessage();
            String plain = stripSection(stats);
            LOGGER.info("{} {} begin", MARKER_STATS, roundLabel);
            for (String line : plain.split("\\R", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                LOGGER.info("{} {} | {}", MARKER_STATS, roundLabel, line);
            }
            LOGGER.info("{} {} end", MARKER_STATS, roundLabel);
            dumpGatewayAssertion(roundLabel);
            SmokeProbeWriter.writeRound(round, mc);
            if (!step.boolParam("gate", true)) {
                LOGGER.info("{} {} stats gate=false (validation skipped)", MARKER_STATS, roundLabel);
                return Outcome.DONE;
            }
            boolean ok = validateStats(plain, roundLabel);
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
        return Outcome.DONE;
    }

    /**
     * T7 V0 网关断言：dump NetworkCore 状态与计数（只读现有公开 API）。
     * marker 格式：{@code HassiumSmokeTest:GATEWAY_CLIENT ROUND<n> state=<NetworkCoreState> s2c=<n> c2s=<n> resume=<bool>}
     */
    private static void dumpGatewayAssertion(String roundLabel) {
        try {
            io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                    io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
            LOGGER.info("{} {} state={} s2c={} c2s={} resume={}",
                    MARKER_GATEWAY, roundLabel, core.state(), core.s2cDispatchedCount(),
                    core.c2sRoutedCount(), core.lastResumeAccepted());
        } catch (Throwable t) {
            LOGGER.error("{} {} state=ERROR s2c=0 c2s=0 resume=false (dump failed: {})",
                    MARKER_GATEWAY, roundLabel, t.toString());
        }
    }

    // ---------------------------------------------------------- assertProbe

    /**
     * 场景内门禁断言（T6 seedgen 引入；dimension 场景复用）：
     * <pre>
     *   assertProbe key=counters.locallyGenerated op=gt value=0
     *   assertProbe key=stats.staleFullChunkRequestCount op=lt vs=stats.clientAppliedChunkCount
     *   assertProbe key=joined op=eq value=true
     *   assertProbe key=dimension op=eq value=minecraft:the_nether
     * </pre>
     * key path 与 {@link SmokeProbeWriter} PROBE JSON 键同名（counters.* / stats.* /
     * gateway.c2s|s2c|resumeAccepted / 顶层 joined、dimension）。数值字段支持
     * gt/ge/lt/le/eq，且可用 vs=&lt;path&gt; 做字段对字段比较；布尔字段（joined/
     * gateway.resumeAccepted）与字符串字段（dimension）只支持 eq。断言失败按统计
     * 校验失败语义 fail(code 2)；key/op 非法属场景配置错误，fail(code 1)。
     */
    private static Outcome execAssertProbe(ScenarioStep step, Minecraft mc) {
        String key = step.param("key");
        String op = step.param("op");
        if (key == null || op == null) {
            fail("assertProbe missing key/op at " + step, 1);
            return Outcome.DONE;
        }
        // 字符串等值：顶层 dimension（未进服按 null 处理，必不等）
        if ("dimension".equals(key)) {
            String actual = mc != null && mc.player != null
                    ? dimensionId(mc.player.level().dimension()) : null;
            boolean ok = "eq".equals(op)
                    && step.param("value") != null
                    && step.param("value").equals(actual);
            probeResult(step, key, op, step.param("value"), actual, ok);
            return Outcome.DONE;
        }
        // 布尔等值：joined / gateway.resumeAccepted（value=true/false 字面量）
        if ("joined".equals(key) || "gateway.resumeAccepted".equals(key)) {
            boolean actual;
            if ("joined".equals(key)) {
                actual = mc != null && mc.player != null && mc.level != null;
            } else {
                boolean resume = false;
                try {
                    resume = io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance()
                            .lastResumeAccepted();
                } catch (Throwable ignored) {
                    // 与 dumpGatewayAssertion 同语义降级为 false
                }
                actual = resume;
            }
            boolean expected = step.boolParam("value", false);
            boolean ok = "eq".equals(op) && actual == expected;
            probeResult(step, key, op, Boolean.toString(expected), Boolean.toString(actual), ok);
            return Outcome.DONE;
        }
        // 数值比较
        Long actual = readNumericProbe(key);
        if (actual == null) {
            fail("assertProbe unknown key '" + key + "' at " + step, 1);
            return Outcome.DONE;
        }
        long expected;
        String expectDesc;
        String vsKey = step.param("vs");
        if (vsKey != null) {
            Long other = readNumericProbe(vsKey);
            if (other == null) {
                fail("assertProbe unknown vs key '" + vsKey + "' at " + step, 1);
                return Outcome.DONE;
            }
            expected = other;
            expectDesc = vsKey + "=" + other;
        } else {
            expected = step.longParam("value", 0L);
            expectDesc = Long.toString(expected);
        }
        boolean ok;
        switch (op) {
            case "gt" -> ok = actual > expected;
            case "ge" -> ok = actual >= expected;
            case "lt" -> ok = actual < expected;
            case "le" -> ok = actual <= expected;
            case "eq" -> ok = actual == expected;
            default -> {
                fail("assertProbe unknown op '" + op + "' at " + step, 1);
                return Outcome.DONE;
            }
        }
        probeResult(step, key, op, expectDesc, Long.toString(actual), ok);
        return Outcome.DONE;
    }

    /** assertProbe 结果统一出口：OK 记 info；FAILED 走 fail(code 2)。 */
    private static void probeResult(ScenarioStep step, String key, String op,
                                    String expected, String actual, boolean ok) {
        if (ok) {
            LOGGER.info("HassiumSmokeTest: assertProbe OK {} {} {} (actual={})", key, op, expected, actual);
        } else {
            fail("assertProbe FAILED " + key + " " + op + " " + expected + " (actual=" + actual + ") at " + step, 2);
        }
    }

    /**
     * assertProbe 数值取值：键名与 {@link SmokeProbeWriter} appendStats/appendCounters/
     * appendGateway 一一对应（PROBE JSON 消费方可用同名键写场景断言）。
     * 返回 null = 未知键。
     */
    private static Long readNumericProbe(String key) {
        io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl m =
                io.github.limuqy.mc.hassium.metrics.NetworkStats.getMetrics();
        return switch (key) {
            // counters.*（appendCounters 同名）
            case "counters.ovdLoaded" -> ovdCounter(0);
            case "counters.ovdPendingMiss" -> ovdCounter(1);
            case "counters.ovdShadowServed" -> ovdCounter(2);
            case "counters.sectionDeltaRequestsSent" -> m.getSectionDeltaRequestsSent();
            case "counters.sectionDeltaApplied" -> m.getSectionDeltaChunksReceived();
            case "counters.lightSegRecalc" -> m.getLightCacheMissCount();
            case "counters.locallyGenerated" -> m.getLocallyGeneratedChunkCount();
            // stats.*（appendStats 同名）
            case "stats.vanillaBytesReceived" -> m.getVanillaBytesReceived();
            case "stats.actualBytesReceived" -> m.getActualBytesReceived();
            case "stats.vanillaBytesSent" -> m.getVanillaBytesSent();
            case "stats.actualBytesSent" -> m.getActualBytesSent();
            case "stats.metadataBytesSent" -> m.getMetadataBytesSent();
            case "stats.metadataBytesReceived" -> m.getMetadataBytesReceived();
            case "stats.chunksCompressed" -> m.getChunksCompressed();
            case "stats.chunksDecompressed" -> m.getChunksDecompressed();
            case "stats.fullChunkRequestCount" -> m.getFullChunkRequestCount();
            case "stats.newFullChunkRequestCount" -> m.getNewFullChunkRequestCount();
            case "stats.staleFullChunkRequestCount" -> m.getStaleFullChunkRequestCount();
            case "stats.cacheHitFullChunkCount" -> m.getCacheHitFullChunkCount();
            case "stats.cacheHitFullChunkBytes" -> m.getCacheHitFullChunkBytes();
            case "stats.cacheDeltaCount" -> m.getCacheDeltaCount();
            case "stats.cacheDeltaSavedBytes" -> m.getCacheDeltaSavedBytes();
            case "stats.cacheShardBytes" -> m.getCacheShardBytes();
            case "stats.locallyGeneratedChunkCount" -> m.getLocallyGeneratedChunkCount();
            case "stats.locallyGeneratedChunkBytes" -> m.getLocallyGeneratedChunkBytes();
            case "stats.clientAppliedChunkCount" -> m.getClientAppliedChunkCount();
            case "stats.clientLandedChunkCount" -> m.getClientLandedChunkCount();
            case "stats.sectionDeltaRequestsSent" -> m.getSectionDeltaRequestsSent();
            case "stats.sectionDeltaChunksReceived" -> m.getSectionDeltaChunksReceived();
            case "stats.lightCacheHitCount" -> m.getLightCacheHitCount();
            case "stats.lightCacheHitBytes" -> m.getLightCacheHitBytes();
            case "stats.lightReuseShadowCount" -> m.getLightReuseShadowCount();
            case "stats.lightReuseShadowBytes" -> m.getLightReuseShadowBytes();
            case "stats.lightCacheMissCount" -> m.getLightCacheMissCount();
            case "stats.lightCacheMissBytes" -> m.getLightCacheMissBytes();
            case "stats.noModReceiveBytes" -> m.getNoModReceiveBytes();
            // gateway.*（appendGateway 同名；resumeAccepted 是布尔走专用分支）
            case "gateway.c2s" -> gatewayCounter(true);
            case "gateway.s2c" -> gatewayCounter(false);
            default -> null;
        };
    }

    /** ovd 计数取值（SmokeProbeWriter.appendCounters 同款降级 -1）。which: 0=loaded 1=pendingMiss 2=shadowServed */
    private static long ovdCounter(int which) {
        try {
            io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService ovd =
                    io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance();
            return switch (which) {
                case 0 -> ovd.getLoadedCount();
                case 1 -> ovd.getPendingMissCount();
                default -> ovd.getShadowServedCount();
            };
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 网关计数取值（NetworkCore 只读公开 API；异常降级 0，同 appendGateway）。 */
    private static long gatewayCounter(boolean c2s) {
        try {
            io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                    io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
            return c2s ? core.c2sRoutedCount() : core.s2cDispatchedCount();
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ exit

    private static Outcome execExit(ScenarioStep step, Minecraft mc) {
        // rounds=1：单轮场景（seedgen 等）只看 round1Pass；默认 2 保持两轮口径。
        boolean allPass = round1Pass && (step.longParam("rounds", 2L) <= 1L || round2Pass);
        if (allPass) {
            LOGGER.info("{}", MARKER_PASS);
        } else {
            LOGGER.error("{} round1={} round2={}", MARKER_FAIL, round1Pass, round2Pass);
        }
        // T6 实体冒烟增强（dev 测试代码）：R2 断线 → 影子端异步保存（park 线程）。
        // 不主动断连直接退出时，JVM 终止会打断 daemon saveAll。先被动断连，关闭线程
        // 等 saveAll 序号递增后再 stop()，不占客户端 tick。
        dumpShadowEntities();
        long saveSeq = io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer.saveAllSeq();
        triggerDisconnect(mc);
        LOGGER.info("HassiumSmokeTest: ROUND2 exit scheduling code={} saveSeq={}",
                allPass ? 0 : 2, saveSeq);
        scheduleExit(allPass ? 0 : 2, saveSeq);
        finished = true;
        return Outcome.DONE;
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
            net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            net.minecraft.world.phys.AABB bounds = player == null
                    ? new net.minecraft.world.phys.AABB(-64, -64, -64, 64, 320, 64)
                    : player.getBoundingBox().inflate(128.0);
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
     * 完成信号 = {@link io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer#saveAllSeq()}
     * 递增。须在 {@code triggerDisconnect} 之前采样序号：park 的 saveAll 常在数毫秒内结束，
     * 若事后再看 heat.idx mtime，文件可能已写完 → 空等到 15s 超时，R2 退出像卡死。
     */
    private static void awaitShadowSaveComplete(long saveSeqBeforeDisconnect) {
        try {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                if (io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer.saveAllSeq()
                        > saveSeqBeforeDisconnect) {
                    LOGGER.info("HassiumSmokeTest: shadow save completed (seq {} -> {})",
                            saveSeqBeforeDisconnect,
                            io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer.saveAllSeq());
                    return;
                }
                Thread.sleep(20L);
            }
            LOGGER.warn("HassiumSmokeTest: shadow save wait timed out (seq still {})",
                    saveSeqBeforeDisconnect);
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: shadow save wait failed, continuing", t);
        }
    }

    // ------------------------------------------------------------------ fail / exit plumbing

    private static void fail(String reason, int exitCode) {
        finished = true;
        LOGGER.error("{} {}", MARKER_FAIL, reason);
        scheduleExit(exitCode);
    }

    private static void scheduleExit(int exitCode) {
        scheduleExit(exitCode, Long.MIN_VALUE);
    }

    private static void scheduleExit(int exitCode, long saveSeqBeforeDisconnect) {
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // T7 e2e 修正：非 0 退出码（校验/断言失败、超时）必须 fail-fast 直接强退。
            if (exitCode != 0) {
                LOGGER.error("HassiumSmokeTest: exiting with code {} (fail-fast, skipping graceful stop)", exitCode);
                try {
                    System.out.flush();
                    System.err.flush();
                } catch (Throwable ignored) {
                }
                Runtime.getRuntime().halt(exitCode);
                return;
            }
            if (saveSeqBeforeDisconnect != Long.MIN_VALUE) {
                awaitShadowSaveComplete(saveSeqBeforeDisconnect);
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
                Thread.sleep(2_000L);
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

    // ------------------------------------------------------------------ stats validation

    /**
     * 校验客户端统计摘要的结构与数值一致性（口径与 HassiumCommandHandler 显示完全同源）：
     * 缓存命中率、流量节省百分比、光照缓存命中率三行的显示值必须与指标计算一致；
     * applied/landed 区块数必须 &gt; 0；ROUND2 必须有缓存全命中。
     */
    static boolean validateStats(String plain, String roundLabel) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        if (!(plain.contains("Hassium")
                && plain.contains("客户端统计")
                && plain.contains("带宽压缩")
                && plain.contains("区块缓存")
                && plain.contains("区块加载")
                && plain.contains("超视渲染")
                && plain.contains("光照缓存")
                && plain.contains("流量节省"))) {
            LOGGER.error("{} {} stats validation FAILED: missing structural lines", MARKER_FAIL, roundLabel);
            return false;
        }

        io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl m =
                io.github.limuqy.mc.hassium.metrics.NetworkStats.getMetrics();
        long applied = m.getClientAppliedChunkCount();
        if (applied <= 0) {
            LOGGER.error("{} {} stats validation FAILED: client applied chunks == 0", MARKER_FAIL, roundLabel);
            return false;
        }
        if (m.getClientLandedChunkCount() <= 0) {
            LOGGER.error("{} {} stats validation FAILED: no chunk actually landed on client", MARKER_FAIL, roundLabel);
            return false;
        }

        boolean ok = true;

        // 1) 区块缓存行：显示的百分比与计数必须和指标计算一致。
        Matcher cacheMatcher = CACHE_STATS_PATTERN.matcher(plain);
        if (!cacheMatcher.find()) {
            LOGGER.error("{} {} stats validation FAILED: cache line not parseable", MARKER_FAIL, roundLabel);
            ok = false;
        } else {
            double displayedRate = Double.parseDouble(cacheMatcher.group(1));
            long cacheCount = Long.parseLong(cacheMatcher.group(2));
            long partialCount = Long.parseLong(cacheMatcher.group(3));
            double expectedRate = m.getEffectiveCacheHitRate() * 100.0;
            if (Math.abs(displayedRate - expectedRate) > 0.06
                    || !countsNear(cacheCount, m.getCacheHitFullChunkCount())
                    || !countsNear(partialCount, m.getCacheDeltaCount())) {
                LOGGER.error("{} {} stats validation FAILED: cache formula mismatch " +
                                "displayed={}/{} partial={}, expected={}/{} partial={}",
                        MARKER_FAIL, roundLabel,
                        displayedRate, cacheCount, partialCount,
                        String.format(java.util.Locale.ROOT, "%.1f", expectedRate),
                        m.getCacheHitFullChunkCount(), m.getCacheDeltaCount());
                ok = false;
            }
        }

        // 2) 流量节省行：第一段百分比必须等于 已节省（= 100% - 实际推送 / 无MOD应收）。
        Matcher savingsMatcher = SAVINGS_STATS_PATTERN.matcher(plain);
        if (!savingsMatcher.find()) {
            LOGGER.error("{} {} stats validation FAILED: traffic savings line not parseable", MARKER_FAIL, roundLabel);
            ok = false;
        } else {
            double displayedSaving = Double.parseDouble(savingsMatcher.group(1));
            long noMod = m.getNoModReceiveBytes();
            double expectedSaving = noMod > 0
                    ? Math.max(0.0, Math.min(100.0,
                    (double) Math.max(0L, noMod - m.getActualBytesReceived()) / noMod * 100.0))
                    : 0.0;
            if (Math.abs(displayedSaving - expectedSaving) > 0.06) {
                LOGGER.error("{} {} stats validation FAILED: traffic savings mismatch displayed={} expected={}",
                        MARKER_FAIL, roundLabel, displayedSaving, expectedSaving);
                ok = false;
            }
        }

        // 3) 光照缓存行：百分比按字节口径（直连命中 + 影子复用字节）/（命中 + 重算字节）。
        Matcher lightMatcher = LIGHT_STATS_PATTERN.matcher(plain);
        if (!lightMatcher.find()) {
            LOGGER.error("{} {} stats validation FAILED: light cache line not parseable", MARKER_FAIL, roundLabel);
            ok = false;
        } else {
            double displayedRate = Double.parseDouble(lightMatcher.group(1));
            double expectedRate = m.getLightCacheHitRate() * 100.0;
            long displayedHit = Long.parseLong(lightMatcher.group(2));
            long recompute = Long.parseLong(lightMatcher.group(3));
            long expectedHit = m.getLightCacheHitCount() + m.getLightReuseShadowCount();
            if (Math.abs(displayedRate - expectedRate) > 0.06
                    || !countsNear(displayedHit, expectedHit)
                    || !countsNear(recompute, m.getLightCacheMissCount())) {
                LOGGER.error("{} {} stats validation FAILED: light formula mismatch " +
                                "displayed={}/{} recompute={}, expected={}/{} recompute={}",
                        MARKER_FAIL, roundLabel,
                        displayedRate, displayedHit, recompute,
                        String.format(java.util.Locale.ROOT, "%.1f", expectedRate),
                        expectedHit, m.getLightCacheMissCount());
                ok = false;
            }
        }

        // ROUND2 回归门禁：缓存主链路必须真实命中（0 说明影子端缓存未生效）。
        if ("ROUND2".equals(roundLabel) && m.getCacheHitFullChunkCount() <= 0) {
            LOGGER.error("{} {} stats validation FAILED: ROUND2 has zero cache full hits", MARKER_FAIL, roundLabel);
            ok = false;
        }

        if (ok) {
            LOGGER.info("HassiumSmokeTest: stats OK applied={} cacheRate={}% trafficRatio={}%",
                    applied,
                    String.format(java.util.Locale.ROOT, "%.1f", m.getEffectiveCacheHitRate() * 100.0),
                    String.format(java.util.Locale.ROOT, "%.1f", m.getTrafficSavingsPercent()));
        }
        return ok;
    }

    /** 区块缓存行：百分比（全命中 N/B，部分命中 N/B，增量 B，应用 B）。 */
    private static final Pattern CACHE_STATS_PATTERN = Pattern.compile(
            "区块缓存：([0-9.]+)%（全命中 (\\d+)/[^，]*，部分命中 (\\d+)/[^，]*，增量 [^，]*，应用 [^）]*）");
    /** 流量节省行：第一段 = 已节省（= 100% - 实际/无MOD）。 */
    private static final Pattern SAVINGS_STATS_PATTERN = Pattern.compile(
            "流量节省：([0-9.]+)%（当前 [^，]*，无MOD [^）]*）");
    /** 光照缓存行：百分比（命中 N/B，重算 N/B）；命中 = 直连 + 影子复用。 */
    private static final Pattern LIGHT_STATS_PATTERN = Pattern.compile(
            "光照缓存：([0-9.]+)%（命中 (\\d+)/[^，]*，重算 (\\d+)/[^）]*）");

    private static String stripSection(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§.", "");
    }

    /**
     * 统计文本已从 metrics 拍出后，影子光照 worker 仍可在主线程校验前完成一批回写。
     * 该增量是单调的同一事件流，不是展示公式不一致；64 覆盖一次 worker drain，
     * 同时远小于真实口径错配的规模。
     */
    static boolean countsNear(long dumped, long live) {
        return Math.abs(dumped - live) <= 64L;
    }

    private static String label(ScenarioStep step) {
        String l = step.param("label");
        return l != null ? l : step.type().name().toLowerCase();
    }

    /** ResourceKey → "namespace:path"（1.21.11 起 ResourceKey 改 identifier()）。 */
    private static String dimensionId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        return key
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
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
