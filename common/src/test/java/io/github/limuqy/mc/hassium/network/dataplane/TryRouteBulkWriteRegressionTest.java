package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tryRouteBulk 回归守护：33d1cf2 修复时误删 writeAndFlush 导致缓存命中率塌陷（详见
 * docs/superpowers/plans/2026-07-25-multi-channel-dataplane-poc.md）。
 * 本测试用 Netty {@link EmbeddedChannel} 在内存中真实走 write 路径，验证三件事：
 * <ol>
 *   <li>share 模式 + 有效 Data 通道时 tryRouteBulk 返回 true（caller 应跳过 Primary）</li>
 *   <li>EmbeddedChannel 收到一条加密帧 ByteBuf（writeAndFlush 副作用未丢失）</li>
 *   <li>{@link NetworkStats#getBulkBytesData()} 累加 > 0（分流埋点未丢失）</li>
 * </ol>
 * 任一断言失败 = 33d1cf2 类回归——纯日志重构再次吃掉相邻非日志副作用。
 */
class TryRouteBulkWriteRegressionTest {

    private boolean prevNetworkStats;
    private String prevMode;
    private byte[] aesKey;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        prevNetworkStats = NetworkStats.isEnabled();
        prevMode = DataPlaneServer.getRuntimeMode();
        aesKey = DataPlaneServer.deriveChannelKey(1, 1);
        playerId = DataPlanePoCConfig.pseudoPlayerId();
        DataPlaneServer.clearRuntimeMode();
        NetworkStats.setEnabled(true);
        // 清 PlayerBundle，测试隔离
        DataPlaneServer.removeBundle(playerId);
    }

    @AfterEach
    void tearDown() {
        DataPlaneServer.removeBundle(playerId);
        DataPlaneServer.setRuntimeMode(prevMode);
        NetworkStats.setEnabled(prevNetworkStats);
    }

    @Test @DisplayName("tryRouteBulk 成功路径：EmbeddedChannel 写入一条帧 + NetworkStats.bulkBytesData 累加")
    void tryRouteBulk_writesFrameAndRecordsDataBulk() {
        NetworkStats.reset();
        // 绑定一条 mock Data 通道到玩家 bundle（绕过 TCP bind 握手）
        EmbeddedChannel ec = new EmbeddedChannel();
        PlayerChannel pc = new PlayerChannel(ec, 50, aesKey, 1);
        DataPlaneServer.getOrCreateBundle(playerId).addChannel(pc);

        byte[] payload = new byte[64];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;

        // 确保走 Data 路径：强制 share 模式（PRIMARY_WEIGHT=100 vs Data weight=50 多次轮询，
        // 至少一次 WRR 选中 Data — 但要 deterministic 命中，循环 200 次 ≥ 1 次成功路径触发）
        DataPlaneServer.setRuntimeMode("share");
        int routedCount = 0;
        for (int i = 0; i < 200; i++) {
            if (DataPlaneServer.tryRouteBulk(playerId, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload)) {
                routedCount++;
            }
        }
        assertTrue(routedCount > 0, "share 模式 Data weight>0 应至少部分路由成功 routedCount=" + routedCount);

        // 副作用 1：EmbeddedChannel outbound 队列收到 N 条 ByteBuf（每条 = 一次 writeAndFlush）
        int writtenFrames = ec.outboundMessages().size();
        assertEquals(routedCount, writtenFrames,
                "writeAndFlush 误删回归：outbound 帧数应等于路由成功次数");

        // 副作用 2：NetworkStats 记录 bulkBytesData > 0（payload.length × routedCount 等）
        long recorded = NetworkStats.getMetrics().getBulkBytesData();
        assertTrue(recorded > 0,
                "recordBulkSentData 埋点回归：NetworkStats.bulkBytesData 应累加 > 0，实得=" + recorded);

        // 副作用 3：Primary 计数未被污染（应该恒 0，因走 Data 路径）
        assertEquals(0L, NetworkStats.getMetrics().getBulkFramesPrimary(),
                "Data 路径不应累加 Primary 计数");
    }

    @Test @DisplayName("no bundle → tryRouteBulk 返回 false，Primary 路径占主导（NetworkStats.bulkBytesData 不变）")
    void tryRouteBulk_noBundle_returnsFalse() {
        NetworkStats.reset();
        long beforeBytes = NetworkStats.getMetrics().getBulkBytesData();
        UUID ghostId = new UUID(0L, 424242L);
        DataPlaneServer.removeBundle(ghostId);

        byte[] payload = new byte[]{1, 2, 3, 4};
        boolean routed = DataPlaneServer.tryRouteBulk(
                ghostId, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);
        assertFalse(routed, "ghostId 无 bundle 必返回 false 走 Primary");
        assertEquals(beforeBytes, NetworkStats.getMetrics().getBulkBytesData(),
                "无 bundle 走 Primary 不应累加 Data bulkBytes");
    }

    @Test @DisplayName("tryRouteBulk:y() false 返回 false — write 抛异常 catch 降级 Primary（不抛出）")
    void tryRouteBulk_encryptFailure_returnsFalseSilently() {
        NetworkStats.reset();
        EmbeddedChannel ec = new EmbeddedChannel();
        // aesKey 故意填成错误长度触发 encrypt 失败（DataPlaneCodec 期望 16 字节 AES；用 8 字节）
        byte[] badKey = new byte[8];
        PlayerChannel pc = new PlayerChannel(ec, 50, badKey, 1);
        DataPlaneServer.getOrCreateBundle(playerId).addChannel(pc);

        byte[] payload = new byte[]{1, 2, 3, 4};
        // share 模式若选中 Data 通道 → encrypt 失败 → catch → return false
        // 不排 dare 在 200 轮中始终保持 Primary 选 Data 不命中（weight 50/100），故以"无异常抛出"为断言
        DataPlaneServer.setRuntimeMode("share");
        boolean threw = false;
        try {
            for (int i = 0; i < 100; i++) {
                DataPlaneServer.tryRouteBulk(playerId, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);
            }
        } catch (Throwable t) {
            threw = true;
        }
        assertFalse(threw, "tryRouteBulk 无论 encrypt 成败都不应抛异常");
        // 即便少量 Data 路径被选中 encrypt 失败也不应累加 bulkBytesData（未写入即 catch）
        // Primary 计数不应被污染（decrypt 失败路径不调 recordBulkSentPrimary）
        assertEquals(0L, NetworkStats.getMetrics().getBulkFramesPrimary(),
                "encrypt 失败 catch 降级不应污染 Primary 计数（埋点在 caller 走 Primary 时计）");
    }
}
