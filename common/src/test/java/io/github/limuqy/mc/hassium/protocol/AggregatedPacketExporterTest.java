package io.github.limuqy.mc.hassium.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AggregatedPacketExporter} JSONL 行编码（debug.exportAggregatedPackets 导出格式钉子）。
 */
class AggregatedPacketExporterTest {

    @Test
    void sendLineContainsTypeSizeAndBase64Payload() {
        byte[] payload = {(byte) 0xCA, (byte) 0xFE, 0x00, 0x01};
        String line = AggregatedPacketExporter.toJsonLine(
                1727272727272L, "send", "local", "minecraft:block_update", null, payload);

        assertTrue(line.startsWith("{\"ts\":1727272727272,"), line);
        assertTrue(line.contains("\"src\":\"send\""), line);
        assertTrue(line.contains("\"conn\":\"local\""), line);
        assertTrue(line.contains("\"type\":\"minecraft:block_update\""), line);
        assertTrue(line.contains("\"size\":4"), line);
        assertTrue(line.contains("\"payload\":\"" + java.util.Base64.getEncoder().encodeToString(payload) + "\""), line);
        // 无 subs 字段（send 行）
        assertTrue(!line.contains("\"subs\""), line);
    }

    @Test
    void aggregationFrameLineCarriesSubCount() {
        byte[] frame = "frame-bytes".getBytes(StandardCharsets.UTF_8);
        String line = AggregatedPacketExporter.toJsonLine(
                1L, "agg", "server", HassiumPacketIds.AGGREGATION_S2C, 7, frame);

        assertTrue(line.contains("\"src\":\"agg\""), line);
        assertTrue(line.contains("\"subs\":7"), line);
        assertTrue(line.contains("\"type\":\"" + HassiumPacketIds.AGGREGATION_S2C + "\""), line);
        assertTrue(line.contains("\"payload\":\"" + java.util.Base64.getEncoder().encodeToString(frame) + "\""), line);
    }

    @Test
    void nullPayloadIsRecordedExplicitly() {
        String line = AggregatedPacketExporter.toJsonLine(1L, "send", "local", "unknown:BundlePacket", null, null);
        assertTrue(line.contains("\"size\":0"), line);
        assertTrue(line.contains("\"payload\":null"), line);
    }

    @Test
    void connAndTypeAreJsonEscaped() {
        String line = AggregatedPacketExporter.toJsonLine(
                1L, "send", "we\"ird\naddr", "a:b", null, new byte[0]);
        assertTrue(line.contains("\"conn\":\"we\\\"ird\\naddr\""), line);
        assertTrue(line.contains("\"size\":0"), line);
    }
}
