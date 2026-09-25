package io.github.limuqy.mc.hassium.protocol;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字典热更 rollout 门控单测：激活版只在全连接字典 ACK（epoch+id）后切换；
 * 未 ACK 连接 / 旧协议连接阻断切换；阻断解除（ACK 齐或断连）后立即切换。
 * <p>
 * rollout 触发链（trainAsync → offerTrainedDictionary）为私有且依赖训练管线，
 * 测试经反射植入服务端会话状态（serverSide / activeAggregation / lastTrained）。
 */
class DictionaryRolloutTest {

    @AfterEach
    void resetSession() {
        DictionaryManager.resetServerSession();
        DictionaryManager.resetClientSession();
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = DictionaryManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void invokeOffer() throws Exception {
        Method method = DictionaryManager.class.getDeclaredMethod("maybeOfferDictionary");
        method.setAccessible(true);
        try {
            method.invoke(null);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Connection newConnection() {
        return new Connection(PacketFlow.SERVERBOUND);
    }

    private static DictionarySnapshot snapshot(int epoch, String content) {
        return DictionarySnapshot.of(epoch, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void flipsOnlyAfterEveryActiveConnectionAcks() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("serverSide", true);
        DictionarySnapshot epoch1 = snapshot(1, "dict-v1");
        DictionarySnapshot epoch2 = snapshot(2, "dict-v2");
        setStatic("activeAggregation", epoch1);
        setStatic("lastTrained", epoch2);

        Connection alice = newConnection();
        Connection bob = newConnection();
        try {
            HassiumConnectionRegistry.markEnabled(alice);
            HassiumConnectionRegistry.markEnabled(bob);
            HassiumConnectionRegistry.markEpochAware(alice);
            HassiumConnectionRegistry.markEpochAware(bob);

            invokeOffer();
            // offer 发出但不切换：激活版仍是 epoch1
            assertSame(epoch1, DictionaryManager.getActiveSnapshot());

            DictionaryManager.onDictionaryAck(alice, epoch2.epoch(), epoch2.id());
            assertSame(epoch1, DictionaryManager.getActiveSnapshot());

            DictionaryManager.onDictionaryAck(bob, epoch2.epoch(), epoch2.id());
            // 全员确认 → 切换，旧版保留为 previous（在途旧帧仍可解）
            assertSame(epoch2, DictionaryManager.getActiveSnapshot());
            assertArrayEquals(epoch1.data(), DictionaryManager.getAggregationDictForEpoch(epoch1.epoch()));
            assertArrayEquals(epoch2.data(), DictionaryManager.getAggregationDictForEpoch(epoch2.epoch()));
        } finally {
            HassiumConnectionRegistry.markDisabled(alice);
            HassiumConnectionRegistry.markDisabled(bob);
        }
    }

    @Test
    void disconnectOfUnackedConnectionUnblocksFlip() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("serverSide", true);
        DictionarySnapshot epoch1 = snapshot(1, "dict-v1");
        DictionarySnapshot epoch2 = snapshot(2, "dict-v2");
        setStatic("activeAggregation", epoch1);
        setStatic("lastTrained", epoch2);

        Connection acked = newConnection();
        Connection stalling = newConnection();
        try {
            HassiumConnectionRegistry.markEnabled(acked);
            HassiumConnectionRegistry.markEnabled(stalling);
            HassiumConnectionRegistry.markEpochAware(acked);
            HassiumConnectionRegistry.markEpochAware(stalling);

            invokeOffer();
            DictionaryManager.onDictionaryAck(acked, epoch2.epoch(), epoch2.id());
            assertSame(epoch1, DictionaryManager.getActiveSnapshot());

            // 未 ACK 的连接断开：离开活跃集 → 不再阻断 → 立即切换
            HassiumConnectionRegistry.markDisabled(stalling);
            DictionaryManager.onConnectionGone(stalling);
            assertSame(epoch2, DictionaryManager.getActiveSnapshot());
        } finally {
            HassiumConnectionRegistry.markDisabled(acked);
            HassiumConnectionRegistry.markDisabled(stalling);
        }
    }

    @Test
    void retriesOfferWhenNewEpochAwareConnectionActivatesAfterAbandon() throws Exception {
        // review P1 场景：offer 超时放弃（pendingOffer 清空、lastTrained 保留候选），
        // 已有连接保持在线且无断开事件——新 epoch-aware 连接完成激活必须重试 offer
        DictionaryManager.resetServerSession();
        setStatic("serverSide", true);
        DictionarySnapshot epoch1 = snapshot(1, "dict-v1");
        DictionarySnapshot epoch2 = snapshot(2, "dict-v2");
        setStatic("activeAggregation", epoch1);
        setStatic("lastTrained", epoch2);
        setStatic("pendingOffer", null); // 超时放弃后的状态

        Connection existing = newConnection();
        Connection newcomer = newConnection();
        try {
            HassiumConnectionRegistry.markEnabled(existing);
            HassiumConnectionRegistry.markEpochAware(existing);

            // 新连接完成激活（handleActivationReady 会先 markEnabled/markEpochAware 再发本事件）
            HassiumConnectionRegistry.markEnabled(newcomer);
            HassiumConnectionRegistry.markEpochAware(newcomer);
            DictionaryManager.onConnectionActivated(newcomer);
            // 搁置的候选重新进入 offer 流程
            Field pending = DictionaryManager.class.getDeclaredField("pendingOffer");
            pending.setAccessible(true);
            assertSame(epoch2, pending.get(null));

            DictionaryManager.onDictionaryAck(existing, epoch2.epoch(), epoch2.id());
            assertSame(epoch1, DictionaryManager.getActiveSnapshot());
            DictionaryManager.onDictionaryAck(newcomer, epoch2.epoch(), epoch2.id());
            assertSame(epoch2, DictionaryManager.getActiveSnapshot());
        } finally {
            HassiumConnectionRegistry.markDisabled(existing);
            HassiumConnectionRegistry.markDisabled(newcomer);
        }
    }

    @Test
    void classifyDictionaryAckMatrix() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("serverSide", true);
        DictionarySnapshot epoch1 = snapshot(1, "dict-v1");
        DictionarySnapshot epoch2 = snapshot(2, "dict-v2");
        setStatic("activeAggregation", epoch1);
        setStatic("lastTrained", epoch2);
        setStatic("pendingOffer", epoch2);

        assertEquals(DictionaryManager.AckKind.OFFER,
                DictionaryManager.classifyDictionaryAck(epoch2.epoch(), epoch2.id()));
        assertEquals(DictionaryManager.AckKind.ACTIVE,
                DictionaryManager.classifyDictionaryAck(epoch1.epoch(), epoch1.id()));
        assertEquals(DictionaryManager.AckKind.NONE,
                DictionaryManager.classifyDictionaryAck(0, 0L));
        assertEquals(DictionaryManager.AckKind.NONE,
                DictionaryManager.classifyDictionaryAck(9, 42L));
        // 激活版回执在 offer 窗口内同样必须归类为 ACTIVE（激活校验的接受集）
        setStatic("pendingOffer", null);
        assertEquals(DictionaryManager.AckKind.ACTIVE,
                DictionaryManager.classifyDictionaryAck(epoch1.epoch(), epoch1.id()));
        // 服务端无字典（冒烟删档）+ 客户端未安装（0,0）：视为一致（否则激活死循环重发）
        setStatic("activeAggregation", null);
        assertEquals(DictionaryManager.AckKind.ACTIVE,
                DictionaryManager.classifyDictionaryAck(0, 0L));
        // 服务端无字典但客户端持有跨会话残留：仍是 NONE（触发重发清掉残留）
        assertEquals(DictionaryManager.AckKind.NONE,
                DictionaryManager.classifyDictionaryAck(3, 99L));
    }

    @Test
    void legacyConnectionBlocksOfferUntilItLeaves() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("serverSide", true);
        DictionarySnapshot epoch1 = snapshot(1, "dict-v1");
        DictionarySnapshot epoch2 = snapshot(2, "dict-v2");
        setStatic("activeAggregation", epoch1);
        setStatic("lastTrained", epoch2);

        Connection legacy = newConnection();
        try {
            HassiumConnectionRegistry.markEnabled(legacy);
            // 旧协议客户端（无 epoch 回执能力）在活跃集 → 不发起 offer（切换会破坏其在途帧解析）
            invokeOffer();
            Field pending = DictionaryManager.class.getDeclaredField("pendingOffer");
            pending.setAccessible(true);
            assertNull(pending.get(null));
            assertSame(epoch1, DictionaryManager.getActiveSnapshot());

            // legacy 离开 → 无活跃连接 → 直接切换
            HassiumConnectionRegistry.markDisabled(legacy);
            DictionaryManager.onConnectionGone(legacy);
            assertSame(epoch2, DictionaryManager.getActiveSnapshot());
        } finally {
            HassiumConnectionRegistry.markDisabled(legacy);
        }
    }
}
