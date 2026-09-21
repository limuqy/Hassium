package io.github.limuqy.mc.hassium.compat;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 影子端「碰原版 POI / SectionStorage 状态」的互斥闸。
 * <p>
 * <b>为什么需要</b>：原版把 chunk 反序列化放在**单线程** {@code mainThreadExecutor} 上
 * （1.21.1 mojmap {@code ChunkMap.scheduleChunkLoad}：{@code thenApplyAsync(..., this.mainThreadExecutor)}），
 * 因为这条链上的结构**全都没有同步**：{@code SectionStorage.storage}（裸 {@code Long2ObjectOpenHashMap}）、
 * {@code SectionStorage.dirty}（{@code LongLinkedOpenHashSet}）、
 * {@code PoiManager.DistanceTracker.levels}（{@code Long2ByteOpenHashMap}）、{@code PoiSection.records}（普通 {@code Set}），
 * 且 {@code SectionStorage.getOrLoad/getOrCreate/readColumn} 与 {@code PoiManager.checkConsistencyWithBlocks}
 * 都不是 {@code synchronized}。
 * <p>
 * 影子端把反序列化挪到了自己的池线程（{@code ShadowSeedServer.DISK_READ_PERMITS} 允许 2~8 并发），
 * 于是「池线程解码」与「影子主循环 {@code ServerChunkCache.tick → ChunkMap.tick → poiManager.tick}」
 * 会并发写同一份 POI 状态 ⇒ {@code Long2ObjectOpenHashMap.rehash} 里 {@code ArrayIndexOutOfBoundsException}
 * ⇒ vanilla 打 {@code Failed to load chunk}（2026-09-21 实测，仅热复用场复现）。
 * <p>
 * <b>门协议</b>（只包「我们自己的」入口，不给原版结构打补丁）：
 * <ul>
 *   <li><b>阻塞</b>：解码（{@code ShadowServerCompat.parseChunkNbt}）、序列化（{@code serializeChunk}）、
 *       影子端 {@code saveAll}（其 {@code ChunkMap.save → poiManager.flush} 也写同一份状态）。</li>
 *   <li><b>非阻塞</b>：影子主循环的 chunk tick 用 {@link #runIfIdle} —— 拿不到闸就**跳过本拍**
 *       （tick 本来就有 {@code CHUNK_TICK_INTERVAL_MS} 节拍，跳一拍无害）。
 *       这样主循环**永远不会**被解码阻塞：既避免「解码卡死 → 主循环停摆」把 §8.5 的爆炸半径放大，
 *       也保证 tick 侧一旦拿到闸就没有解码在跑。</li>
 * </ul>
 * 同线程可重入（{@link ReentrantLock}）：主循环 tick 内再触发解码不会自锁。
 */
public final class ShadowPoiGate {

    private ShadowPoiGate() {}

    private static final ReentrantLock GATE = new ReentrantLock();

    /** 阻塞取闸执行（解码 / 序列化 / saveAll）。 */
    public static <T> T callExclusive(java.util.function.Supplier<T> action) {
        GATE.lock();
        try {
            return action.get();
        } finally {
            GATE.unlock();
        }
    }

    /** 阻塞取闸执行（无返回值）。 */
    public static void runExclusive(Runnable action) {
        GATE.lock();
        try {
            action.run();
        } finally {
            GATE.unlock();
        }
    }

    /**
     * 空闲才执行（主循环 tick）：闸被解码占着就跳过本拍。
     *
     * @return true = 执行了；false = 跳过（有解码在跑）
     */
    public static boolean runIfIdle(Runnable action) {
        if (!GATE.tryLock()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            GATE.unlock();
        }
    }
}
