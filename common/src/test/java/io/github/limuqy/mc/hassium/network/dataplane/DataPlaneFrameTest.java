package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlaneFrameTest {

    @Test @DisplayName("帧类型常量与设计稿一致")
    void typeConstants() {
        assertEquals(1, DataPlaneFrame.TYPE_BIND_REQUEST);
        assertEquals(2, DataPlaneFrame.TYPE_BIND_ACK);
        assertEquals(3, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK);
        assertEquals(5, DataPlaneFrame.TYPE_KEEPALIVE);
        assertEquals(6, DataPlaneFrame.TYPE_KEEPALIVE_ACK);
        assertEquals(7, DataPlaneFrame.TYPE_CLOSE);
        assertEquals(8, DataPlaneFrame.TYPE_FAILOVER_REQUEST);
        assertEquals(9, DataPlaneFrame.TYPE_FAILOVER_PERMIT);
    }

    @Test @DisplayName("encode/decode 往返——payload 非空")
    void roundTrip_withPayload() {
        byte[] payload = new byte[]{10, 20, 30, 40};
        byte[] frame = DataPlaneFrame.encode(3, payload);
        assertEquals(3, DataPlaneFrame.decodeType(frame));
        assertArrayEquals(payload, DataPlaneFrame.decodePayload(frame));
    }

    @Test @DisplayName("encode/decode 往返——payload 为空")
    void roundTrip_emptyPayload() {
        byte[] frame = DataPlaneFrame.encode(2, new byte[0]);
        assertEquals(2, DataPlaneFrame.decodeType(frame));
        assertEquals(0, DataPlaneFrame.decodePayload(frame).length);
    }

    @Test @DisplayName("frameLen 等于总长度减 VarInt(frameLen) 自身占用的字节")
    void frameLengthIncludesType() {
        byte[] payload = new byte[100];
        byte[] frame = DataPlaneFrame.encode(3, payload);
        assertTrue(frame.length >= 102);
    }

    @Test @DisplayName("非法 type 抛出 IllegalArgumentException")
    void invalidType() {
        assertThrows(IllegalArgumentException.class,
            () -> DataPlaneFrame.encode(0, new byte[1]));     // 0 < MIN_TYPE
        assertThrows(IllegalArgumentException.class,
            () -> DataPlaneFrame.encode(99, new byte[1]));    // 99 > MAX_TYPE
    }

    @Test @DisplayName("截断输入抛出异常")
    void truncatedInput() {
        byte[] bad = new byte[]{2, 1}; // type=1, 但 payload 缺失
        assertThrows(Exception.class, () -> DataPlaneFrame.decodePayload(bad));
    }

    @Test @DisplayName("decodeType 拒绝非法类型（review-fix: T4-84 类型范围校验）")
    void decodeTypeRejectsOutOfRangeType() {
        byte[] frame = new byte[] {2, 0, 1}; // frameLen=2（type+payload），type=0 非法
        assertThrows(IllegalArgumentException.class, () -> DataPlaneFrame.decodeType(frame));
        byte[] high = new byte[] {2, 10, 1}; // type=10 > MAX_TYPE
        assertThrows(IllegalArgumentException.class, () -> DataPlaneFrame.decodeType(high));
    }

    @Test @DisplayName("decodeType 超大 VarInt frameLen 抛 IllegalArgumentException 而非 BufferUnderflowException（review-fix: T4-84 溢出防护）")
    void decodeTypeRejectsOverflowingFrameLen() {
        // 5 字节 VarInt = 0xFFFFFFFF（frameLen ≈ 2^32）：原实现 int 溢出使截断检查失效 → BufferUnderflowException；
        // 修复后走 long 比较 → IllegalArgumentException。
        byte[] overflow = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, 3};
        assertThrows(IllegalArgumentException.class, () -> DataPlaneFrame.decodeType(overflow));
    }

    @Test @DisplayName("decodePayload 拒绝 frameLen=0（防 dataLen=-1 负数组分配，review-fix: T4-84）")
    void decodePayloadRejectsZeroFrameLen() {
        byte[] frame = new byte[] {0, 1};
        assertThrows(IllegalArgumentException.class, () -> DataPlaneFrame.decodePayload(frame));
    }
}
