package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HkdfTest {

    @Test @DisplayName("RFC 5869 Appendix A.1 — SHA-256 测试向量")
    void rfc5869_a1() {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] expected = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        byte[] result = Hkdf.extractAndExpand(ikm, salt, info, 42);
        assertArrayEquals(expected, result);
    }

    @Test @DisplayName("UdpSessionKey.derive 确定性 + 参数隔离（T4-80 INFO 收敛后唯一实现）")
    void deriveDataKey() {
        byte[] token = new byte[16]; // 全零 token（PoC 固定值）
        java.util.UUID playerId = new java.util.UUID(0x0001020304050607L, 0x08090a0b0c0d0e0fL);
        byte[] key = UdpSessionKey.derive(token, playerId, 0L, 1, 1);
        assertEquals(16, key.length);
        // 派生结果应该是确定性的
        byte[] key2 = UdpSessionKey.derive(token, playerId, 0L, 1, 1);
        assertArrayEquals(key, key2);
        // 参数隔离：endpointId/channelId 任一变化必须换 key（T4-M1 防密钥域碰撞）
        assertFalse(java.util.Arrays.equals(key, UdpSessionKey.derive(token, playerId, 0L, 2, 1)));
        assertFalse(java.util.Arrays.equals(key, UdpSessionKey.derive(token, playerId, 0L, 1, 2)));
        assertFalse(java.util.Arrays.equals(key, UdpSessionKey.derive(token, playerId, 1L, 1, 1)));
    }

    @Test @DisplayName("expand 长度越界抛 IllegalArgumentException（RFC 5869 上限）")
    void expandLengthBounds() {
        byte[] ikm = new byte[16];
        // review-fix: T4-79 — 0 与 > 255*32 越界拒绝；上限值合法且长度精确。
        assertThrows(IllegalArgumentException.class, () -> Hkdf.extractAndExpand(ikm, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.extractAndExpand(ikm, null, null, 255 * 32 + 1));
        assertEquals(255 * 32, Hkdf.extractAndExpand(ikm, null, null, 255 * 32).length);
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        return data;
    }
}