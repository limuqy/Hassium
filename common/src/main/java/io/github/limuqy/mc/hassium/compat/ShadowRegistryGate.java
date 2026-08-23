package io.github.limuqy.mc.hassium.compat;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
#if MC_VER < MC_1_21_1

/**
 * 影子端序列化 vs forge 注册表重建的结构性互斥门（仅 {@code MC_VER < MC_1_21_1} 编入）。
 * <p>
 * 根因链（代码级定位）：1.20.1 forge/neoforge 所有客户端断连路径汇聚到
 * {@code Minecraft.clearLevel(Screen)}（Render 线程），forge patch 在其中注入
 * {@code ForgeHooksClient.handleClientLevelClosing} → 非内存连接必调
 * {@code GameData.revertToFrozen()} → {@code loadRegistry(FROZEN→ACTIVE)} 清空重灌
 * ACTIVE 注册表的 ids/names/keys BiMap（{@code NamespacedWrapper.getResourceKey} 直接委托）。
 * 而 Hassium 断连清理派发 park 线程异步 {@code ShadowSeedServer.saveAll}，其
 * {@code ChunkSerializer.write} 经 {@code BlockState.CODEC}（byNameCodec 编码方向）
 * 查该 BiMap —— 与 revert 结构性并发，撞进 clear 窗口即报
 * {@code Unknown registry element}。
 * <p>
 * 门协议：
 * <ul>
 *   <li><b>写锁</b>：{@code clearLevel(Screen)} HEAD 获取、TAIL 释放（Render 线程），
 *       覆盖 vanilla 世界拆除 + {@code handleClientLevelClosing}（revertToFrozen）全程。</li>
 *   <li><b>读锁</b>：影子端 {@code ChunkSerializer.write} / {@code ChunkSerializer.read}
 *       全程持有（park/save 线程）。</li>
 * </ul>
 * 由此获得结构保证而非概率探测：write/read 要么整体在 revert 前完成（读锁先获，
 * 写锁等待），要么整体在 revert 后进行（写锁先获，读锁等待）；编码永不落在
 * BiMap 清空窗口内，vanilla ERROR 行不再出现。fabric 无重建机制，读写锁无竞争
 * （写侧仅在 forge patch 存在时获取，见 MixinMinecraft 平台守卫），零开销直通。
 * <p>
 * 死锁安全：saveAll 序列化路径不依赖 Render 线程（flush 回调同步执行，
 * 无主线程泵），读锁持有期间不会等待写锁方；写锁方为 Render 线程自身。
 */
public final class ShadowRegistryGate {

    private ShadowRegistryGate() {}

    private static final ReadWriteLock GATE = new ReentrantReadWriteLock();

    /** 影子端序列化/反序列化入口：注册表访问全程持读锁。 */
    public static <T> T withReadAccess(java.util.function.Supplier<T> action) {
        GATE.readLock().lock();
        try {
            return action.get();
        } finally {
            GATE.readLock().unlock();
        }
    }

    /**
     * 写锁原语（mixin HEAD/TAIL 成对使用）：{@code clearLevel(Screen)} HEAD 获取、
     * TAIL 释放，覆盖世界拆除 + {@code handleClientLevelClosing}（revertToFrozen）全程。
     * 同线程重入安全（ReentrantReadWriteLock 写锁可重入）。
     */
    public static void acquireWrite() {
        GATE.writeLock().lock();
    }

    /** 写锁释放原语（与 {@link #acquireWrite()} 成对；须同线程）。 */
    public static void releaseWrite() {
        GATE.writeLock().unlock();
    }
}
#endif
