package io.github.limuqy.mc.hassium.concurrent;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Predicate;

/**
 * 主线程回调调度器
 * <p>
 * 后台线程通过 {@link #execute(Runnable)} 提交回调，
 * 主线程在 {@link #flushClient()} 中按优先级批量执行。
 * <p>
 * 优先级由 {@link ChunkDistancePriority} 在入队瞬间冻结（数值越小越优先），
 * 与 {@code ClientCacheLoadQueue} 一致；玩家移动不改写已入队 key。
 * 层序恒为：权威 &gt; 未知任务（无锚点） &gt; 环带（renderOnly）。
 * <p>
 * 在客户端由 MixinClientTick 每帧调用 flushClient()，
 * 在服务端由 MinecraftServer tick 调用 flushServer()。
 */
public final class MainThreadDispatcher {

    /** 默认单帧最大回调数 */
    static final int DEFAULT_MAX_CALLBACKS_PER_FRAME = 5;

    /**
     * 无 chunk 锚点的全局任务优先级（{@link ChunkDistancePriority.Tier#UNKNOWN}）。
     * 层序：权威 &lt; 未知任务 &lt; 环带（数值越小越优先）。
     */
    public static final double PRIORITY_UNKNOWN = ChunkDistancePriority.unknown();

    /**
     * @deprecated 语义已变为「未知层」而非绝对垫底；请用 {@link #PRIORITY_UNKNOWN}。
     *             保留别名避免外部/历史调用编译失败。
     */
    @Deprecated
    public static final double PRIORITY_LOWEST = PRIORITY_UNKNOWN;

    private static final PriorityBlockingQueue<CallbackTask> CLIENT_QUEUE =
            new PriorityBlockingQueue<>(64);

    private static final PriorityBlockingQueue<CallbackTask> SERVER_QUEUE =
            new PriorityBlockingQueue<>(64);

    /** 玩家原始坐标缓存（login / tick / 收包路径写入） */
    private static volatile double hassium$playerX = 0.0;
    private static volatile double hassium$playerZ = 0.0;
    private static volatile boolean hassium$playerPosKnown = false;

    private MainThreadDispatcher() {}

    // ============== 玩家位置更新 ==============

    /**
     * 更新玩家坐标（由 MixinClientTick 每帧 / login 路径调用），用于距离优先级计算
     */
    public static void updatePlayerPosition(double x, double z) {
        hassium$playerX = x;
        hassium$playerZ = z;
        hassium$playerPosKnown = true;
    }

    public static void updatePlayerPosition() {
        // 首波 payload 可能在首个 client tick 前到达：此处刷新玩家坐标供距离优先级使用
        // 单测 / 无客户端环境时 getInstance() 可能为 null
        Minecraft mcEarly = Minecraft.getInstance();
        if (mcEarly != null && mcEarly.player != null) {
            MainThreadDispatcher.updatePlayerPosition(mcEarly.player.getX(), mcEarly.player.getZ());
        }
    }

    /**
     * 清除玩家坐标缓存（断连时调用），避免下次进服用旧坐标。
     */
    public static void clearPlayerPosition() {
        hassium$playerX = 0.0;
        hassium$playerZ = 0.0;
        hassium$playerPosKnown = false;
    }

    /**
     * 玩家坐标缓存是否已写入（login / tick / 收包路径）。
     */
    public static boolean isPlayerPositionKnown() {
        return hassium$playerPosKnown;
    }

    /**
     * 权威层优先级。坐标未知时仍为 {@link ChunkDistancePriority.Tier#AUTHORITATIVE} 层
     *（{@code base+0}，不伪装原点），保证<strong>权威始终先于未知任务与环带</strong>。
     */
    public static double authoritativePriority(ChunkPos chunkPos) {
        return priorityOf(ChunkDistancePriority.Tier.AUTHORITATIVE, chunkPos);
    }

    /**
     * renderOnly 层优先级。坐标未知时仍为 {@link ChunkDistancePriority.Tier#RENDER_ONLY} 层
     *（{@code base+0}），保证<strong>环带始终晚于权威与未知任务</strong>。
     */
    public static double renderOnlyPriority(ChunkPos chunkPos) {
        return priorityOf(ChunkDistancePriority.Tier.RENDER_ONLY, chunkPos);
    }

    private static double priorityOf(ChunkDistancePriority.Tier tier, ChunkPos chunkPos) {
        if (chunkPos == null || tier == null) {
            // 无锚点：UNKNOWN 层（夹在权威与环带之间），勿用绝对 MAX
            return ChunkDistancePriority.unknown();
        }
        if (!hassium$playerPosKnown) {
            updatePlayerPosition();
        }
        if (!hassium$playerPosKnown) {
            // 保留所属层，仅无距离分量 → 权威 > 未知任务 > 环带 的层序不破
            return ChunkDistancePriority.ofUnknownDistance(tier);
        }
        return ChunkDistancePriority.ofWorld(tier, chunkPos, hassium$playerX, hassium$playerZ);
    }

    // ============== 提交 API ==============

    /**
     * 提交回调到客户端主线程（可在任意线程调用）
     * <p>
     * 默认 {@link #PRIORITY_UNKNOWN}（无锚点），类别 = MISSION_CRITICAL。
     */
    public static void execute(Runnable task) {
        execute(task, PRIORITY_UNKNOWN, TaskCategory.MISSION_CRITICAL);
    }

    /**
     * 提交带 chunk 位置的回调（可在任意线程调用）
     * <p>
     * 基于玩家坐标自动计算距离优先级。
     * 类别 = SAFE_TO_CANCEL（默认，连接断开时取消）。
     */
    public static void execute(Runnable task, ChunkPos chunkPos) {
        execute(task, calcDistancePriority(chunkPos), TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 提交带 chunk 位置和类别的回调
     */
    public static void execute(Runnable task, ChunkPos chunkPos, TaskCategory category) {
        execute(task, calcDistancePriority(chunkPos), category);
    }

    /**
     * 提交带指定优先级和类别的回调（可在任意线程调用）
     *
     * @param task     回调任务
     * @param priority 优先级（越小越优先）
     * @param category 任务类别
     */
    public static void execute(Runnable task, double priority, TaskCategory category) {
        DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Adding callback to queue (priority={}, category={}, queueSize={})",
                priority, category, CLIENT_QUEUE.size());
        CLIENT_QUEUE.offer(new CallbackTask(task, priority, category));
        DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Callback added, new queueSize={}", CLIENT_QUEUE.size());
    }

    /**
     * 提交回调到服务端主线程（可在任意线程调用）
     */
    public static void executeOnServer(Runnable task) {
        SERVER_QUEUE.offer(new CallbackTask(task, PRIORITY_UNKNOWN, TaskCategory.MISSION_CRITICAL));
    }

    // ============== 刷新（主线程调用） ==============

    /**
     * 刷新客户端主线程回调队列（每帧调用）
     * <p>
     * 按优先级顺序出队并执行，默认最多执行 DEFAULT_MAX_CALLBACKS_PER_FRAME 个。
     */
    public static void flushClient() {
        flushClient(DEFAULT_MAX_CALLBACKS_PER_FRAME);
    }

    /**
     * 刷新客户端主线程回调队列（每帧调用，按数量硬顶）。
     *
     * @param maxPerFrame 单帧最多执行的回调数
     */
    public static void flushClient(int maxPerFrame) {
        long deadlineNs = System.nanoTime() + 50_000_000L; // 兼容旧调用：给足预算，仍受 hardCap 约束
        flushClientUntil(deadlineNs, maxPerFrame);
    }

    /**
     * 在共享时间预算内刷新客户端回调队列。
     *
     * @param deadlineNs  本帧截止时间（{@link System#nanoTime()}）
     * @param hardCap     安全硬顶（最多回调数）
     * @return 实际处理的回调数
     */
    public static int flushClientUntil(long deadlineNs, int hardCap) {
        if (CLIENT_QUEUE.isEmpty()) {
            return 0;
        }
        int maxPerFrame = Math.max(1, hardCap);
        DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Flushing client queue (queueSize={}, hardCap={})",
                CLIENT_QUEUE.size(), maxPerFrame);

        int processed = 0;
        boolean forceOne = true;
        CallbackTask task;
        while (processed < maxPerFrame && (task = CLIENT_QUEUE.poll()) != null) {
            long now = System.nanoTime();
            if (!forceOne && now >= deadlineNs) {
                // 预算用尽：把任务放回队列头部语义由优先级队列保证，重新 offer
                CLIENT_QUEUE.offer(task);
                break;
            }
            forceOne = false;
            try {
                DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Executing callback (priority={}, category={})",
                        task.priority(), task.category());
                task.action.run();
                processed++;
                DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Callback executed successfully");
            } catch (Exception e) {
                DebugLogger.error("[MAIN_DISPATCHER] Client task failed", e);
            }
        }
        DebugLogger.info(LogType.DISPATCHER, "[MAIN_DISPATCHER] Flushed {} callbacks, remaining={}",
                processed, CLIENT_QUEUE.size());
        return processed;
    }

    /**
     * 刷新服务端主线程回调队列（每 tick 调用）
     */
    public static void flushServer() {
        CallbackTask task;
        while ((task = SERVER_QUEUE.poll()) != null) {
            try {
                task.action.run();
            } catch (Exception e) {
                Constants.LOG.error("Hassium: MainThreadDispatcher server task failed", e);
            }
        }
    }

    // ============== 清空 / 取消 ==============

    /**
     * 清空客户端队列（断开连接时调用）
     * <p>
     * 等同于 clearClient(false)，清空所有任务。
     */
    public static void clearClient() {
        clearClient(false);
    }

    /**
     * 清空客户端队列
     *
     * @param keepMissionCritical true 时保留 MISSION_CRITICAL 类别的任务
     */
    public static void clearClient(boolean keepMissionCritical) {
        if (keepMissionCritical) {
            CLIENT_QUEUE.removeIf(task -> task.category != TaskCategory.MISSION_CRITICAL);
        } else {
            CLIENT_QUEUE.clear();
        }
    }

    /**
     * 清空服务端队列（服务器关闭时调用）
     */
    public static void clearServer() {
        SERVER_QUEUE.clear();
    }

    /**
     * 按条件取消尚未执行的特定任务
     *
     * @param predicate 判断哪些任务应被取消
     * @return 取消的任务数量
     */
    public static int cancelTasks(Predicate<CallbackTask> predicate) {
        int removed = 0;
        CallbackTask task;
        while ((task = CLIENT_QUEUE.poll()) != null) {
            if (!predicate.test(task)) {
                CLIENT_QUEUE.offer(task); // 放回
            } else {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 取消指定类别的所有待处理回调
     */
    public static int cancelByCategory(TaskCategory category) {
        return cancelTasks(task -> task.category == category);
    }

    // ============== 状态 ==============

    /**
     * 获取客户端队列大小
     */
    public static int getClientQueueSize() {
        return CLIENT_QUEUE.size();
    }

    /**
     * 获取服务端队列大小
     */
    public static int getServerQueueSize() {
        return SERVER_QUEUE.size();
    }

    // ============== 内部 ==============

    /**
     * 计算 chunk 优先级键并在入队时冻结（权威层）。
     * <p>
     * 依赖 {@link #updatePlayerPosition} 缓存（login / 每 tick / 收包路径写入）。
     * 坐标未知时仍为权威层 {@code base+0}（不伪装 (0,0)），
     * 层序恒为：权威 &gt; 未知任务 &gt; 环带。
     */
    private static double calcDistancePriority(ChunkPos chunkPos) {
        return authoritativePriority(chunkPos);
    }

    /**
     * 回调任务，按优先级排序
     */
    public record CallbackTask(Runnable action, double priority, TaskCategory category)
            implements Comparable<CallbackTask> {
        @Override
        public int compareTo(CallbackTask other) {
            return Double.compare(this.priority, other.priority);
        }
    }
}
