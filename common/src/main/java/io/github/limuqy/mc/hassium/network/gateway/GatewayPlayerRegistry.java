package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 玩家会话注册表（UUID-keyed，T3 事实表：per-player 状态进程内存，removePlayer 一键清理）。
 *
 * <p>登记/移除是帧连接 ↔ 玩家身份的映射：{@link GatewayChannel} 在续流握手
 * （票据 UUID）或登录桥附着时注册；移除时级联清理 {@link ServerChunkPushManager}
 * per-player 表（T7 续流标记/位置上报/队列等，{@code removePlayer} 一键清空）
 * 与平台侧 per-player 清理（{@link #setPlayerRemovalHook}，如
 * {@code PlayerCompressionTracker.removePlayer}）。
 *
 * <p>线程安全：任意线程（event loop / 平台登录桥 / 断连处理）。
 */
public final class GatewayPlayerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayPlayerRegistry");

    private final ConcurrentHashMap<UUID, GatewayPlayerSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<UUID>> removalHooks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<GatewayPlayerSession>> attachHooks = new CopyOnWriteArrayList<>();

    /** 登记玩家会话（重复注册同 UUID：直接覆盖并关闭旧通道；旧会话清理链由通道断连路径完成）。 */
    public GatewayPlayerSession register(GatewayPlayerSession session) {
        if (session == null) {
            return null;
        }
        GatewayPlayerSession prev = sessions.put(session.playerId(), session);
        if (prev != null && prev != session) {
            LOGGER.warn("[GATEWAY] Session overwrite for {} (old channel {})", session.playerId(), prev.channel().remote());
            prev.channel().close("session overwritten"); // review-fix: 旧通道走完整清理链（channelInactive → GatewayChannel.close）
        }
        LOGGER.info("[GATEWAY] Player session registered: {} (resume={}{}, channel={}, players={})",
                session.playerId(), session.resume(),
                session.resume() ? ", epoch=" + session.resumeEpoch() : "",
                session.channel().remote(), sessions.size());
        // T12：会话登记钩子（续流会话物化调度；平台接线在 GatewayPlatformWiring 注册）
        for (Consumer<GatewayPlayerSession> hook : attachHooks) {
            try {
                hook.accept(session);
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] attach hook failed for {}", session.playerId(), t);
            }
        }
        return session;
    }

    public GatewayPlayerSession get(UUID playerId) {
        return playerId == null ? null : sessions.get(playerId);
    }

    /**
     * 按会话移除并清理（通道断连/会话替换入口）。
     * 身份守卫：仅当当前登记会话就是传入会话时才移除（旧通道关闭不会误删新会话）。
     * 原子实现：computeIfPresent 移除映射时返回 null，故用数组捕获被移除值。
     */
    public GatewayPlayerSession remove(GatewayPlayerSession session) {
        if (session == null) {
            return null;
        }
        GatewayPlayerSession[] removed = new GatewayPlayerSession[1];
        sessions.computeIfPresent(session.playerId(), (id, cur) -> {
            if (cur != session) {
                return cur; // 身份不符：保留现会话
            }
            removed[0] = cur;
            return null;
        });
        return removed[0] != null ? finishRemoval(removed[0]) : null;
    }

    /** 按 UUID 移除并清理（无身份守卫——调用方须确认当前会话归属；通道关闭请用 {@link #remove(GatewayPlayerSession)}）。 */
    public GatewayPlayerSession remove(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        GatewayPlayerSession removed = sessions.remove(playerId);
        return removed != null ? finishRemoval(removed) : null;
    }

    private GatewayPlayerSession finishRemoval(GatewayPlayerSession session) {
        // T3 事实表：removePlayer 一键清空 per-player 表（resume 标记/位置/队列/hash/bloom）
        ServerChunkPushManager.getInstance().removePlayer(session.playerId());
        for (Consumer<UUID> hook : removalHooks) {
            try {
                hook.accept(session.playerId());
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] removal hook failed for {}", session.playerId(), t);
            }
        }
        session.channel().detachPlayer(session);
        LOGGER.info("[GATEWAY] Player session removed: {} (players={})", session.playerId(), sessions.size());
        return session;
    }

    /**
     * 预热会话 TTL 清扫（B3，服务端 tick 泵调用）：移除「无续流完成」且超时的 resume 会话。
     *
     * <p>判定（风险 8 裁决，TTL 起算点 = {@link GatewayPlayerSession#registeredAtMillis()}）：
     * 仅 resume 会话（prewarm/续流握手登记）参与 TTL；「续流完成」= C2S sink 已挂载
     * （物化成功、玩家进入世界）——完成者的生命周期归通道断连清理路径（{@link GatewayChannel#close}），
     * TTL 不清。新会话覆盖旧会话后，旧会话不在本表内（{@link #register} put 覆盖），清扫
     * 天然只针对当前登记会话——续流成功后旧会话停止计时，不误伤正常续流。
     *
     * <p>移除走 {@link #remove(GatewayPlayerSession)}（身份守卫 + finishRemoval 完整清理链：
     * ServerChunkPushManager.removePlayer + removal hook + detachPlayer，不另起清理逻辑）。
     *
     * @param nowMs 当前时刻（tick 泵传 System.currentTimeMillis()；测试可注入模拟到期）
     * @param ttlMs 预热会话 TTL（HassiumConfigService.getMigrationPrewarmTtlMs()，默认 60000）
     * @return 本轮移除的会话数（测试/诊断）
     */
    public int sweepExpired(long nowMs, long ttlMs) {
        if (ttlMs <= 0) {
            return 0; // 配置禁用防御（键 min=1000，正常不可达）
        }
        int swept = 0;
        for (GatewayPlayerSession session : sessions.values()) {
            try {
                if (session == null || !session.resume() || session.c2sSink() != null) {
                    continue; // 非 resume / 续流已完成 → 归断连清理路径
                }
                if (nowMs - session.registeredAtMillis() <= ttlMs) {
                    continue; // 未到期
                }
                if (remove(session) != null) {
                    swept++;
                    LOGGER.info("[GATEWAY] Player session TTL expired: {} (age={}ms, ttl={}ms) — 无续流完成，清理",
                            session.playerId(), nowMs - session.registeredAtMillis(), ttlMs);
                }
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] TTL sweep failed for {}", session.playerId(), t);
            }
        }
        return swept;
    }

    /** 平台侧 per-player 清理钩子（如 PlayerCompressionTracker.removePlayer）；幂等注册。 */
    public void addPlayerRemovalHook(Consumer<UUID> hook) {
        if (hook != null) {
            removalHooks.addIfAbsent(hook);
        }
    }

    /** 会话登记钩子（T12：续流会话 ServerPlayer 物化调度）；幂等注册。 */
    public void addPlayerAttachHook(Consumer<GatewayPlayerSession> hook) {
        if (hook != null) {
            attachHooks.addIfAbsent(hook);
        }
    }

    public int size() {
        return sessions.size();
    }

    public Set<UUID> playerIds() {
        return sessions.keySet();
    }

    /** 清空全部会话（网关停机）；逐个走完整清理路径。 */
    public void clear() {
        for (UUID id : Set.copyOf(sessions.keySet())) {
            remove(id);
        }
    }
}
