package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;

/**
 * 影子端「碰原版 POI / SectionStorage 状态」的串行化入口——**转影子主循环 dispatch，不用锁**。
 * <p>
 * <b>为什么需要</b>：原版把 chunk 反序列化放在**单线程** {@code mainThreadExecutor} 上
 * （1.21.1 mojmap {@code ChunkMap.scheduleChunkLoad}：{@code thenApplyAsync(..., this.mainThreadExecutor)}），
 * 因为这条链上的结构**全都没有同步**：{@code SectionStorage.storage}（裸 {@code Long2ObjectOpenHashMap}）、
 * {@code SectionStorage.dirty}（{@code LongLinkedOpenHashSet}）、
 * {@code PoiManager.DistanceTracker.levels}（{@code Long2ByteOpenHashMap}）、{@code PoiSection.records}（普通 {@code Set}），
 * 且 {@code SectionStorage.getOrLoad/getOrCreate/readColumn} 与 {@code PoiManager.checkConsistencyWithBlocks}
 * 都不是 {@code synchronized}。
 * <p>
 * <b>为什么不再是锁</b>：影子端把这些访问放到池线程（{@code ShadowSeedServer.DISK_READ_PERMITS} 2~8 并发），
 * 于是要用一把 {@code ReentrantLock} 把「池线程解码」与「影子主循环 tick」串起来。但锁一旦与
 * {@code ShadowLightCompute.chunkLock}、{@code ShadowStorageManager.flushLock} 共存就产生锁序，
 * 实测两次 ABBA：
 * <ul>
 *   <li>872c9628：flush 持 {@code chunkLock} 等 POI 闸，主循环持 POI 闸等 {@code chunkLock}；</li>
 *   <li>断连时：Render 持注册表写锁等 saver，saver 等 {@code flushLock}，在途 flush 持
 *       {@code flushLock} 等 POI/注册表闸 —— 退出卡满 10s 等待超时。</li>
 * </ul>
 * 改为**调度**：把解码/序列化提交到影子主循环线程执行并同步等待（{@link ShadowSeedServer#runOnMainLoopAndWait}），
 * 与主循环 tick 天然同线程 ⇒ 结构性串行，无锁、无锁序、不可能 ABBA。
 * <p>
 * <b>影响面严格限定影子端</b>：{@link RuntimeServerContext#isShadowServerContext()} 为假
 * （专用服 / 集成服真实服务端 / 未装配影子端 / 单测）时**直接执行**，与改动前行为完全一致——
 * 专用服不装配 {@code ShadowSeedServer}，原版自身就是单线程 mainThreadExecutor 解码，
 * 不需要也不得引入任何调度。
 */
public final class ShadowPoiGate {

    private ShadowPoiGate() {}

    /**
     * 串行执行（解码 / 序列化 / saveAll）：影子端上下文内调度到影子主循环线程执行并同步等待。
     * <p>
     * 非影子端上下文 ⇒ 直接执行（零行为变化）。
     */
    public static <T> T callExclusive(java.util.function.Supplier<T> action) {
        ShadowSeedServer shadow = shadowServer();
        if (shadow == null) {
            return action.get();
        }
        return shadow.runOnMainLoopAndWait(action);
    }

    /** 串行执行（无返回值）。 */
    public static void runExclusive(Runnable action) {
        callExclusive(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 主循环 tick 入口：调用方本就在影子主循环线程，且解码/序列化也被调度到同一线程
     * ⇒ 天然串行，直接执行。
     * <p>
     * 原实现用 {@code tryLock}「拿不到闸就跳过本拍」，是为了让主循环**不被解码阻塞**；
     * 转主循环后阻塞关系消失（两者同线程），跳过语义不再需要。保留方法签名以维持调用点稳定。
     *
     * @return 恒为 true（执行了）
     */
    public static boolean runIfIdle(Runnable action) {
        action.run();
        return true;
    }

    /** 影子端实例；非影子端上下文返回 {@code null}（调用方据此走零影响路径）。 */
    private static ShadowSeedServer shadowServer() {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        return ShadowServerRegistry.getInstance().get();
    }
}
