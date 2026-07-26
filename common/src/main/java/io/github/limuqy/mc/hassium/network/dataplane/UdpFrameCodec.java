package io.github.limuqy.mc.hassium.network.dataplane;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * UDP 数据面应用帧的 AES-GCM 加密/解密。
 *
 * <p>Wire layout（每帧）:
 * <pre>
 *   sequence[u64 big-endian, 明文头]   // 8 字节，仅用于 nonce 重建与重放过滤
 *   ciphertext                         // AEAD(sequence || type || payload)
 * </pre>
 * sequence 同时作为明文头与 AEAD 明文前缀 —— Brief 接口契约要求 AEAD plaintext layout
 * 为 {@code sequence || type || payload}；明文头仅服务 nonce 重建，承担冗余但保证接收端
 * 即使不在 {@code expectedMinimumSequence} 处亦可解密，并可在 AEAD 成功后比对明文头与
 * 解密头一致（额外抗 nonce 误用）。
 *
 * <p>Nonce（12 字节）：{@code direction[u8] + 11-byte big-endian unsigned sequence}。
 * 同 (key, direction) 下 sequence 严格单调，nonce 不复用。密封同一 (key, dir, seq, type, payload)
 * 两次输出同一密文，重传保留可逐位比较的字节。
 *
 * <p>不同 {@link Direction} 不同 nonce，杜绝同方向跨用。{@link #open} 先用明文头 sequence 重建 nonce
 * 解密，AEAD 成功后校验解出的明文 sequence 与明文头一致，再做重放过滤
 * ({@code sequence >= expectedMinimumSequence})，否则抛 {@link SecurityException}。
 *
 * <p>该 codec 不负责 HKDF 密钥派生（Task 3 拥有），调用方传入 16 字节密钥。
 */
public final class UdpFrameCodec {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGO = "AES";
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int NONCE_BYTES = 12;
    private static final int SEQUENCE_BYTES = Long.BYTES;
    private static final int TYPE_BYTES = 1;
    private static final int AEAD_PLAINTEXT_PREFIX_BYTES = SEQUENCE_BYTES + TYPE_BYTES;

    private UdpFrameCodec() {}

    /** 帧方向；写入 nonce 第 1 字节，避免双向 nonce 重复。 */
    public enum Direction {
        CLIENT_TO_SERVER((byte) 0),
        SERVER_TO_CLIENT((byte) 1);

        private final byte code;

        Direction(byte code) { this.code = code; }

        public byte code() { return code; }
    }

    /** 解封后的应用帧。 */
    public record Opened(long sequence, int type, byte[] payload) {}

    /**
     * 加密一帧；返回 {@code sequence[u64 明文头] + ciphertext(AEAD(sequence||type||payload))}。
     *
     * @param key 16 字节 AES 密钥
     * @param direction 帧方向
     * @param sequence 单调递增序列号；同一 (key, direction) 下不得重复
     * @param type {@link DataPlaneFrame} 帧类型常数（1–9）；超出区间抛 IllegalArgumentException
     * @param payload 明文 payload；可为 null 或空
     * @throws IllegalArgumentException key 不合法、direction null、sequence 负、type 不在合法帧类型区间
     */
    public static byte[] seal(byte[] key, Direction direction, long sequence,
                              int type, byte[] payload) {
        validateKey(key);
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        validateType(type);
        int payloadLen = payload != null ? payload.length : 0;
        byte[] aadPlaintext = new byte[AEAD_PLAINTEXT_PREFIX_BYTES + payloadLen];
        writeLong(aadPlaintext, 0, sequence);
        aadPlaintext[SEQUENCE_BYTES] = (byte) (type & 0xFF);
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, aadPlaintext, AEAD_PLAINTEXT_PREFIX_BYTES, payloadLen);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, KEY_ALGO),
                    new GCMParameterSpec(TAG_BITS, nonce(direction, sequence)));
            byte[] ciphertext = cipher.doFinal(aadPlaintext);
            byte[] out = new byte[SEQUENCE_BYTES + ciphertext.length];
            writeLong(out, 0, sequence);
            System.arraycopy(ciphertext, 0, out, SEQUENCE_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("seal failed", e);
        }
    }

    /**
     * 解封一帧。
     *
     * @param key 16 字节 AES 密钥
     * @param direction 期望的方向（必须与发送方 seal 时一致）
     * @param expectedMinimumSequence 期望的最小合法 sequence；AEAD 成功后比对，小于此值视为重放
     * @param sealed seal 输出（8 字节明文 sequence 头 + ciphertext）
     * @throws SecurityException AEAD 鉴权失败、方向错、明文头与解密头不一致、重放
     */
    public static Opened open(byte[] key, Direction direction, long expectedMinimumSequence,
                              byte[] sealed) {
        validateKey(key);
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (sealed == null || sealed.length < SEQUENCE_BYTES + TAG_BYTES + AEAD_PLAINTEXT_PREFIX_BYTES) {
            throw new SecurityException("sealed frame too short");
        }
        long headerSequence = readLong(sealed, 0);
        int cipherLen = sealed.length - SEQUENCE_BYTES;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, KEY_ALGO),
                    new GCMParameterSpec(TAG_BITS, nonce(direction, headerSequence)));
            byte[] plaintext = cipher.doFinal(sealed, SEQUENCE_BYTES, cipherLen);
            long decryptedSequence = readLong(plaintext, 0);
            if (decryptedSequence != headerSequence) {
                throw new SecurityException("sequence header/plaintext mismatch");
            }
            int type = plaintext[SEQUENCE_BYTES] & 0xFF;
            int payloadLen = plaintext.length - AEAD_PLAINTEXT_PREFIX_BYTES;
            if (payloadLen < 0) {
                throw new SecurityException("decrypted frame truncated");
            }
            if (headerSequence < expectedMinimumSequence) {
                throw new SecurityException("replay: sequence " + headerSequence
                        + " below minimum " + expectedMinimumSequence);
            }
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0) {
                System.arraycopy(plaintext, AEAD_PLAINTEXT_PREFIX_BYTES, payload, 0, payloadLen);
            }
            return new Opened(headerSequence, type, payload);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("open failed (auth/direction)", e);
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }
    }

    /**
     * 校验帧类型落在 {@link DataPlaneFrame} 合法区间 [1, 9]。
     * 防止 {@code type > 255} 被静默截断成低字节——例如 256 → 0。
     */
    private static void validateType(int type) {
        if (type < 1 || type > 9) {
            throw new IllegalArgumentException("invalid DataPlaneFrame type: " + type);
        }
    }

    private static byte[] nonce(Direction direction, long sequence) {
        byte[] nonce = new byte[NONCE_BYTES];
        nonce[0] = direction.code();
        // 11-byte big-endian unsigned sequence：低 8 字节填 nonce[4..11]，nonce[1..3] 留 0。
        for (int i = 0; i < 8; i++) {
            nonce[NONCE_BYTES - 1 - i] = (byte) (sequence >>> (8 * i));
        }
        return nonce;
    }

    private static void writeLong(byte[] buf, int off, long v) {
        buf[off]     = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }

    private static long readLong(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 56)
                | ((long) (buf[off + 1] & 0xFF) << 48)
                | ((long) (buf[off + 2] & 0xFF) << 40)
                | ((long) (buf[off + 3] & 0xFF) << 32)
                | ((long) (buf[off + 4] & 0xFF) << 24)
                | ((long) (buf[off + 5] & 0xFF) << 16)
                | ((long) (buf[off + 6] & 0xFF) << 8)
                | ((long) (buf[off + 7] & 0xFF));
    }
}
