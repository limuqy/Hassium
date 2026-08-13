package io.github.limuqy.mc.hassium.concurrent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 带「同键取代 + 消费侧版本校验 + 消费时重算优先级」的并发优先级队列（通用实现）。
 * <p>
 * <b>背景</b>：普通优先队列的优先级键在入队瞬间冻结。玩家来回移动时，同区块
 * 可能被多次入队，旧任务（a）与新任务重复排队造成重复消费/重复磁盘读；
 * （b）因冻结键过优/过劣在错误时机被消费——旧任务消费晚于新任务时，
 * 「老数据覆盖新数据」；新任务被更近的旧任务挤后时，近处块迟迟不落地。
 * <p>
 * <b>三层能力</b>（各调用点按需参数化）：
 * <ol>
 *   <li><b>入队取代</b>：{@link OfferPolicy#REPLACE} 用新任务替换同 key 旧任务
 *       （旧任务留堆为过期残留、由消费侧 {@link #isCurrent} 丢弃并定期压缩；
 *       新任务带入队时刻的最新优先级）；{@link OfferPolicy#SKIP_IF_PRESENT} 丢弃新任务。</li>
 *   <li><b>消费侧版本校验</b>：{@link Entry#generation()} 单调递增；
 *       {@link #isCurrent(Entry)} 在 poll 与真正执行之间仍可失效——poll 后、执行前
 *       同 key 有新任务入队时旧任务立即过期，调用方据此跳过，杜绝老数据覆盖。</li>
 *   <li><b>消费时重算优先级</b>：{@link #pollBest} 在出队瞬间用调用方提供的
 *       {@link PriorityRefresher}（如按当前玩家位置）重算键；若刷新后不再是队首最优，
 *       以新键重新插入并继续，至多 {@code maxReinserts} 次——冻结键自动追平移动。</li>
 * </ol>
 * <p>
 * <b>key 语义</b>：key = ({@link Key#posLong()}, {@link Key#op()})。op 由调用方定义，
 * 同一 chunk 位置不同语义的任务（全量 apply / BE apply / 网络请求）必须用不同 op，
 * 避免互相取代。key 为 null 时退化为普通优先队列（无取代/无校验）。
 * <p>
 * <b>线程安全</b>：内部 {@link PriorityBlockingQueue} + {@link ConcurrentHashMap}，
 * 生产/消费可任意线程并发。相等优先级保持与 PriorityBlockingQueue 相同的
 * 「近似 FIFO」行为（同键不保证严格 FIFO，新插入元素排在已有同键元素之后）。
 * <p>
 * 相同键的多语义用法示例见 {@link MainThreadDispatcher}（OP_CHUNK_APPLY / OP_REQUEST /
 * OP_BLOCK_ENTITY）。
 */
public final class KeyedPriorityQueue<E> {

    /**
     * 队列键：{@code posLong} = {@code ChunkPos.asLong(x, z)}；{@code op} 区分同位置
     * 不同语义的任务（取值由调用方定义，见 {@link MainThreadDispatcher}）。
     */
    public record Key(long posLong, int op) {}

    /** 入队策略 */
    public enum OfferPolicy {
        /** 同 key 已有存活任务：摘除旧任务，用新任务（最新优先级）替换 */
        REPLACE,
        /** 同 key 已有存活任务：丢弃新任务（去重） */
        SKIP_IF_PRESENT
    }

    /** offer 结果 */
    public enum OfferResult {
        /** 新任务入队（此前无同 key 存活任务） */
        INSERTED,
        /** 旧任务被摘除，新任务入队 */
        REPLACED,
        /** SKIP_IF_PRESENT 且同 key 已有任务：新任务被丢弃 */
        DUP_SKIPPED
    }

    /**
     * 队列元素。{@link #priority()} 为入队/重插时的冻结键；{@link #generation()} 全局
     * 单调递增（同一 key 内唯一），消费侧用它做版本校验。
     */
    public record Entry<E>(E item, Key key, double priority, long generation) implements Comparable<Entry<E>> {
        @Override
        public int compareTo(Entry<E> other) {
            int c = Double.compare(this.priority, other.priority);
            if (c != 0) {
                return c;
            }
            // review-fix: 同优先级（无锚点任务均为 PRIORITY_UNKNOWN、null key）严格按入队顺序——
            // generation 全局单调递增作 tie-breaker。此前 PriorityBlockingQueue 对 compareTo==0
            // 的元素不保证出队顺序，导致 gateway-only 登录时 ClientboundLoginPacket 与后续
            // 初始化包（PlayerAbilities/SetCarriedItem/…）乱序 dispatch，handleLogin 未执行即
            // 触达后续 handler（player/level null NPE 风暴）。
            return Long.compare(this.generation, other.generation);
        }

        /** 同逻辑元素以新键重插（generation 不变，仍可通过 {@link #isCurrent} 校验）。 */
        public Entry<E> withPriority(double newPriority) {
            return new Entry<>(item, key, newPriority, generation);
        }
    }

    /** 消费时重算优先级：key 为 null（无锚点任务）时返回 {@code oldPriority} 原样。 */
    @FunctionalInterface
    public interface PriorityRefresher {
        double refresh(Key key, double oldPriority);
    }

    /** 全局单调序号（同一 key 内比较有效；跨 key 无意义但无害）。 */
    private final AtomicLong sequence = new AtomicLong();

    private final PriorityBlockingQueue<Entry<E>> heap;
    /** key → 最新存活 entry（同时也是 generation 登记表）。null-key 任务不进本表。 */
    private final ConcurrentHashMap<Key, Entry<E>> current = new ConcurrentHashMap<>();
    /**
     * REPLACE 取代后留在堆中的过期残留条目数（poll/peek/removeIf/压缩移除时递减）。
     * review-fix: T8-22: REPLACE 不再 heap.remove(old)（O(n) 线性扫 → 整体 O(n²)）——
     * 旧条目留堆由消费侧 {@link #isCurrent} 丢弃，本计数用于 {@link #size()} 精确化
     * 与定期压缩（{@link #compactIfNeeded()}）的阈值。
     */
    private final AtomicInteger staleCount = new AtomicInteger();

    /** review-fix: T8-22: 过期残留触发整堆压缩的阈值（达到即 O(n) removeIf 扫一次，摊还 O(1)）。 */
    private static final int COMPACT_THRESHOLD = 256;

    public KeyedPriorityQueue() {
        this(16);
    }

    public KeyedPriorityQueue(int initialCapacity) {
        this.heap = new PriorityBlockingQueue<>(Math.max(4, initialCapacity));
    }

    /**
     * 入队。key 为 null 时无取代/校验语义（等价普通优先队列）。
     *
     * @return 见 {@link OfferResult}
     */
    public OfferResult offer(E item, Key key, double priority, OfferPolicy policy) {
        if (key == null) {
            heap.offer(new Entry<>(item, null, priority, sequence.incrementAndGet()));
            return OfferResult.INSERTED;
        }
        Entry<E> entry = new Entry<>(item, key, priority, sequence.incrementAndGet());
        Entry<E> old = current.putIfAbsent(key, entry);
        if (old == null) {
            heap.offer(entry);
            return OfferResult.INSERTED;
        }
        if (policy == OfferPolicy.SKIP_IF_PRESENT) {
            return OfferResult.DUP_SKIPPED;
        }
        // REPLACE：条件替换登记表（并发 REPLACE 已抢先时本任务作废）；旧任务留在堆中
        // 作为过期残留，由 poll/peek 侧 isCurrent 丢弃——不再 heap.remove(old) 线性扫
        // （玩家来回移动高频 REPLACE 时整体 O(n²) → 摊还 O(1)，review-fix: T8-22）。
        if (!current.replace(key, old, entry)) {
            return OfferResult.DUP_SKIPPED; // 被并发 REPLACE 抢先：丢弃，防双 entry
        }
        heap.offer(entry);
        staleCount.incrementAndGet(); // review-fix: T8-22: 旧任务留堆为过期残留，计数供 size()/压缩阈值
        compactIfNeeded();
        return OfferResult.REPLACED;
    }

    /**
     * review-fix: T8-22: 过期残留达到阈值时整堆压缩一次（O(n)，摊还到每次 REPLACE 为 O(1)）。
     * 仅移除 isCurrent=false 的条目——null-key 恒 current，活条目必是登记表最新值，不会误删。
     * {@link PriorityBlockingQueue#removeIf} 内部加锁，与并发 offer/poll 安全。
     */
    private void compactIfNeeded() {
        if (staleCount.get() < COMPACT_THRESHOLD) {
            return;
        }
        AtomicInteger dropped = new AtomicInteger();
        heap.removeIf(e -> {
            boolean stale = !isCurrent(e);
            if (stale) {
                dropped.incrementAndGet();
            }
            return stale;
        });
        staleCount.addAndGet(-dropped.get());
    }

    /**
     * 出队最优任务；已过期（被 REPLACE / 被 release）的任务在出队时直接丢弃。
     * 返回的 entry 在真正执行前仍需 {@link #isCurrent(Entry)} 复核（poll 与执行之间的竞态）。
     */
    public Entry<E> poll() {
        while (true) {
            Entry<E> entry = heap.poll();
            if (entry == null || isCurrent(entry)) {
                return entry;
            }
            staleCount.decrementAndGet(); // review-fix: T8-22: 丢弃过期残留（堆中垃圾出堆）
        }
    }


    /**
     * 出队最优任务，并在出队瞬间用 {@code refresher} 按当前锚点重算优先级。
     * 若刷新后不再优于新的队首，以新键重插并继续，至多 {@code maxReinserts} 次
     * （防止移动振荡导致任务永远不被消费）。刷新后仍是最优（或达到上限）则返回。
     * <p>
     * 典型场景：服务端数据队列按玩家当前位置重算距离键，冻结键自动追平移动。
     */
    public Entry<E> pollBest(PriorityRefresher refresher, int maxReinserts) {
        int reinserts = 0;
        while (true) {
            Entry<E> entry = heap.poll();
            if (entry == null || !isCurrent(entry)) {
                if (entry == null) {
                    return null;
                }
                staleCount.decrementAndGet(); // review-fix: T8-22: 丢弃过期残留
                continue; // 过期任务丢弃
            }
            double fresh = refresher.refresh(entry.key(), entry.priority());
            if (fresh > entry.priority() && reinserts < maxReinserts) {
                Entry<E> head = heap.peek();
                if (head != null && fresh > head.priority()) {
                    heap.offer(entry.withPriority(fresh));
                    reinserts++;
                    continue;
                }
            }
            return entry;
        }
    }

    /**
     * 查看队首（非破坏性；顺带摘除已过期的队首）。
     */
    public Entry<E> peek() {
        while (true) {
            Entry<E> entry = heap.peek();
            if (entry == null || isCurrent(entry)) {
                return entry;
            }
            heap.poll();
            staleCount.decrementAndGet(); // review-fix: T8-22: 顺带清理过期队首
        }
    }

    /**
     * 版本校验：entry 是否仍是该 key 的最新存活任务。
     * 返回 false 表示已被更新的同 key 任务取代（或已被 release），消费方必须跳过。
     * key 为 null 的任务恒为 current。
     */
    public boolean isCurrent(Entry<E> entry) {
        if (entry == null || entry.key() == null) {
            return entry != null;
        }
        Entry<E> latest = current.get(entry.key());
        return latest != null && latest.generation() == entry.generation();
    }

    /**
     * 释放 entry 的 key 登记（任务已被消费/转入他处）。仅当 entry 仍是该 key 的
     * 最新任务时生效；已被更新的任务取代时 no-op。
     */
    public void release(Entry<E> entry) {
        if (entry == null || entry.key() == null) {
            return;
        }
        current.remove(entry.key(), entry);
    }

    /**
     * 将刚 poll 出的 entry 以新优先级放回（generation 不变，版本校验仍通过）。
     * 仅当 entry 仍是当前任务时生效（已被取代则丢弃，防复活旧数据）。
     *
     * @return true=已放回
     */
    public boolean reoffer(Entry<E> entry, double newPriority) {
        if (entry == null || !isCurrent(entry)) {
            return false;
        }
        heap.offer(entry.withPriority(newPriority));
        return true;
    }

    /**
     * 按条目条件移除任务（并清理 key 登记）。适用于断连清队列等场景。
     */
    public boolean removeIf(Predicate<E> predicate) {
        Object[] snapshot = heap.toArray();
        boolean removed = false;
        for (Object o : snapshot) {
            @SuppressWarnings("unchecked")
            Entry<E> entry = (Entry<E>) o;
            if (predicate.test(entry.item())) {
                boolean removedFromHeap = heap.remove(entry);
                if (removedFromHeap) {
                    removed = true;
                }
                if (entry.key() != null) {
                    // review-fix: T8-22: 登记表移除成功 = 活条目（正常释放）；失败 = 过期残留 → 同步计数
                    boolean released = current.remove(entry.key(), entry);
                    if (removedFromHeap && !released) {
                        staleCount.decrementAndGet();
                    }
                }
            }
        }
        return removed;
    }

    public void clear() {
        heap.clear();
        current.clear();
        staleCount.set(0); // review-fix: T8-22: 清堆同步清零残留计数
    }

    /** 存活任务数（堆中条目数 - 过期残留数）。并发下为近似值。review-fix: T8-22: 残留不计入 size。 */
    public int size() {
        return Math.max(0, heap.size() - staleCount.get());
    }


    public boolean isEmpty() {
        return heap.isEmpty();
    }
}
