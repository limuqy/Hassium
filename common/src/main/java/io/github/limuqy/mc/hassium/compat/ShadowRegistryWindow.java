package io.github.limuqy.mc.hassium.compat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
#if MC_VER < MC_1_21_1

/**
 * 影子端「注册表重建窗口」互斥（仅 {@code MC_VER < MC_1_21_1} 编入）——**不用锁**。
 * <p>
 * 背景（原 {@code ShadowRegistryGate} 读写锁）：1.20.1 forge/neoforge 所有客户端断连路径
 * 汇聚到 {@code Minecraft.clearLevel(Screen)}（Render 线程），forge patch 在其中注入
 * {@code ForgeHooksClient.handleClientLevelClosing} → 非内存连接必调
 * {@code GameData.revertToFrozen()} → {@code loadRegistry(FROZEN→ACTIVE)} 清空重灌
 * ACTIVE 注册表的 ids/names/keys BiMap（{@code NamespacedWrapper.getResourceKey} 直接委托）；
 * 而影子端 {@code ChunkSerializer.write/read} 经 {@code BlockState.CODEC} 查同一份 BiMap，
 * 撞进重建窗口即报 {@code Unknown registry element} / 调色板项被静默换成 air。
 * <p>
 * <b>为什么改成窗口而不是读写锁</b>：读写锁让「影子序列化线程」与「clearLevel 写方」互相阻塞，
 * 与 {@code ShadowStorageManager.flushLock} / {@code ShadowLightCompute.chunkLock} 构成多锁偏序，
 * 实测两次 ABBA：{@code ShadowPoiGate/chunkLock}（872c9628）与
 * {@code flushLock/注册表写锁}——后者表现为断连时 Render 线程持写锁等 saver、
 * saver 等 flushLock、在途 flush 持 flushLock 等读锁，**退出卡满 10s 等待超时**。
 * <p>
 * 窗口把互斥降级为「单向握手 + 跳过」，不阻塞访问方、不参与任何锁序：
 * <ul>
 *   <li><b>开窗</b>（Render 线程，{@code clearLevel} HEAD）：置位后**有界等**在途访问退出；
 *       等待期间不持有任何其它锁 ⇒ 与任何锁都不构成环。</li>
 *   <li><b>窗口内新访问</b>：直接跳过（返回 {@code null}），不阻塞、不入队。
 *       序列化跳过 ⇒ 柱保持脏（{@code writeBatch} 还原脏位，TAIL 主线程保存时补编）；
 *       解码跳过 ⇒ 本次 cache miss，调用方走原有 null 兜底。</li>
 *   <li><b>关窗</b>（{@code clearLevel} TAIL）：放行；随后主线程同步跑影子端保存
 *       （见 {@code ClientLifecycleHelper.saveShadowOnDisconnect}）。</li>
 * </ul>
 * 握手正确性：访问方「查位 → 计数++ → 再查位」，开窗方「置位 → 等计数归零」。
 * 访问方两次查位皆假才真正执行 ⇒ 开窗方等计数时不会漏掉已进入的访问；
 * 访问方第二次查位为真则放弃执行 ⇒ 窗口内不会有访问真正触碰注册表。
 * <p>
 * Fabric 无 {@code revertToFrozen}：{@link #shouldGuard()} 为 false 时 mixin 不开窗，
 * 零开销（原语义保留）。
 */
public final class ShadowRegistryWindow {

    private ShadowRegistryWindow() {}

    /** 开窗时等在途访问退出的上界（毫秒）。正常在途访问是单柱编解码，微秒~毫秒级。 */
    public static final long DRAIN_TIMEOUT_MS = 2_000L;

    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /**
     * 影子端注册表访问入口：窗口内直接跳过（返回 {@code null}），否则登记在途并执行。
     * 非阻塞——这是本类替代读写锁的关键：访问方永远不会为了等窗口而挂住。
     */
    public static <T> T withAccess(java.util.function.Supplier<T> action) {
        if (ACTIVE.get()) {
            return null;
        }
        IN_FLIGHT.incrementAndGet();
        try {
            if (ACTIVE.get()) {
                return null;
            }
            return action.get();
        } finally {
            IN_FLIGHT.decrementAndGet();
        }
    }

    /**
     * 开窗（Render 线程 {@code clearLevel} HEAD）：置位后有界等在途注册表访问退出。
     *
     * @return true = 已排空（窗口内不再有在途访问）；false = 超时未排空
     *         （调用方记 warn；仍保持开窗——宁可概率性撞一次，也不把退出路径挂死）
     */
    public static boolean open(long timeoutMs) {
        ACTIVE.set(true);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (IN_FLIGHT.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(200_000L);
        }
        return true;
    }

    /** 关窗（{@code clearLevel} TAIL / 保存前）。幂等。 */
    public static void close() {
        ACTIVE.set(false);
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** 当前在途注册表访问数（诊断 / 测试）。 */
    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Fabric 无注册表重建窗口，clearLevel 不必开窗。
     * 未加载 platform（单测）时保守返回 true。
     */
    public static boolean shouldGuard() {
        try {
            return !"Fabric".equalsIgnoreCase(
                    io.github.limuqy.mc.hassium.platform.Services.PLATFORM.getPlatformName());
        } catch (Throwable ignored) {
            return true;
        }
    }
}
#endif
