package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** UDP listener 两端必须以同一身份材料导出会话密钥。 */
final class UdpSessionKeyTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final long EPOCH = 0x0123_4567_89AB_CDEFL;

    @Test
    void derivesSameKeyForTheSamePlayerEpochEndpointAndChannel() {
        byte[] token = new byte[16];
        token[0] = 7;
        token[15] = (byte) 0xA5;

        byte[] first = UdpSessionKey.derive(token, PLAYER, EPOCH, 2, 2);
        byte[] same = UdpSessionKey.derive(token, PLAYER, EPOCH, 2, 2);
        byte[] differentEpoch = UdpSessionKey.derive(token, PLAYER, EPOCH + 1, 2, 2);

        assertArrayEquals(first, same, "相同 UDP 会话身份必须导出相同密钥");
        assertFalse(java.util.Arrays.equals(first, differentEpoch),
                "epoch 变化必须隔离旧 UDP 会话密钥");
    }
}
