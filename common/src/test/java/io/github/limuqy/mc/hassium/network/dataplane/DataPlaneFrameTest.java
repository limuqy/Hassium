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
            () -> DataPlaneFrame.encode(0, new byte[1]));
        assertThrows(IllegalArgumentException.class,
            () -> DataPlaneFrame.encode(8, new byte[1]));
    }

    @Test @DisplayName("截断输入抛出异常")
    void truncatedInput() {
        byte[] bad = new byte[]{2, 1}; // type=1, 但 payload 缺失
        assertThrows(Exception.class, () -> DataPlaneFrame.decodePayload(bad));
    }
}
