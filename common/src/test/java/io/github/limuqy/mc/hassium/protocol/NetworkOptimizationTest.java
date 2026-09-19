package io.github.limuqy.mc.hassium.protocol;

import org.junit.jupiter.api.BeforeAll;
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

    /**
     * 注册表 bootstrap 前置：IndexSyncManager 初始化会经 PacketCodecCompat 触发
     * GameProtocols/ItemStack/BuiltInRegistries 静态初始化；未 bootstrap 时该失败
     * 会让这些类在测试 JVM 内永久不可用，连锁污染后续所有依赖注册表的测试。
     */
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

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
        indexManager.register("hassium:play_init_s2c");

        // 验证注册
        assertTrue(indexManager.contains("minecraft:commands"));
        assertTrue(indexManager.contains("minecraft:login"));
        assertTrue(indexManager.contains("hassium:play_init_s2c"));
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

        int[] index3 = indexManager.getIndex("hassium:play_init_s2c");
        assertNotNull(index3);
        assertEquals(2, index3[0]); // 第二个 namespace
        assertEquals(1, index3[1]); // 第一个 path

        // 验证反向查找
        assertEquals("minecraft:commands", indexManager.getIdentifier(1, 1));
        assertEquals("minecraft:login", indexManager.getIdentifier(1, 2));
        assertEquals("hassium:play_init_s2c", indexManager.getIdentifier(2, 1));
    }

    @Test
    @DisplayName("测试 NamespaceIndexManager 批量注册")
    void testNamespaceIndexManagerBatchRegister() {
        // 批量注册
        indexManager.registerAll(java.util.Arrays.asList(
                "minecraft:commands",
                "minecraft:login",
                "hassium:dictionary_sync",
                "hassium:play_init_s2c"
        ));

        // 验证数量
        assertEquals(4, indexManager.size());

        // 验证确定性排序（namespace 字典序，然后 path 字典序）
        int[] index1 = indexManager.getIndex("hassium:dictionary_sync");
        int[] index2 = indexManager.getIndex("hassium:play_init_s2c");
        int[] index3 = indexManager.getIndex("minecraft:commands");
        int[] index4 = indexManager.getIndex("minecraft:login");

        // hassium 在 minecraft 之前（字典序）
        assertTrue(index1[0] < index3[0]);
        // dictionary_sync 在 play_init_s2c 之前
        assertTrue(index1[1] < index2[1]);
    }

    @Test
    @DisplayName("测试 NamespaceIndexManager 序列化和反序列化")
    void testNamespaceIndexManagerSerialization() {
        // 注册包类型
        indexManager.registerAll(java.util.Arrays.asList(
                "minecraft:commands",
                "minecraft:login",
                "hassium:dictionary_sync",
                "hassium:play_init_s2c"
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
        assertTrue(newIndexManager.contains("hassium:dictionary_sync"));
        assertTrue(newIndexManager.contains("hassium:play_init_s2c"));

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

    @Test
    @DisplayName("控制面 S2C 必须硬编码黑名单，禁止进聚合")
    void testControlPlaneHardcodedBlacklist() {
        // 文档约定：handshake / index sync / chunkHash 等控制面不进 PENDING 聚合缓冲。
        // 聚合拆包走 RawCustomPayload.handle 会绕开 Fabric/NeoForge receiver。
        String[] controlPlane = {
                HassiumPacketIds.DICTIONARY_SYNC_S2C,
                HassiumPacketIds.INDEX_SYNC_S2C,
                HassiumPacketIds.PLAY_INIT_S2C,
                HassiumPacketIds.SHADOW_PULL_RESPONSE_S2C,
                HassiumPacketIds.MAIN_CHANNEL,
                HassiumPacketIds.AGGREGATION_S2C
        };
        for (String id : controlPlane) {
            assertTrue(PacketCompressionBlacklist.isHardcodedBlacklist(id),
                    "control-plane must be hardcoded blacklist: " + id);
            assertFalse(PacketCompressionBlacklist.shouldCompress(id),
                    "control-plane must not compress: " + id);
            assertFalse(PacketCompressionBlacklist.shouldAggregate(id),
                    "control-plane must not aggregate: " + id);
        }
        // 原版包默认仍可压缩/聚合
        assertTrue(PacketCompressionBlacklist.shouldCompress("minecraft:keep_alive"));
        assertTrue(PacketCompressionBlacklist.shouldAggregate("minecraft:keep_alive"));
    }

    @Test
    @DisplayName("区块图控制包禁止应用层聚合；实体高频包已放开进聚合")
    void testHighFrequencyNoAggregate() {
        // 区块图控制路径（pull 模式域）保持直发
        // 1.20.1 snake_case 全名
        assertTrue(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_forget_level_chunk_packet"));
        assertTrue(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_set_chunk_cache_center_packet"));
        assertTrue(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_set_chunk_cache_radius_packet"));
        // 1.20.5+ PacketType id 形态
        assertTrue(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:forget_level_chunk"));
        assertTrue(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:set_chunk_cache_center"));

        // 聚合入口必须拒绝
        assertFalse(PacketCompressionBlacklist.shouldAggregate(
                "minecraft:clientbound_forget_level_chunk_packet"));

        // 实体高频包（2026-09-14 评估放开）：可聚合、可压缩
        // 1.20.1 嵌套类短 path
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate("minecraft:pos"));
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate("minecraft:pos_rot"));
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate("minecraft:rot"));
        // 1.20.1 snake_case 全名
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_set_entity_motion_packet"));
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_rotate_head_packet"));
        // 1.20.5+ PacketType id 形态
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:set_entity_motion"));
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:move_entity_pos"));
        assertTrue(PacketCompressionBlacklist.shouldAggregate("minecraft:pos"));
        assertTrue(PacketCompressionBlacklist.shouldAggregate(
                "minecraft:clientbound_set_entity_motion_packet"));

        // 非高频包仍可聚合
        assertFalse(PacketCompressionBlacklist.isHighFrequencyNoAggregate(
                "minecraft:clientbound_block_update_packet"));
        assertTrue(PacketCompressionBlacklist.shouldAggregate(
                "minecraft:clientbound_block_update_packet"));
    }
}
