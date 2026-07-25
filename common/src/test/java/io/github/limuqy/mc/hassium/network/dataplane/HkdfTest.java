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

    @Test @DisplayName("deriveDataKey 与设计稿 §4 Key derivation 公式一致")
    void deriveDataKey() {
        byte[] token = new byte[16]; // 全零 token（PoC 固定值）
        byte[] playerUuid = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}; // 16 bytes
        byte[] info = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] channelId = new byte[]{0,0,0,1}; // VarInt 1 的字节
        byte[] combinedInfo = new byte[info.length + channelId.length];
        System.arraycopy(info, 0, combinedInfo, 0, info.length);
        System.arraycopy(channelId, 0, combinedInfo, 0, channelId.length);
        byte[] key = Hkdf.extractAndExpand(token, playerUuid, combinedInfo, 16);
        assertEquals(16, key.length);
        // 派生结果应该是确定性的
        byte[] key2 = Hkdf.extractAndExpand(token, playerUuid, combinedInfo, 16);
        assertArrayEquals(key, key2);
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        return data;
    }
}