package io.github.limuqy.mc.hassium.server.entity;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每连接「本 tick 已发出的实体更新包数」计数器（帧率压力调节的实测输入）。
 * <p>
 * 位置包是**相对 base 的增量**，丢一个就永久错位（只有绝对包能自愈），所以压力调节
 * **不在投递侧丢包或延迟投递**——这里只做纯计数，实际降速由
 * {@link EntityUpdatePacing} 在实体发送源头（{@code ServerEntity.sendChanges} 的间隔）反压。
 * <p>
 * 计数走 {@code Connection.send} 热路径：一次 {@code ConcurrentHashMap#get} + 一次
 * {@code incrementAndGet}，无分配（计数器首次出现时才建）。
 */
public final class EntityPacketCounters {

    private static final Map<Connection, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();

    private EntityPacketCounters() {
    }

    /**
     * 该包是否属于「实体位移/旋转/速度/硬同步」族（本域关注的流量）。
     * <p>
     * 用 {@code instanceof} 而非包名字符串：包名字符串在 1.20.1 段依赖
     * {@code NamespaceIndexManager} 的类→标识符映射，而这里只需要稳定分类。
     */
    public static boolean isEntityUpdatePacket(Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundTeleportEntityPacket) {
            return true;
        }
#if MC_VER >= MC_1_21_2
        // 1.21.2 起原版用 ClientboundEntityPositionSyncPacket 取代 teleport 包做硬同步
        return packet instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
#else
        return false;
#endif
    }

    /** 记一次实体包（仅当 {@link #isEntityUpdatePacket(Packet)} 为真时调用）。 */
    public static void observe(Connection connection) {
        AtomicInteger counter = COUNTERS.get(connection);
        if (counter == null) {
            counter = COUNTERS.computeIfAbsent(connection, ignored -> new AtomicInteger());
        }
        counter.incrementAndGet();
    }

    /**
     * 取出并清零「游戏态连接」本 tick 的实体包数，写入 {@code out}（调用方复用容器，稳态零分配）。
     * <p>
     * 引擎对**全部**玩家生效（含原版客户端——实体域是 vanilla 兼容的复制节拍优化），
     * 所以压力输入必须覆盖所有已进入 Play 的连接，而不是只采样 Hassium 已协商连接。
     * 握手期/状态查询期连接没有 {@code ServerGamePacketListenerImpl}，不计入。
     *
     * @param out 目标容器；方法内部先 {@code clear()} 再填充
     */
    public static void drainGameInto(Map<Connection, Integer> out) {
        out.clear();
        for (Map.Entry<Connection, AtomicInteger> entry : COUNTERS.entrySet()) {
            Connection connection = entry.getKey();
            if (!(connection.getPacketListener()
                    instanceof net.minecraft.server.network.ServerGamePacketListenerImpl)) {
                continue;
            }
            int count = entry.getValue().getAndSet(0);
            out.put(connection, count);
        }
    }

    /** 断开连接时清理（{@code Connection} 是强引用键，不清理会随会话累积）。 */
    public static void forget(Connection connection) {
        COUNTERS.remove(connection);
    }

    public static void clear() {
        COUNTERS.clear();
    }
}
