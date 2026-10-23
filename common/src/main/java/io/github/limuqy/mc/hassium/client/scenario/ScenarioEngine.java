package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.client.SmokeProbeWriter;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
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
 * 场景选择：JVM 属性 {@code hassium.smokeScenario=<name>}；未设置时默认 {@code classic}
 * （与旧状态机默认行为完全一致）。场景文件从 classpath
 * {@code /hassium/smoke/scenario/<name>.scenario} 加载。
 * <p>
 * 退出码语义沿用旧状态机：0 两轮均通过；2 统计校验失败；3 进服超时；
 * 非 0 其它为运行错误。契约 marker（CLIENT_STATS/CLIENT/GATEWAY_CLIENT/PASS/FAIL/CLIENT_MODE）
 * 输出格式与时机不变（GATEWAY_CLIENT 行保留 state=RETIRED 兼容旧 harness grep）。
 */
public final class ScenarioEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");
    static final String MARKER_STATS = "HassiumSmokeTest:CLIENT_STATS";
    static final String MARKER_PASS = "HassiumSmokeTest:PASS";
    static final String MARKER_FAIL = "HassiumSmokeTest:FAIL";
    /** 每轮统计时输出 vanilla 直连会话状态与区块收发计数（新 harness 消费）。 */
    static final String MARKER_CLIENT = "HassiumSmokeTest:CLIENT";
    /** 旧网关断言 marker：网关轮次已退役，行保留且 state=RETIRED，兼容旧 harness grep 不崩。 */
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
    // 步间共享状态
    private static long disconnectAtMs = -1L;
    private static boolean round1Pass;
    private static boolean round2Pass;

    // 当前步骤局部状态（advance 时复位）
    private static long stepStartMs;
    private static boolean joinAnnounced;
    private static boolean dumpWaitAnnounced;
    private static boolean dimensionCommandSent;
    private static long resourcesReadyAtMs;
    /** join 等待诊断节流（每 5s 一行证据，见 execJoin）。 */
    private static long joinWaitLogAtMs;
    /** R2 重连有界自愈：本步骤是否已强制重连过一次。 */
    private static boolean reconnectRetried;

    // 飞行注入：服务端 TP 到安全高度并确认客户端同步后才平飞；平飞独立计时。
    private static long moveUntilMs = -1L;
    /** 1=等待服务端 TP 同步 2=平飞（按住前进） */
    private static int movePhase;
    private static long cruiseSeconds;
    private static int moveTargetY;

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

        String name = System.getProperty("hassium.smokeScenario");
        if (name == null || name.isBlank()) {
            name = "classic";
        }
        if ("integrated".equals(name)) IsolationSmokeProbe.arm();
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
        if ("egressstress".equals(name)) {
            var workload = io.github.limuqy.mc.hassium.protocol.egress.EgressStressWorkload.configured();
            io.github.limuqy.mc.hassium.protocol.egress.EgressStressWorkload.text(0);
            vars.put("egressStressWaitMs", Long.toString(workload.clientWaitMs()));
            vars.put("egressStressExpected", Integer.toString(workload.expected()));
        }
        vars.put("delayMs", Long.toString(delayMs));
        // 2026-09-18 用户指定：R1 喂满等待 40s、R2 20s（可用 JVM 属性覆盖）
        vars.put("round1WaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.round1WaitMs"), 40_000L)));
        // T7 dimension 场景：切维段等待（nether/back 默认 20s；END 段注入/算光更慢默认 30s，
        // 可用 hassium.smokeTest.dimWaitMs / endWaitMs 覆盖）
        vars.put("dimWaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.dimWaitMs"), Math.max(20_000L, delayMs * 2))));
        vars.put("endWaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.endWaitMs"), Math.max(30_000L, delayMs))));
        // R2：缓存/OVD 回填，20s 足够（用户 2026-09-18）
        vars.put("round2WaitMs", Long.toString(parseLong(
                System.getProperty("hassium.smokeTest.round2WaitMs"), 20_000L)));
        vars.put("reconnectDelayMs", Long.toString(reconnectDelayMs));
        vars.put("joinTimeoutMs", Long.toString(joinTimeoutMs));
        vars.put("moveSeconds", Long.toString(moveSeconds));
        // fly 是**非阻塞**原语，TP 到安全高度和平飞 Ns 由 tick 泵推进；
        // 后续 wait 会等飞行完成。moveWaitMs 另留场景稳定余量。
        // moveSeconds=0 时 fly 空操作，wait 必须为 0，避免 classic 每场白等 6s。
        vars.put("moveWaitMs", Long.toString(
                moveSeconds <= 0L ? 0L : moveSeconds * 1000L + 6_000L));
        vars.put("host", host);

        steps = ScenarioStep.parse(lines, vars);
        index = 0;
        stepStartMs = startAtMs = System.currentTimeMillis();
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
        if (finished) {
            return;
        }

        // 全局超时：两轮 joinTimeout + R1 40s + R2 20s + 重连间隔 + R2 dump 等 OVD
        if (startAtMs > 0L && now - startAtMs > joinTimeoutMs * 2 + 60_000L + reconnectDelayMs) {
            fail("global timeout in step " + currentDesc(), 3);
            return;
        }

        // 瞬时步骤（fly/disconnect/command/dump/exit）同 tick 内连续推进；
        // join/wait 等阻塞步骤 RUNNING 时让出本 tick
        for (int guard = 0; guard < 16 && !finished; guard++) {
            ScenarioStep step = steps.get(index);
            Outcome outcome;
            try {
                outcome = execute(step, mc, System.currentTimeMillis());
            } catch (RuntimeException e) {
                fail("step crashed: " + currentDesc() + " (" + e.getMessage() + ")", 3);
                return;
            }
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
        dimensionCommandSent = false;
        resourcesReadyAtMs = 0L;
        joinWaitLogAtMs = 0L;
        reconnectRetried = false;
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
            case INPUT -> execInput(step, mc, now);
            case SLEEP -> execSleep(step,mc,now);
            case PULL_ORDER -> io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.scenarioStep(
                    step.param("action"),(int)step.longParam("x",3),now-stepStartMs,step.longParam("timeoutMs",15000))
                    ? Outcome.DONE : Outcome.RUNNING;
            case DIMENSION -> execDimension(step, mc, now);
            case DISCONNECT -> execDisconnect(mc, now);
            case RECONNECT -> execReconnect(step, mc, now);
            case DUMP -> execDump(step, mc);
            case ASSERT_PROBE -> execAssertProbe(step, mc);
            case NBT -> { io.github.limuqy.mc.hassium.compat.CacheNbtProbeCompat.assertClient((int)step.longParam("round",1)); yield Outcome.DONE; }
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
            // 间歇「重连后登录卡死」取证：每 5s 一行（含三段 null 态），下一场复现即可区分
            // 「TCP/登录半开」与「连接压根没建立」。日志节流，正常场次每步只留少量行。
            if (now - joinWaitLogAtMs >= 5_000L) {
                joinWaitLogAtMs = now;
                LOGGER.info("HassiumSmokeTest: JOIN_WAIT {} elapsedMs={} conn={} player={} level={} screen={}",
                        label, base > 0L ? now - base : 0L,
                        mc.getConnection() != null, mc.player != null, mc.level != null,
                        mc.screen == null ? "none" : mc.screen.getClass().getName());
            }
            // 登录握手期间 getConnection() 仍为空；不能打断正在连接的整合包客户端。
            // 仅回到空闲/失败界面后允许一次自愈，避免产生两个并行登录连接。
            if (sinceDisconnect && !reconnectRetried && base > 0L && now - base > 15_000L
                    && mc.getConnection() == null && !joinInProgress(mc.screen)) {
                reconnectRetried = true;
                LOGGER.warn("HassiumSmokeTest: {} no connection after {}ms — forcing teardown + one reconnect retry",
                        label, now - base);
                try {
#if MC_VER < MC_1_21_1
                    mc.clearLevel();
#else
                    mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
#endif
                } catch (Throwable t) {
                    LOGGER.warn("HassiumSmokeTest: teardown before retry failed: {}", t.toString());
                }
                triggerReconnect(mc);
            }
            return Outcome.RUNNING;
        }
        // 单人内嵌服不计入多人连服冒烟
        if (mc.getSingleplayerServer() != null && !step.boolParam("integrated", false)) {
            return Outcome.RUNNING;
        }
        // Legacy smoke worlds use positive Y; imported void/deep worlds may spawn below zero.
        if (step.boolParam("requirePositiveY", true) && mc.player.getY() <= 0) {
            return Outcome.RUNNING;
        }
        if (!joinAnnounced) {
            joinAnnounced = true;
            LOGGER.info("HassiumSmokeTest: {} player entered world at y={}", label, mc.player.getY());
            // 会话起点置位点：单轮场景不调 SmokeChunkTrace.reset()，
            // 起点只能在这里落下，否则 probe 的 sessionToAllMs 恒为 -1。
            io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.markSessionStart();
        }
        return Outcome.DONE;
    }

    static boolean joinInProgress(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof net.minecraft.client.gui.screens.ConnectScreen
                || io.github.limuqy.mc.hassium.compat.ClientLoadingScreenCompat.isTerrainLoadingScreen(screen);
    }

    // ------------------------------------------------------------------ wait

    private static Outcome execWait(ScenarioStep step, Minecraft mc, long now) {
        String raw = step.param("ms");
        if (raw != null && raw.contains("${")) {
            fail("unresolved wait ms=" + raw + " at " + step, 3);
            return Outcome.DONE;
        }
        long ms;
        try {
            ms = step.longParam("ms", 0L);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage(), 3);
            return Outcome.DONE;
        }
        // fly 非阻塞；后续 wait 仍须等 TP 同步与实际平飞完成。
        return now - stepStartMs >= ms && movePhase == 0 ? Outcome.DONE : Outcome.RUNNING;
    }

    /** 非阻塞飞行注入：服务端 TP 到安全高度，客户端确认位置后再平飞 Ns。 */
    private static Outcome execFly(ScenarioStep step, Minecraft mc, long now) {
        long seconds = step.longParam("seconds", 0L);
        if (seconds <= 0L || mc.player == null) {
            cruiseSeconds = 0L;
            return Outcome.DONE;
        }
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.level == null) {
            fail("MOVE_TP_NO_CONNECTION", 3);
            return Outcome.DONE;
        }
        mc.player.getAbilities().flying = true;
        movePhase = 1;
        cruiseSeconds = seconds;
        int startY = mc.player.blockPosition().getY();
        moveTargetY = Math.min(LevelHeightCompat.getMaxBlockYExclusive(mc.level) - 16, (int) step.longParam("targetY", Math.max(256, startY + 16)));
        if (moveTargetY <= startY + 1) {
            fail("MOVE_TP_NO_HEADROOM startY=" + startY + " maxY=" + LevelHeightCompat.getMaxBlockYExclusive(mc.level), 3);
            return Outcome.DONE;
        }
        moveUntilMs = now + 20_000L;
        LOGGER.info("HassiumSmokeTest:MOVE_TP_REQUEST targetY={} pos=({}, {}, {})", moveTargetY,
                mc.player.blockPosition().getX(), startY,
                mc.player.blockPosition().getZ());
        conn.sendCommand("tp @s ~ " + moveTargetY + " ~");
        return Outcome.DONE;
    }

    /** 飞行注入每 tick 推进（语义照搬旧状态机 moveUntilMs/movePhase 块）。 */
    private static void tickMovement(Minecraft mc, long now) {
        if (moveUntilMs <= 0L) {
            return;
        }
        // Match the unattended INPUT runner: a native pause screen clears simulated keys.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        mc.mouseHandler.releaseMouse();
        if (mc.player == null) {
            moveUntilMs = -1L;
            movePhase = 0;
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            fail("MOVE_INTERRUPTED player disappeared", 3);
        } else if (movePhase == 1) {
            if (mc.player.getY() >= moveTargetY - 1) {
                // 以 TP 同步后的当前位置为水平段基准，TP 本身绝不计入水平位移。
                movePhase = 2;
                io.github.limuqy.mc.hassium.client.SmokeProbeWriter.resetOvdMovementProbe();
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                moveUntilMs = now + cruiseSeconds * 1000L;
                LOGGER.info("HassiumSmokeTest:MOVE_START cruise {}s pos=({}, {}, {}) targetY={}",
                        cruiseSeconds, mc.player.blockPosition().getX(),
                        mc.player.blockPosition().getY(), mc.player.blockPosition().getZ(), moveTargetY);
            } else if (now >= moveUntilMs) {
                moveUntilMs = -1L;
                movePhase = 0;
                fail("MOVE_TP_TIMEOUT targetY=" + moveTargetY + " actualY=" + mc.player.getY(), 3);
            }
        } else if (movePhase == 2) {
            io.github.limuqy.mc.hassium.client.SmokeProbeWriter.sampleOvdMovement(mc);
            if (now >= moveUntilMs) {
                LOGGER.info("HassiumSmokeTest:MOVE_INPUT flying={} forward={} yaw={} pitch={}",
                        mc.player.getAbilities().flying, mc.options.keyUp.isDown(),
                        mc.player.getYRot(), mc.player.getXRot());
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
                moveUntilMs = -1L;
                movePhase = 0;
                LOGGER.info("HassiumSmokeTest:MOVE_END pos=({}, {}, {})",
                        mc.player.blockPosition().getX(), mc.player.blockPosition().getY(),
                        mc.player.blockPosition().getZ());
            } else {
                // TP 后客户端输入状态可能被同步清空，水平段每 tick 重新维持按键。
                mc.player.getAbilities().flying = true;
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
            }
        }
    }

    // --------------------------------------------------------------- command

    private static Outcome execCommand(ScenarioStep step, Minecraft mc, long now) {
        ClientPacketListener conn = mc.getConnection();
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

    private static int profileInputStep = -1;
    private static int sleepInputStep = -1;
    private static boolean sleepAutoEntering;
    private static boolean sleepAutoReleased;
    private static Object sleepResumeConnection,sleepResumeLevel,sleepResumePlayer,sleepResumeNear;
    private static int sleepResumeUsingTicks;
    private static int sleepResumeMiningStage;
    private static long sleepTrafficReadyMs;
    private static Outcome execSleep(ScenarioStep step, Minecraft mc, long now) {
        String action=step.param("action");
        if(sleepInputStep!=index) {
            sleepInputStep=index;
            if("disabled".equals(action)) {
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(1000);
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.enter();
                mc.pauseGame(false);
                for(var child:mc.screen.children()) if(child instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("hassium.sleep.enter").getString()))
                    throw new IllegalStateException("Disabled sleep still has a menu entry");
                mc.setScreen(null);
            }
            else if("auto_menu".equals(action)) {
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(1000);
                mc.setScreen(new io.github.limuqy.mc.hassium.client.sleep.SleepSetupScreen(null));
            }
            else if("auto".equals(action)) {
                sleepAutoEntering=false; sleepAutoReleased=false;
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(1500);
                mc.options.keyAttack.setDown(true); mc.options.keyUse.setDown(true);
            }
            else if("release".equals(action)) {
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(0);
                mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
            }
            else if("enter".equals(action)) {
                if(step.boolParam("ui",false)) {
                    mc.pauseGame(false);
                    pressSleepButton(mc,"hassium.sleep.enter");
                    pressSleepButton(mc,"hassium.sleep.enter");
                } else io.github.limuqy.mc.hassium.client.sleep.SleepClient.enter();
            }
            else if("observer".equals(action)) { io.github.limuqy.mc.hassium.compat.SleepProbeCompat.assertObserver(); return Outcome.DONE; }
            else if("afk_baseline".equals(action)) { io.github.limuqy.mc.hassium.compat.SleepProbeCompat.assertAfk(); return Outcome.DONE; }
            else if("capture".equals(action)) { io.github.limuqy.mc.hassium.compat.SleepProbeCompat.capture("sleep-overlay"); return Outcome.DONE; }
            else if("geometry_begin".equals(action)) { io.github.limuqy.mc.hassium.compat.SleepProbeCompat.beginGeometry(); return Outcome.DONE; }
            else if("geometry".equals(action) || "geometry_awake".equals(action)) {
                io.github.limuqy.mc.hassium.compat.SleepProbeCompat.assertGeometry("geometry_awake".equals(action)); return Outcome.DONE;
            }
            else if("arm_held".equals(action)) { mc.options.keyAttack.setDown(true); return Outcome.DONE; }
            else if("held".equals(action)) {
                if(!mc.options.keyAttack.isDown() || mc.options.keyAttack.consumeClick()) throw new IllegalStateException("Sleep held input lost or synthesized a click");
                LOGGER.info("HassiumSmokeTest:SLEEP held input preserved, no generated click"); return Outcome.DONE;
            }
            else if("entity".equals(action)) {
                boolean present=false;
                if(mc.level!=null) for(var e:mc.level.entitiesForRendering())
                    if(e.getType()==net.minecraft.world.entity.EntityType.PIG && Math.abs(e.getX()-Double.parseDouble(step.param("x")))<1) present=true;
                if(present!=step.boolParam("present",true)) throw new IllegalStateException("Sleep entity visibility x="+step.param("x")+" present="+present);
                LOGGER.info("HassiumSmokeTest:SLEEP entity x={} present={}",step.param("x"),present); return Outcome.DONE;
            }
            else if("world_current".equals(action)) {
                io.github.limuqy.mc.hassium.compat.SleepProbeCompat.assertWakeWorld(); return Outcome.DONE;
            }
            else if("world_paused".equals(action)) {
                io.github.limuqy.mc.hassium.compat.SleepProbeCompat.assertPausedWorld(); return Outcome.DONE;
            }
            else if("traffic_wake".equals(action)) {
                if(!io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.restricted())
                    throw new IllegalStateException("Traffic profile did not negotiate the sleep window");
                sleepResumeConnection=mc.getConnection().getConnection(); sleepResumeLevel=mc.level; sleepResumePlayer=mc.player;
                sleepTrafficReadyMs=-1;
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.wake();
            }
            else if("mining_ready".equals(action)) { /* Wait below for native mining to begin. */ }
            else if("resume".equals(action)) {
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(0);
                if(!io.github.limuqy.mc.hassium.client.sleep.SleepClient.supported()
                        || io.github.limuqy.mc.hassium.client.sleep.SleepClient.state()!=io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.SLEEPING)
                    throw new IllegalStateException("In-place sleep capability/state missing");
                sleepResumeConnection=mc.getConnection().getConnection(); sleepResumeLevel=mc.level; sleepResumePlayer=mc.player;
                sleepResumeNear=null;
                for(var e:mc.level.entitiesForRendering()) if(e.getType()==net.minecraft.world.entity.EntityType.PIG && Math.abs(e.getX()-5.5)<1) sleepResumeNear=e;
                if(sleepResumeNear==null) throw new IllegalStateException("Nearby entity fixture missing");
                if(step.boolParam("using",false) && !mc.player.isUsingItem()) throw new IllegalStateException("Bow use did not start before wake");
                sleepResumeUsingTicks=mc.player.getTicksUsingItem();
                sleepResumeMiningStage=mc.gameMode.getDestroyStage();
                if(step.boolParam("mining",false) && (!mc.gameMode.isDestroying() || sleepResumeMiningStage<0))
                    throw new IllegalStateException("Mining fixture stopped before wake: "+miningFixtureState(mc));
                String trigger=step.param("trigger");
                if("esc".equals(trigger)) io.github.limuqy.mc.hassium.compat.SleepClientCompat.pressEscape();
                else if("move".equals(trigger)) mc.options.keyUp.setDown(true);
                else if("sprint".equals(trigger)) mc.options.keySprint.setDown(true);
                else if("turn".equals(trigger)) mc.player.setYRot(mc.player.getYRot()+25);
                else throw new IllegalArgumentException("Unknown resume trigger "+trigger);
            }
            else if("equipment".equals(action)) {
                int damage=mc.player.getMainHandItem().getDamageValue();
                if(damage<step.longParam("minimumDamage",1)) throw new IllegalStateException("Sleep inventory durability not synchronized: "+damage);
                LOGGER.info("HassiumSmokeTest:SLEEP inventory damage={}",damage); return Outcome.DONE;
            }
            else if("wake".equals(action)) {
                io.github.limuqy.mc.hassium.client.sleep.SleepClient.wake();
            } else throw new IllegalArgumentException("Unknown sleep action "+action);
        }
        var state=io.github.limuqy.mc.hassium.client.sleep.SleepClient.state();
        if("disabled".equals(action)) {
            if(io.github.limuqy.mc.hassium.client.sleep.SleepClient.supported()
                    || state!=io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.IDLE)
                throw new IllegalStateException("Disabled sleep negotiated or entered");
            if(now-stepStartMs<2500) return Outcome.RUNNING;
            io.github.limuqy.mc.hassium.client.sleep.SleepClient.testAutoDelay(0);
            LOGGER.info("HassiumSmokeTest:SLEEP disabled capability/menu/manual/automatic verified"); return Outcome.DONE;
        }
        if("traffic_wake".equals(action)) {
            if(mc.getConnection()==null || mc.getConnection().getConnection()!=sleepResumeConnection || mc.level!=sleepResumeLevel || mc.player!=sleepResumePlayer)
                throw new IllegalStateException("Traffic recovery recreated connection/world/player");
            int pending=0;
            int vd=io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.serverViewDistance();
            int radius=io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.boundingRadius(vd);
            var center=mc.player.chunkPosition();
            for(int x=center.x-radius;x<=center.x+radius;x++) for(int z=center.z-radius;z<=center.z+radius;z++)
                if(io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.contains(center.x,center.z,vd,x,z)
                        && !io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.visible(x,z)) pending++;
            if(state==io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.IDLE && pending==0) {
                if(sleepTrafficReadyMs<0) sleepTrafficReadyMs=now-stepStartMs;
                if(now-stepStartMs>=30_000) {
                    LOGGER.info("HassiumSmokeTest:SLEEP traffic recovery sameConnection=true visiblePending=0 readyMs={} observationMs={}",sleepTrafficReadyMs,now-stepStartMs);
                    return Outcome.DONE;
                }
            }
            if(now-stepStartMs>120_000) throw new IllegalStateException("Traffic recovery not complete: pending="+pending);
            return Outcome.RUNNING;
        }
        if("mining_ready".equals(action)) {
            if(mc.gameMode.isDestroying() && mc.gameMode.getDestroyStage()>=0) {
                LOGGER.info("HassiumSmokeTest:SLEEP native mining fixture ready stage={} elapsedMs={}",
                        mc.gameMode.getDestroyStage(),now-stepStartMs);
                return Outcome.DONE;
            }
            if(now-stepStartMs>5000) throw new IllegalStateException("Mining fixture did not start within 5s: "
                    +miningFixtureState(mc));
            return Outcome.RUNNING;
        }
        if("resume".equals(action)) {
            boolean escape="esc".equals(step.param("trigger"));
            if(mc.getConnection()==null || mc.getConnection().getConnection()!=sleepResumeConnection || mc.level!=sleepResumeLevel || mc.player!=sleepResumePlayer)
                throw new IllegalStateException("In-place resume recreated connection/world/player");
            if(!escape && (!mc.options.keyAttack.isDown() || !mc.options.keyUse.isDown()))
                throw new IllegalStateException("In-place resume interrupted held input");
            if(!escape && step.boolParam("using",false) && !mc.player.isUsingItem()) throw new IllegalStateException("In-place resume interrupted bow use");
            if(!escape && step.boolParam("using",false)) {
                if(mc.player.getTicksUsingItem()<sleepResumeUsingTicks) throw new IllegalStateException("In-place resume restarted use duration");
                sleepResumeUsingTicks=mc.player.getTicksUsingItem();
            }
            if(step.boolParam("mining",false)) {
                if(!mc.gameMode.isDestroying() || mc.gameMode.getDestroyStage()<sleepResumeMiningStage)
                    throw new IllegalStateException("Resume interrupted mining progress");
                sleepResumeMiningStage=mc.gameMode.getDestroyStage();
            }
            boolean sameNear=false;
            for(var e:mc.level.entitiesForRendering()) if(e==sleepResumeNear) sameNear=true;
            if(!sameNear) throw new IllegalStateException("In-place resume replaced nearby entity");
            // Movement only triggers wake; attack/use stay held until the entire visible
            // selection is restored (including the separate NBT-only tail fixture).
            if(now-stepStartMs>=200) mc.options.keyUp.setDown(false);
            int pendingVisible=0;
            java.util.List<String> pendingColumns=new java.util.ArrayList<>();
            int vd=io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.serverViewDistance();
            int radius=io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.boundingRadius(vd);
            var center=mc.player.chunkPosition();
            for(int x=center.x-radius;x<=center.x+radius;x++) for(int z=center.z-radius;z<=center.z+radius;z++)
                if(io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.contains(center.x,center.z,vd,x,z)
                        && !io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.visible(x,z)) {
                    pendingVisible++;
                    if(pendingColumns.size()<16) pendingColumns.add(x+","+z+" {"+
                            io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.snapshotState(
                                    io.github.limuqy.mc.hassium.utils.DimensionKey.key(
                                            io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(mc.level),x,z))+"}");
                }
            if(now-stepStartMs>10_000) throw new IllegalStateException("In-place resume timed out: state="+state
                    +" pending="+pendingVisible+" sample="+pendingColumns);
            if(state!=io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.IDLE || now-stepStartMs<1000
                    || pendingVisible!=0) return Outcome.RUNNING;
            if(escape && !(mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen)) throw new IllegalStateException("Resume ACK stole Esc menu");
            if(!escape && mc.screen!=null) throw new IllegalStateException("In-place resume opened a screen");
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            if(escape) mc.setScreen(null);
            LOGGER.info("HassiumSmokeTest:SLEEP in-place trigger={} identity/input/near-entity preserved using={} visiblePending=0 elapsedMs={}",step.param("trigger"),step.boolParam("using",false),now-stepStartMs);
            if(step.boolParam("mining",false)) LOGGER.info("HassiumSmokeTest:SLEEP actual mining progress preserved stage={}",sleepResumeMiningStage);
            return Outcome.DONE;
        }
        if("auto_menu".equals(action)) {
            if(state!=io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.IDLE || !(mc.screen instanceof io.github.limuqy.mc.hassium.client.sleep.SleepSetupScreen))
                throw new IllegalStateException("Auto sleep stole an open screen");
            if(now-stepStartMs<2500) return Outcome.RUNNING;
            io.github.limuqy.mc.hassium.compat.SleepProbeCompat.capture("sleep-settings");
            for(var child:mc.screen.children()) if(child instanceof net.minecraft.client.gui.components.EditBox input) input.setValue("15");
            pressSleepButton(mc,"hassium.sleep.save");
            if(mc.screen!=null) throw new IllegalStateException("Sleep settings did not return to game");
            if(io.github.limuqy.mc.hassium.client.sleep.SleepProfiles.autoSeconds()!=900)
                throw new IllegalStateException("Configured 15-minute sleep delay was not saved");
            LOGGER.info("HassiumSmokeTest:SLEEP auto menu gate and settings save verified"); return Outcome.DONE;
        }
        if("release".equals(action)) {
            if(mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) throw new IllegalStateException("Sleep replayed released input");
            if(now-stepStartMs<1500) return Outcome.RUNNING;
            LOGGER.info("HassiumSmokeTest:SLEEP released input remained released"); return Outcome.DONE;
        }
        if("auto".equals(action)) {
            if(mc.screen!=null || mc.options.keyAttack.isDown()==sleepAutoReleased || !mc.options.keyUse.isDown())
                throw new IllegalStateException("Auto sleep interrupted held input or focus");
            if(state==io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.ENTERING) {
                sleepAutoEntering=true;
                if(step.boolParam("releaseDuringEntry",false)) { mc.options.keyAttack.setDown(false); sleepAutoReleased=true; }
            }
            if(state==io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.SLEEPING) {
                if(!sleepAutoEntering) throw new IllegalStateException("Auto sleep handshake was not observed by input probe");
                LOGGER.info("HassiumSmokeTest:SLEEP automatic entry input uninterrupted; attackReleasedDuringEntry={}",sleepAutoReleased); return Outcome.DONE;
            }
            if(now-stepStartMs>10_000) throw new IllegalStateException("Auto sleep did not enter");
            return Outcome.RUNNING;
        }
        boolean done="enter".equals(action) ? state==io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.SLEEPING
                : state==io.github.limuqy.mc.hassium.client.sleep.SleepClient.State.IDLE && mc.player!=null;
        if(done && "wake".equals(action)) {
            // A real unload makes restoration asynchronous; sample after the selected columns recover.
            int vd=io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.serverViewDistance();
            int radius=io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.boundingRadius(vd);
            var center=mc.player.chunkPosition();
            for(int x=center.x-radius;x<=center.x+radius && done;x++) for(int z=center.z-radius;z<=center.z+radius;z++)
                if(io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.contains(center.x,center.z,vd,x,z)
                        && !io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.visible(x,z)) { done=false; break; }
        }
        if(done) { LOGGER.info("HassiumSmokeTest:SLEEP action={} state={}",action,state); return Outcome.DONE; }
        if(now-stepStartMs>120_000) { fail("SLEEP timeout action="+action+" state="+state,3); return Outcome.DONE; }
        return Outcome.RUNNING;
    }
    private static void pressSleepButton(Minecraft mc,String key) {
        if(mc.screen==null) throw new IllegalStateException("Sleep screen missing");
        String title=net.minecraft.network.chat.Component.translatable(key).getString();
        for(var child:mc.screen.children()) if(child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().equals(title)) { io.github.limuqy.mc.hassium.compat.SleepClientCompat.pressButton(button); return; }
        throw new IllegalStateException("Sleep button missing: "+key);
    }
    private static String miningFixtureState(Minecraft mc) {
        var hit=mc.hitResult;
        var block=hit instanceof net.minecraft.world.phys.BlockHitResult b ? b.getBlockPos() : null;
        return "stage="+mc.gameMode.getDestroyStage()+" destroying="+mc.gameMode.isDestroying()
                +" grabbed="+mc.mouseHandler.isMouseGrabbed()+" screen="+mc.screen
                +" hit="+hit+" target="+(block==null?"none":mc.level.getBlockState(block))
                +" player="+mc.player.position()+" yaw="+mc.player.getYRot()+" pitch="+mc.player.getXRot()
                +" using="+mc.player.isUsingItem()+" instabuild="+mc.player.getAbilities().instabuild
                +" attack="+mc.options.keyAttack.isDown();
    }
    private static long profileAttackCycle = -1;
    private static boolean profileAttacking;

    private static Outcome execInput(ScenarioStep step, Minecraft mc, long now) {
        if (mc.player == null) return Outcome.RUNNING;
        String expectedSleep=step.param("sleepState");
        if(expectedSleep!=null) {
            var sleep=io.github.limuqy.mc.hassium.client.sleep.SleepClient.state();
            if(!expectedSleep.equals(sleep.name())) throw new IllegalStateException("Traffic phase sleep state="+sleep+" expected="+expectedSleep);
            if("SLEEPING".equals(expectedSleep)) {
                if(!io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.restricted()) throw new IllegalStateException("Traffic sleep window missing");
                var center=mc.player.chunkPosition(); int visible=0,light=0;
                for(int x=center.x-4;x<=center.x+4;x++) for(int z=center.z-4;z<=center.z+4;z++) {
                    if(io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.visible(x,z)) visible++;
                    if(io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.allows(x,z)) light++;
                }
                if(visible!=9 || light!=25) throw new IllegalStateException("Traffic window geometry="+visible+"/"+light);
                if(mc.level.getChunkSource().getLoadedChunksCount()!=25) throw new IllegalStateException("Traffic sleep must retain 9 active columns and 16 native neighbours");
            }
        }
        // Profiling runs unattended; the native pause screen suppresses keyboard movement.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        mc.mouseHandler.releaseMouse();
        long elapsed = now - stepStartMs;
        boolean running = elapsed < step.longParam("ms", 60_000L);
        String mode = step.param("mode");
        if (!java.util.Set.of("walk", "idle", "hover", "hold_attack", "periodic_attack", "entity_attack").contains(mode))
            throw new IllegalArgumentException("Unknown profiling input mode: " + mode);
        if (profileInputStep != index) {
            profileInputStep = index;
            profileAttackCycle = -1;
            profileAttacking = false;
            LOGGER.info("HassiumSmokeTest: INPUT_BEGIN mode={} screen={} pos=({}, {}, {})",
                    mode, mc.screen, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }
        mc.options.keyUp.setDown(running && "walk".equals(mode));
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        if ("hover".equals(mode) && !mc.player.getAbilities().flying) {
            if (!mc.player.getAbilities().mayfly) throw new IllegalStateException("Hover requires native flight permission");
            mc.player.getAbilities().flying = true;
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.onUpdateAbilities();
        }
        if (running && "walk".equals(mode)) {
            // Four 4-second legs; ordinary walking speed, no teleport or sprint in the window.
            mc.player.setYRot((float) ((elapsed / 4000L) % 4L) * 90.0f);
            mc.player.setXRot(10.0f);
        }
        if (java.util.Set.of("hold_attack", "periodic_attack").contains(mode) && mc.gameMode != null) {
            boolean attacking = running && ("hold_attack".equals(mode) || elapsed % 1000L < 100L);
            // Use native interaction APIs: background windows cannot acquire the mouse.
            // No synthetic network packets. The fixture target is unbreakable in survival.
            var target = new net.minecraft.core.BlockPos((int)step.longParam("targetX",30), (int)step.longParam("targetY",139), (int)step.longParam("targetZ",5));
            long cycle = "hold_attack".equals(mode) ? 0 : elapsed / 1000L;
            if (attacking) {
                if (profileAttackCycle != cycle) {
                    mc.gameMode.startDestroyBlock(target, net.minecraft.core.Direction.UP);
                    profileAttackCycle = cycle;
                } else {
                    mc.gameMode.continueDestroyBlock(target, net.minecraft.core.Direction.UP);
                }
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            } else if (profileAttacking) {
                mc.gameMode.stopDestroyBlock();
            }
            profileAttacking = attacking;
        }
        if(running && "entity_attack".equals(mode) && mc.gameMode!=null && elapsed/1000L!=profileAttackCycle) {
            profileAttackCycle=elapsed/1000L;
            for(var entity:mc.level.entitiesForRendering()) if(entity.getType()==net.minecraft.world.entity.EntityType.PIG
                    && entity.isAlive() && entity.distanceToSqr(mc.player)<9) {
                mc.gameMode.attack(mc.player,entity); mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND); break;
            }
        }
        if (!running) LOGGER.info("HassiumSmokeTest: INPUT_END mode={} pos=({}, {}, {})",
                mode, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        return running ? Outcome.RUNNING : Outcome.DONE;
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
        if (!dimensionCommandSent) {
            LOGGER.info("HassiumSmokeTest:DIM_CHANGE to={}", target);
            // sendCommand 即命令路径（ServerboundChatCommandPacket，无需斜杠）；/execute 需 OP（level 2），
            // 服务端冒烟由 ServerSmokeTest 按 hassium.serverSmokeScenario 自动 op。
            // 同一连接按序执行：先免疫窒息/坠落，再 TP，避免死亡重生污染维度/缓存场景。
            conn.sendCommand("gamemode creative");
            conn.sendCommand("execute in " + target + " run tp @s ~ ~ ~");
            dimensionCommandSent = true;
            return Outcome.RUNNING;
        }
        String actual = dimensionId(mc.player.level().dimension());
        if (target.equals(actual)) {
            LOGGER.info("HassiumSmokeTest:DIM_CHANGE arrived {}", target);
            return Outcome.DONE;
        }
        long timeout = step.longParam("timeoutMs", 20_000L);
        if (now - stepStartMs > timeout) {
            fail("dimension change timeout to=" + target + " actual=" + actual, 2);
            return Outcome.DONE;
        }
        return Outcome.RUNNING;
    }

    // ------------------------------------------------------------ disconnect / reconnect

    private static Outcome execDisconnect(Minecraft mc, long now) {
        triggerDisconnect(mc);
        disconnectAtMs = now;
        return Outcome.DONE;
    }

    private static Outcome execReconnect(ScenarioStep step, Minecraft mc, long now) {
        // Production-pack tests can follow a normal menu connection after the initial resource overlay.
        if (step.boolParam("waitForResources", false)) {
            if (mc.getOverlay() != null) {
                resourcesReadyAtMs = 0L;
                return Outcome.RUNNING;
            }
            // Let initial menu callbacks settle before opening ConnectScreen.
            if (resourcesReadyAtMs == 0L) resourcesReadyAtMs = now;
            if (now - resourcesReadyAtMs < 1_500L) return Outcome.RUNNING;
        }
        long wait = step.longParam("delayMs", reconnectDelayMs);
        if (disconnectAtMs > 0L && now - disconnectAtMs < wait) {
            return Outcome.RUNNING;
        }
        // 仅当玩家和连接仍完整存在时，才视为断连未生效并跳过重连。
        // channelInactive 期间 player 可能尚未清空；此时必须继续重连，否则后续 join 会一直等待连接。
        if (mc.player != null && mc.getConnection() != null && mc.level != null) {
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
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.resetHashClassify();
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.resetLightProbeCounters();
            io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.reset();
            LOGGER.info("HassiumSmokeTest: network stats reset for ROUND2");
        } catch (Throwable t) {
            LOGGER.warn("HassiumSmokeTest: failed to reset network stats", t);
        }
    }

    /** Reconnect through remapped, typed calls in the version compatibility layer. */
    private static void triggerReconnect(Minecraft mc) {
        try {
            LOGGER.info("HassiumSmokeTest: connecting to {}", host);
            io.github.limuqy.mc.hassium.compat.SmokeConnectCompat.connect(mc, host);
            LOGGER.info("HassiumSmokeTest: reconnect triggered");
        } catch (Throwable t) {
            LOGGER.error("HassiumSmokeTest: reconnect failed", t);
        }
    }

    // ------------------------------------------------------------------ dump

    private static Outcome execDump(ScenarioStep step, Minecraft mc) {
        int round = (int) step.longParam("round", 1L);
        boolean isRound1 = round <= 1;
        String roundLabel = isRound1 ? "ROUND1" : "ROUND2";
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
            dumpRoundMarkers(roundLabel);
            SmokeProbeWriter.writeRound(round, mc);
            if (!step.boolParam("gate", true)) {
                LOGGER.info("{} {} stats gate=false (validation skipped)", MARKER_STATS, roundLabel);
                return Outcome.DONE;
            }
            boolean ok;
            if (step.boolParam("vanilla", false) || step.boolParam("world",false)) {
                var content = SmokeProbeWriter.sampleContentCheck(mc);
                ok = SmokeProbeWriter.currentLoadedChunkCount(mc) > 64 && content.chunksWithNonAir() > 0;
                LOGGER.info("HassiumSmokeTest: world delivery {} loaded={} nonAir={} pass={}",
                        roundLabel, SmokeProbeWriter.currentLoadedChunkCount(mc), content.chunksWithNonAir(), ok);
            } else {
                boolean coldCache = step.boolParam("coldCache", true);
                if (!coldCache) LOGGER.info("HassiumSmokeTest: {} permits an existing disk cache", roundLabel);
                ok = validateStats(plain, roundLabel, coldCache);
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
        return Outcome.DONE;
    }

    /**
     * 每轮统计 marker：
     * <ul>
     *   <li>新行 {@code HassiumSmokeTest:CLIENT ROUND<n> state=READY s2c=<applied> c2s=<requests> resume=false}
     *       —— vanilla 直连会话就绪 + 客户端区块收发计数；</li>
     *   <li>旧行 {@code HassiumSmokeTest:GATEWAY_CLIENT ROUND<n> state=RETIRED s2c=0 c2s=0 resume=false}
     *       —— 网关轮次已退役，保留同格式行（state=RETIRED）兼容旧 harness grep 不崩。</li>
     * </ul>
     */
    private static void dumpRoundMarkers(String roundLabel) {
        try {
            io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl m =
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.getMetrics();
            long applied = m.getClientAppliedChunkCount();
            long requests = m.getFullChunkRequestCount();
            LOGGER.info("{} {} state=READY s2c={} c2s={} resume=false",
                    MARKER_CLIENT, roundLabel, applied, requests);
        } catch (Throwable t) {
            LOGGER.error("{} {} state=ERROR s2c=0 c2s=0 resume=false (dump failed: {})",
                    MARKER_CLIENT, roundLabel, t.toString());
        }
        LOGGER.info("{} {} state=RETIRED s2c=0 c2s=0 resume=false", MARKER_GATEWAY, roundLabel);
    }

    // ---------------------------------------------------------- assertProbe

    /**
     * 场景内门禁断言（T6 seedgen 引入；dimension 场景复用）：
     * <pre>
     *   assertProbe key=counters.locallyGenerated op=gt value=0
     *   assertProbe key=stats.staleFullChunkRequestCount op=lt vs=stats.clientAppliedChunkCount
     *   assertProbe key=joined op=eq value=true
     *   assertProbe key=dimension op=eq value=minecraft:the_nether
     *   assertProbe key=clientCache.loadedChunks op=gt value=64
     * </pre>
     * key path 与 {@link SmokeProbeWriter} PROBE JSON 键同名（counters.* / stats.* /
     * clientCache.* / chunkTrace.* / 顶层 joined、dimension）。数值字段支持
     * gt/ge/lt/le/eq，且可用 vs=&lt;path&gt; 做字段对字段比较；布尔字段（joined）
     * 与字符串字段（dimension）只支持 eq。断言失败按统计
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
        // 布尔等值：joined（value=true/false 字面量）
        if ("joined".equals(key)) {
            boolean actual = mc != null && mc.player != null && mc.level != null;
            boolean expected = step.boolParam("value", false);
            boolean ok = "eq".equals(op) && actual == expected;
            probeResult(step, key, op, Boolean.toString(expected), Boolean.toString(actual), ok);
            return Outcome.DONE;
        }
        // 数值比较
        Long actual = readNumericProbe(key, mc);
        if (actual == null) {
            fail("assertProbe unknown key '" + key + "' at " + step, 1);
            return Outcome.DONE;
        }
        long expected;
        String expectDesc;
        String vsKey = step.param("vs");
        if (vsKey != null) {
            Long other = readNumericProbe(vsKey, mc);
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
     * assertProbe 数值取值：键名与 {@link SmokeProbeWriter} appendStats/appendCounters
     * 一一对应（PROBE JSON 消费方可用同名键写场景断言）。
     * 返回 null = 未知键。
     */
    private static Long readNumericProbe(String key, Minecraft mc) {
        io.github.limuqy.mc.hassium.metrics.HassiumMetricsImpl m =
                io.github.limuqy.mc.hassium.metrics.NetworkStats.getMetrics();
        String dim = mc != null && mc.player != null && mc.level != null
                ? dimensionId(mc.player.level().dimension()) : null;
        io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.Snapshot trace =
                io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.snapshot(dim);
        return switch (key) {
            case "pullOrder.passes" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.passes();
            case "pullOrder.failures" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.failures();
            case "pullOrder.inflight" -> (long)io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount();
            case "pullOrder.targetInflight" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.targetInflight();
            case "pullOrder.reservedBytes" -> io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.reservedBytes();
            case "pullOrder.officialLanded" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.officialLanded();
            case "pullOrder.officialExerciseLanded" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.officialExerciseLanded();
            case "pullOrder.sameHashFull" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.sameHashFull();
            case "pullOrder.haloPhases" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.haloPhases();
            case "pullOrder.nativeFulls" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.nativeFulls();
            case "pullOrder.responses" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.responses();
            case "pullOrder.afterReceipts" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.afterReceipts();
            case "pullOrder.applications" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.applications();
            case "pullOrder.checks" -> (long)io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.checks();
            case "trace.ovdGenerated" -> (long) io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.ovdGenerated(dim).size();
            case "trace.ovdGeneratedApplied" -> (long) io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace.ovdGeneratedApplied(dim).size();
            case "isolation.integrated" -> mc.getSingleplayerServer() != null ? 1L : 0L;
            case "isolation.shadowRunning" -> io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get() != null ? 1L : 0L;
            case "isolation.processShadowFlag" -> io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext() ? 1L : 0L;
            case "isolation.scheduleTicks" -> IsolationSmokeProbe.scheduleTicks(mc);
            case "isolation.memoryConnection" -> mc.getConnection() != null && mc.getConnection().getConnection().isMemoryConnection() ? 1L : 0L;
            case "egress.fixtureReceived" -> (long)io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.receivedCount();
            // counters.*（appendCounters 同名）
            case "counters.sectionDeltaRequestsSent" -> m.getSectionDeltaRequestsSent();
            case "counters.sectionDeltaApplied" -> m.getSectionDeltaChunksReceived();
            case "counters.lightSegRecalc" -> m.getLightCacheMissCount();
            case "counters.ovdLoaded" -> m.getOvdLoadedCount();
            case "counters.ovdMiss" -> m.getOvdMissCount();
            case "counters.locallyGenerated" -> m.getLocallyGeneratedChunkCount();
            // 【2026-09-21】seedgen 场景的核心不变量（门控开时无基线柱不得被抢成空基线权威 FULL）。
            // 冷启动与热复用两态都成立（seedGen 关时该计数 ~2000）；取代原先的
            // counters.locallyGenerated > 0（后者是冷场专属读数，热复用场必然 0）。
            case "counters.authoritativeRequests" ->
                    io.github.limuqy.mc.hassium.protocol.ShadowPullClient.authoritativeRequestCount();
            // 往返飞行黑块专项（flyroundtrip 门禁锚；判定值取落地后下一帧复检的 post-apply 采样）：
            //   clientDarkRegressionChunks —— 仍黑且曾亮（「已缓存区块被打黑」，门禁主锚）
            //   clientDarkLightProbeChunks —— 诊断时刻仍黑的柱数（观测）
            //   clientDarkLightProbeSamples —— 全部即时 0 采样（含首次落地瞬态，观测用）
            case "counters.clientDarkLightProbeSamples" ->
                    io.github.limuqy.mc.hassium.client.ClientChunkHandler.darkLightProbeSampleCount();
            case "counters.clientDarkLightProbeChunks" ->
                    io.github.limuqy.mc.hassium.client.ClientChunkHandler.darkLightProbeChunkCount();
            case "counters.clientDarkRegressionChunks" ->
                    io.github.limuqy.mc.hassium.client.ClientChunkHandler.darkRegressionChunkCount();
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
            case "stats.landedTotal" -> m.getLandedTotalCount();
            case "stats.sectionDeltaRequestsSent" -> m.getSectionDeltaRequestsSent();
            case "stats.sectionDeltaChunksReceived" -> m.getSectionDeltaChunksReceived();
            case "stats.lightCacheHitCount" -> m.getLightCacheHitCount();
            case "stats.lightCacheHitBytes" -> m.getLightCacheHitBytes();
            case "stats.lightReuseShadowCount" -> m.getLightReuseShadowCount();
            case "stats.lightReuseShadowBytes" -> m.getLightReuseShadowBytes();
            case "stats.lightCacheMissCount" -> m.getLightCacheMissCount();
            case "stats.lightCacheMissBytes" -> m.getLightCacheMissBytes();
            case "stats.noModReceiveBytes" -> m.getNoModReceiveBytes();
            case "clientCache.loadedChunks" -> {
                long n = SmokeProbeWriter.currentLoadedChunkCount(mc);
                yield n < 0L ? 0L : n;
            }
            case "contentCheck.sampledChunks" ->
                    SmokeProbeWriter.sampleContentCheck(mc).sampledChunks();
            case "contentCheck.chunksWithNonAir" ->
                    SmokeProbeWriter.sampleContentCheck(mc).chunksWithNonAir();
            case "contentCheck.playerChunkNonAir" -> {
                long n = SmokeProbeWriter.sampleContentCheck(mc).playerChunkNonAir();
                yield n < 0L ? 0L : n;
            }
            case "contentCheck.maxNonAir" ->
                    SmokeProbeWriter.sampleContentCheck(mc).maxNonAir();
            case "chunkTrace.shadowInjected" -> (long) trace.shadowInjected().size();
            case "chunkTrace.clientApplied" -> (long) trace.clientApplied().size();
            case "chunkTrace.meshCompiled" -> (long) trace.meshCompiled().size();
            // modCompat.*（appendModCompat 同名；布尔以 0/1 暴露，便于沿用数值断言口径）
            case "modCompat.c2meHookHits" ->
                    io.github.limuqy.mc.hassium.compat.mods.ModCompatStats.payloadStreams();
            case "modCompat.type126Patched" ->
                    io.github.limuqy.mc.hassium.compat.mods.ModCompatStats.type126Patched();
            case "modCompat.c2meChunkIoReplaced" ->
                    io.github.limuqy.mc.hassium.compat.mods.ModCompatFlags.c2meChunkIoReplaced() ? 1L : 0L;
            case "modCompat.c2meCompatArmed" ->
                    io.github.limuqy.mc.hassium.compat.mods.ModCompatStats.compatArmed() > 0 ? 1L : 0L;
            case "modCompat.foreignLightEngineActive" -> foreignLightEngineActive(mc) ? 1L : 0L;
            default -> null;
        };
    }

    /** 客户端 level 的光照引擎是否已被 Starlight / ScalableLux 替换（0/1 数值口径）。 */
    private static boolean foreignLightEngineActive(Minecraft mc) {
        try {
            return mc != null && mc.level != null
                    && io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                            .isForeign(mc.level.getLightEngine());
        } catch (Throwable ignored) {
            return false;
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
        long saveSeq = io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get() == null
                ? Long.MIN_VALUE : io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer.saveAllSeq();
        triggerDisconnect(mc);
        LOGGER.info("HassiumSmokeTest: ROUND2 exit scheduling code={} saveSeq={}",
                allPass ? 0 : 2, saveSeq);
        scheduleExit(allPass ? 0 : 2, saveSeq);
        finished = true;
        return Outcome.DONE;
    }


    /**
     * T6 实体冒烟增强（dev 测试代码）：等待影子端断连保存完成。
     * 完成信号 = {@link io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer#saveAllSeq()}
     * 递增。须在 {@code triggerDisconnect} 之前采样序号：park 的 saveAll 常在数毫秒内结束，
     * 若事后再看 heat.idx mtime，文件可能已写完 → 空等到 15s 超时，R2 退出像卡死。
     */
    private static void awaitShadowSaveComplete(long saveSeqBeforeDisconnect) {
        try {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                if (io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer.saveAllSeq()
                        > saveSeqBeforeDisconnect) {
                    LOGGER.info("HassiumSmokeTest: shadow save completed (seq {} -> {})",
                            saveSeqBeforeDisconnect,
                            io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer.saveAllSeq());
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

    /**
     * 优雅退出宽限：把 {@code Minecraft.stop()} 投到主线程后等这么久，仍未退出才强退。
     * <p>
     * 原值 2s 会与**进行中的**拆除并发：断连路径上 `ClientLifecycleHelper.finalizeDisconnect()`
     * （`awaitShutdownComplete(10s)` + `HassiumTaskExecutor.shutdownClient(5s)`）占着主线程时，
     * 排队的 `stop()` 还没轮到执行，2s 后 `System.exit(0)` 就在半拆状态下落下 → JVM 以 native
     * `0xC0000409` 收场（无 hs_err / 无 crash-report）。
     * 实证（2026-09-20，1.21.1 fabric classic ×7）：走强退的场次全部 `native=0xC0000409` 且日志
     * **没有** `Stopping!`；走完优雅退出的场次全部 `native` 为空且有 `Stopping!`——7/7 一致。
     * 加大宽限只影响「本来就走不完优雅退出」的场次：正常场次在 sleep 期间就自行退出，不会走到强退。
     */
    // Full modpacks can still be saving or unloading resources after the default
    // grace period. Let the diagnostic harness wait longer without relaxing its
    // exit-code gate or changing the production smoke-entrypoint guard.
    private static final long GRACEFUL_STOP_GRACE_MS = Math.max(10_000L, Math.min(120_000L,
            Long.getLong("hassium.smokeTest.gracefulStopGraceMs", 10_000L)));


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
                Thread.sleep(GRACEFUL_STOP_GRACE_MS);
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
     * 校验客户端统计摘要的结构与数值一致性（口径与 HassiumCommandHandler 显示完全同源）。
     * 仍要求真实区块已接收并落地；ROUND2 另要求 G1：{@code ovdLoaded > 0}（影子双窗 OVD
     * 本地源回填生效）。不要求 ROUND2 缓存全命中。
     */
    static boolean validateStats(String plain, String roundLabel) {
        return validateStats(plain, roundLabel, true);
    }

    private static boolean validateStats(String plain, String roundLabel, boolean coldCache) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        if (!(plain.contains("Hassium")
                && plain.contains("客户端统计")
                && plain.contains("带宽压缩")
                && plain.contains("区块缓存")
                && plain.contains("区块加载")
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
        long resident = io.github.limuqy.mc.hassium.client.SmokeProbeWriter
                .currentLoadedChunkCount(Minecraft.getInstance());
        if (resident <= 0) {
            LOGGER.error("{} {} stats validation FAILED: ClientChunkCache has no resident chunks (applied={})",
                    MARKER_FAIL, roundLabel, applied);
            return false;
        }
        // contentCheck 仅观测：空岛 / 空置域破基岩 / 末地虚空等合法全空气柱，
        // 不得作为全局硬门禁。需要非空地形的场景在 .scenario 里显式 assertProbe。
        var content = io.github.limuqy.mc.hassium.client.SmokeProbeWriter
                .sampleContentCheck(Minecraft.getInstance());
        LOGGER.info("HassiumSmokeTest: contentCheck {} sampled={} withNonAir={} "
                        + "playerChunkNonAir={} foot={}",
                roundLabel, content.sampledChunks(), content.chunksWithNonAir(),
                content.playerChunkNonAir(), content.footBlock());

        // G1：classic ROUND2 超视渲染 —— 仅在 OVD 配置开启时要求 ovdLoaded>0；
        // 阶段 A 冻结 OVD（viewDistanceExtensionEnabled=false）时该门禁跳过。
        if ("ROUND2".equals(roundLabel) && m.getOvdLoadedCount() <= 0) {
            boolean ovdEnabled = false;
            try {
                ovdEnabled = io.github.limuqy.mc.hassium.config.HassiumConfigService
                        .getInstance().isViewDistanceExtensionEnabled();
            } catch (Throwable ignored) {
                ovdEnabled = true; // 配置不可读：保持旧语义（要求 OVD 有装载）
            }
            int clientVD = io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.effectiveClientVD();
            int serverVD = io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.serverViewDistance();
            if (ovdEnabled && (serverVD <= 0 || clientVD > serverVD)) {
                LOGGER.error("{} {} stats validation FAILED: G1 ovdLoaded==0 (shadow dual-window OVD)",
                        MARKER_FAIL, roundLabel);
                return false;
            }
            LOGGER.info("HassiumSmokeTest: {} G1 ovdLoaded==0 skipped (OVD disabled or delivery entirely within server view)",
                    roundLabel);
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
                    || !countsNear(recompute, m.getLightRecomputeEffectiveCount())) {
                LOGGER.error("{} {} stats validation FAILED: light formula mismatch " +
                                "displayed={}/{} recompute={}, expected={}/{} recompute={}",
                        MARKER_FAIL, roundLabel,
                        displayedRate, displayedHit, recompute,
                        String.format(java.util.Locale.ROOT, "%.1f", expectedRate),
                        expectedHit, m.getLightRecomputeEffectiveCount());
                ok = false;
            }
        }


        // 4) R1 语义门禁：清缓存首进不得出现「网络全量=0 且缓存全命中>0」的假命中。
        //    （来源被 publishCachedChunk 改写 / origin 被日志开关 strip 成 null 的回归锚）
        //    SeedGen 本地生成是合法的 R1 路径：fullReq=0 + locallyGenerated>0 不算假缓存。
        if (coldCache && roundLabel != null && roundLabel.contains("ROUND1")) {
            long fullReq = m.getFullChunkRequestCount();
            long fullHit = m.getCacheHitFullChunkCount();
            long localGen = m.getLocallyGeneratedChunkCount();
            if (fullReq == 0 && fullHit > 64 && localGen == 0) {
                LOGGER.error("{} {} stats validation FAILED: R1 has no network full load " +
                                "but cacheHitFull={} (origin misclassified as cache)",
                        MARKER_FAIL, roundLabel, fullHit);
                ok = false;
            }
        }

        // 5) 区块加载行：结构须含「本地命中」，不再要求「过期」。
        if (!plain.contains("本地命中")) {
            LOGGER.error("{} {} stats validation FAILED: load line missing 本地命中", MARKER_FAIL, roundLabel);
            ok = false;
        }

        if (ok) {
            LOGGER.info("HassiumSmokeTest: stats OK applied={} cacheRate={}% fullReq={} cacheHit={} trafficRatio={}%",
                    applied,
                    String.format(java.util.Locale.ROOT, "%.1f", m.getEffectiveCacheHitRate() * 100.0),
                    m.getFullChunkRequestCount(),
                    m.getCacheHitFullChunkCount(),
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
