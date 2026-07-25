package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlaneCodecTest {

    @Test @DisplayName("encrypt → decrypt 往返正确")
    void roundTrip() {
        byte[] key = new byte[16]; // 全零密钥（PoC 用）
        byte[] payload = "hello dataplane".getBytes();
        byte[] encrypted = DataPlaneCodec.encrypt(key, 3, payload);
        DataPlaneCodec.FrameDecryptResult result = DataPlaneCodec.decrypt(key, encrypted);
        assertEquals(3, result.type);
        assertArrayEquals(payload, result.payload);
    }

    @Test @DisplayName("错误密钥解密——CFB8 流密码不抛异常但明文不同")
    void wrongKey() {
        byte[] key1 = new byte[16];
        byte[] key2 = new byte[16]; key2[0] = 0x42;
        byte[] payload = "secret".getBytes();
        byte[] encrypted = DataPlaneCodec.encrypt(key1, 2, payload);
        // CFB8/NoPadding 是流密码，密钥错误不会抛异常；但解出的明文必须与原文不同
        DataPlaneCodec.FrameDecryptResult result = DataPlaneCodec.decrypt(key2, encrypted);
        assertFalse(java.util.Arrays.equals(payload, result.payload),
            "wrong key must produce different plaintext");
    }

    @Test @DisplayName("不同 channelId 派生不同密钥")
    void differentChannelDifferentKey() throws Exception {
        byte[] token = new byte[16];
        byte[] playerUuid = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        byte[] info1 = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] info2 = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // channelId 1 vs 2
        byte[] combined1 = new byte[info1.length + 1]; System.arraycopy(info1,0,combined1,0,info1.length); combined1[info1.length] = 1;
        byte[] combined2 = new byte[info2.length + 1]; System.arraycopy(info2,0,combined2,0,info2.length); combined2[info2.length] = 2;
        byte[] key1 = Hkdf.extractAndExpand(token, playerUuid, combined1, 16);
        byte[] key2 = Hkdf.extractAndExpand(token, playerUuid, combined2, 16);
        assertFalse(java.util.Arrays.equals(key1, key2));
    }

    @Test @DisplayName("frameLen 在加密后保持明文")
    void frameLenCleartext() {
        byte[] key = new byte[16];
        byte[] payload = new byte[200];
        byte[] encrypted = DataPlaneCodec.encrypt(key, 3, payload);
        byte[] plainFrame = DataPlaneFrame.encode(3, payload);
        // frameLen VarInt 头部字节在加密前后保持一致（明文 frameLen）
        // 明文帧 = VarInt(frameLen=201) + type(3) + payload(200)
        // 密文帧 = VarInt(frameLen=201) + encrypted(type||payload, 201字节)
        // 两者 frameLen VarInt 字节相同；但 type||payload 部分不同
        int plainHeaderSize = DataPlaneFrame.varIntSize(201);
        for (int i = 0; i < plainHeaderSize; i++) {
            assertEquals(plainFrame[i], encrypted[i],
                "frameLen VarInt header must be cleartext at byte " + i);
        }
        // 密文体的首字节应与明文 type 字节不同（已加密）
        assertNotEquals(plainFrame[plainHeaderSize], encrypted[plainHeaderSize]);
    }
}
