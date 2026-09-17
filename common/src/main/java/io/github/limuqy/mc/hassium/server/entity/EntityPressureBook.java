package io.github.limuqy.mc.hassium.server.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 帧率压力账本：**每观察者一份** {@link EntityFramePressure}，按各自的实测实体包量独立反压。
 * <p>
 * 为什么不能全服共用一份：反压的输入是「该观察者当帧收到的实体包数 / 配额」。全服共用时，一个玩家
 * 身边的热点（大农场、掉落物塔）会把倍率顶起来，连累其它玩家身边的实体一起降帧——「远端热点，
 * 近处连坐」。改成每观察者一份后，谁超预算谁自己的视锥内实体降帧。
 * <p>
 * 代价（记录在案）：{@code ServerEntity} 是「每实体一份、广播给全部跟踪者」，没有 per-observer 发送
 * 路径，所以单实体的帧率只能取**一个**值——本域用「最近观察者的压力」当作该值（与距离档位同源，
 * 见 {@code EntityUpdatePacing}）。共享该实体的其它观察者拿到同一帧率，这是并集语义的既有边界。
 * <p>
 * 有状态、<b>非线程安全</b>，只允许主线程独占调用（与 {@code EntityUpdatePacing} 同线程模型）。
 */
public final class EntityPressureBook {

    private final Map<Object, EntityFramePressure> books = new HashMap<>();
    private final int maxPressure;

    /**
     * @param maxPressure 单份压力档上限，构造内 clamp 到 [1, 16]
     */
    public EntityPressureBook(int maxPressure) {
        this.maxPressure = maxPressure;
    }

    /**
     * 用某个观察者当帧的实测值更新它的压力档；首次出现时按需建实例。
     *
     * @param key    观察者标识（服务端用 {@code ServerPlayer} 实例）
     * @param packets 该观察者当帧发送的实体包数
     * @param budget  该观察者的当帧配额；{@code <= 0} 表示本帧无配额信息，该观察者回到初始档
     */
    public void sample(Object key, int packets, int budget) {
        if (key == null) {
            return;
        }
        EntityFramePressure book = books.get(key);
        if (book == null) {
            book = new EntityFramePressure(maxPressure);
            books.put(key, book);
        }
        book.sample(packets, budget);
    }

    /**
     * 该观察者当前的压力档。
     *
     * @return {@code >= 1} 的倍率；未知观察者（没观测过 / 与 Hassium 无关的连接）返回 1
     */
    public int pressureOf(Object key) {
        if (key == null) {
            return 1;
        }
        EntityFramePressure book = books.get(key);
        return book == null ? 1 : book.pressure();
    }

    /**
     * 丢弃不在本帧观测集合里的观察者（断连、或本帧没有实测值的连接），避免随会话累积。
     * <p>
     * 传本帧实测到的观察者集合而不是「断连通知」：后者要在断连路径上再挂一个钩子，而这里的集合本来
     * 每帧就有，两者等价（计数器条目随断连移除，观察者也就不再出现在集合里）。
     */
    public void retainOnly(Collection<?> liveKeys) {
        books.keySet().retainAll(liveKeys);
    }

    /** 回到初始态：清空全部观察者的压力档。 */
    public void reset() {
        books.clear();
    }

    /** @return 当前在册观察者数（诊断用） */
    public int size() {
        return books.size();
    }
}
