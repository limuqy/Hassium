package io.github.limuqy.mc.hassium.network.dataplane;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * UDP 数据面会话密钥派生的唯一实现。
 *
 * <p>同一 {@code (token, playerId, epoch, endpointId, channelId)} 必须在 client/server
 * 派生出同一把 AEAD 密钥；任一身份字段变化必须隔离旧会话。
 */
final class UdpSessionKey {

    private static final byte[] INFO_PREFIX = "hassium-udp-v1".getBytes(StandardCharsets.US_ASCII);

    private UdpSessionKey() {
    }

    static byte[] derive(byte[] token, UUID playerId, long epoch, int endpointId, int channelId) {
        if (token == null || token.length != 16) {
            throw new IllegalArgumentException("token must be 16 bytes");
        }
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (endpointId < 0 || channelId < 0) {
            throw new IllegalArgumentException("endpointId/channelId must be non-negative");
        }
        // review-fix: T4-M1 — endpointId/channelId 以 4 字节大端写入 info，杜绝单字节截断导致的
        // 密钥域碰撞（如 endpointId=256 与 endpointId=0 曾导出同一把 key）；client/server 共用本方法，天然对称。
        return Hkdf.extractAndExpand(token, concat(uuidBytes(playerId), longBytes(epoch)),
                concat(INFO_PREFIX, concat(intBytes(endpointId), intBytes(channelId))), 16);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static byte[] uuidBytes(UUID playerId) {
        return concat(longBytes(playerId.getMostSignificantBits()), longBytes(playerId.getLeastSignificantBits()));
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            out[i] = (byte) value;
            value >>>= Byte.SIZE;
        }
        return out;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
