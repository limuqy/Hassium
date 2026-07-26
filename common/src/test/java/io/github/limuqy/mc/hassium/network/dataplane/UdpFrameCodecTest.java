package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UdpFrameCodecTest {

    private static byte[] testKey() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 7);
        return key;
    }

    @Test
    void sealedFrameRoundTripsAndPreservesSequence() {
        byte[] key = testKey();
        byte[] sealed = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                11L, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {1, 2});

        UdpFrameCodec.Opened opened = UdpFrameCodec.open(
                key, UdpFrameCodec.Direction.SERVER_TO_CLIENT, 0L, sealed);

        assertEquals(11L, opened.sequence());
        assertEquals(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, opened.type());
        assertArrayEquals(new byte[] {1, 2}, opened.payload());
    }

    @Test
    void sealRejectsModifiedCiphertextAndReplay() {
        byte[] key = testKey();
        byte[] sealed = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.CLIENT_TO_SERVER,
                5L, DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);

        byte[] tampered = sealed.clone();
        // 翻转末位（type/payload 段的 GCM 标签位置）
        tampered[tampered.length - 1] ^= 1;
        assertThrows(SecurityException.class,
                () -> UdpFrameCodec.open(key, UdpFrameCodec.Direction.CLIENT_TO_SERVER, 0L, tampered));

        // 重放：期望最小 sequence=6，但帧 sequence=5 → 命中 replay 分支
        byte[] valid = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.CLIENT_TO_SERVER,
                5L, DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);
        assertThrows(SecurityException.class,
                () -> UdpFrameCodec.open(key, UdpFrameCodec.Direction.CLIENT_TO_SERVER, 6L, valid));
    }

    @Test
    void directionMismatchRejectsDecryption() {
        byte[] key = testKey();
        byte[] sealed = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.CLIENT_TO_SERVER,
                7L, DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);

        // wire 头里的明文 sequence 与 expectedMinimum 一致（不触发 replay 拦截），
        // 真正的失败源是 nonce 方向字节不匹配 → GCM 鉴权失败
        assertThrows(SecurityException.class,
                () -> UdpFrameCodec.open(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT, 7L, sealed));
    }

    @Test
    void retransmissionProducesIdenticalCiphertext() {
        byte[] key = testKey();
        byte[] first = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                42L, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {9, 8, 7});
        byte[] second = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                42L, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {9, 8, 7});
        assertArrayEquals(first, second);
    }

    @Test
    void emptyPayloadSealOpenRoundTrip() {
        byte[] key = testKey();
        byte[] sealed = UdpFrameCodec.seal(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                1L, DataPlaneFrame.TYPE_KEEPALIVE, null);
        UdpFrameCodec.Opened opened = UdpFrameCodec.open(
                key, UdpFrameCodec.Direction.SERVER_TO_CLIENT, 0L, sealed);
        assertEquals(1L, opened.sequence());
        assertEquals(DataPlaneFrame.TYPE_KEEPALIVE, opened.type());
        assertEquals(0, opened.payload().length);
    }

    @Test
    void sealRejectsInvalidKeyAndSequence() {
        byte[] key = testKey();
        assertThrows(IllegalArgumentException.class,
                () -> UdpFrameCodec.seal(new byte[15], UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                        1L, DataPlaneFrame.TYPE_BIND_ACK, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> UdpFrameCodec.seal(key, null, 1L, DataPlaneFrame.TYPE_BIND_ACK, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> UdpFrameCodec.seal(key, UdpFrameCodec.Direction.SERVER_TO_CLIENT,
                        -1L, DataPlaneFrame.TYPE_BIND_ACK, new byte[0]));
    }
}
