package io.github.limuqy.mc.hassium.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网络优化测试
 * <p>
 * 测试紧凑包头、索引管理等功能
 */
public class NetworkOptimizationTest {

    private NamespaceIndexManager indexManager;

    @BeforeEach
    void setUp() {
        indexManager = new NamespaceIndexManager();
    }

    @Test
    @DisplayName("测试 NamespaceIndexManager 注册和查找")
    void testNamespaceIndexManager() {
        // 注册包类型
        indexManager.register("minecraft:commands");
        indexManager.register("minecraft:login");
        indexManager.register("hassium:handshake_c2s");

        // 验证注册
        assertTrue(indexManager.contains("minecraft:commands"));
        assertTrue(indexManager.contains("minecraft:login"));
        assertTrue(indexManager.contains("hassium:handshake_c2s"));
        assertFalse(indexManager.contains("minecraft:unknown"));

        // 验证索引
        int[] index1 = indexManager.getIndex("minecraft:commands");
        assertNotNull(index1);
        assertEquals(1, index1[0]); // namespace 索引从 1 开始
        assertEquals(1, index1[1]); // path 索引从 1 开始

        int[] index2 = indexManager.getIndex("minecraft:login");
        assertNotNull(index2);
        assertEquals(1, index2[0]); // 同一个 namespace
        assertEquals(2, index2[1]); // 第二个 path

        int[] index3 = indexManager.getIndex("hassium:handshake_c2s");
        assertNotNull(index3);
        assertEquals(2, index3[0]); // 第二个 namespace
        assertEquals(1, index3[1]); // 第一个 path

        // 验证反向查找
        assertEquals("minecraft:commands", indexManager.getIdentifier(1, 1));
        assertEquals("minecraft:login", indexManager.getIdentifier(1, 2));
        assertEquals("hassium:handshake_c2s", indexManager.getIdentifier(2, 1));
    }

    @Test
    @DisplayName("测试 NamespaceIndexManager 批量注册")
    void testNamespaceIndexManagerBatchRegister() {
        // 批量注册
        indexManager.registerAll(java.util.Arrays.asList(
                "minecraft:commands",
                "minecraft:login",
                "hassium:handshake_c2s",
                "hassium:handshake_s2c"
        ));

        // 验证数量
        assertEquals(4, indexManager.size());

        // 验证确定性排序（namespace 字典序，然后 path 字典序）
        int[] index1 = indexManager.getIndex("hassium:handshake_c2s");
        int[] index2 = indexManager.getIndex("hassium:handshake_s2c");
        int[] index3 = indexManager.getIndex("minecraft:commands");
        int[] index4 = indexManager.getIndex("minecraft:login");

        // hassium 在 minecraft 之前（字典序）
        assertTrue(index1[0] < index3[0]);
        // handshake_c2s 在 handshake_s2c 之前
        assertTrue(index1[1] < index2[1]);
    }

    @Test
    @DisplayName("测试 NamespaceIndexManager 序列化和反序列化")
    void testNamespaceIndexManagerSerialization() {
        // 注册包类型
        indexManager.registerAll(java.util.Arrays.asList(
                "minecraft:commands",
                "minecraft:login",
                "hassium:handshake_c2s",
                "hassium:handshake_s2c"
        ));

        // 序列化
        byte[] data = indexManager.serialize();
        assertNotNull(data);
        assertTrue(data.length > 0);

        // 反序列化到新的管理器
        NamespaceIndexManager newIndexManager = new NamespaceIndexManager();
        newIndexManager.deserialize(data);

        // 验证反序列化后的数据
        assertEquals(indexManager.size(), newIndexManager.size());

        // 验证所有包类型都存在
        assertTrue(newIndexManager.contains("minecraft:commands"));
        assertTrue(newIndexManager.contains("minecraft:login"));
        assertTrue(newIndexManager.contains("hassium:handshake_c2s"));
        assertTrue(newIndexManager.contains("hassium:handshake_s2c"));

        // 验证索引一致
        int[] index1 = indexManager.getIndex("minecraft:commands");
        int[] index2 = newIndexManager.getIndex("minecraft:commands");
        assertArrayEquals(index1, index2);
    }

    @Test
    @DisplayName("测试 IndexSyncManager")
    void testIndexSyncManager() {
        IndexSyncManager syncManager = IndexSyncManager.getInstance();

        // 初始化服务端索引
        syncManager.initializeServerIndex();

        // 验证服务端索引不为空
        NamespaceIndexManager serverIndex = syncManager.getServerIndexManager();
        assertTrue(serverIndex.size() > 0);

        // 创建同步包
        IndexSyncPacket syncPacket = syncManager.createSyncPacket();
        assertNotNull(syncPacket);

        // 应用到客户端
        String connectionId = "test-connection";
        NamespaceIndexManager clientIndex = syncManager.handleSyncPacket(connectionId, syncPacket);

        // 验证客户端索引与服务端一致
        assertEquals(serverIndex.size(), clientIndex.size());

        // 清理
        syncManager.removeClientIndexManager(connectionId);
    }
}
