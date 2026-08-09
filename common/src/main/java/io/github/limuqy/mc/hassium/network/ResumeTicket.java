package io.github.limuqy.mc.hassium.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 续流票据（REQ §B9）：玩家 UUID + epoch + HMAC-SHA256 签名。
 * <p>
 * 纯 Java（无 MC/netty 依赖），可单测。线格式（握手 append-only 尾部，由
 * {@link HandshakeStateTail} 承载）：{@code varint len + [uuid(16B) | epoch(8B BE) | sig(32B)]}。
 * 签名输入 = uuid(16B) + epoch(8B BE)，密钥 = 主控 A/B 共享密钥（{@link #setSharedKey}）。
 * 服务端（B）验签通过且 epoch 递增（{@link ResumeTicketValidator}）→ 续流就绪。
 */
public final class ResumeTicket {

    /** HMAC-SHA256 签名长度 */
    public static final int SIGNATURE_LENGTH = 32;

    /** 编码后总长：uuid(16) + epoch(8) + sig(32) */
    public static final int ENCODED_LENGTH = 16 + 8 + SIGNATURE_LENGTH;

    private static volatile byte[] sharedKey = defaultSharedKey();

    private final UUID playerId;
    private final long epoch;
    private final byte[] signature;

    public ResumeTicket(UUID playerId, long epoch, byte[] signature) {
        this.playerId = playerId;
        this.epoch = epoch;
        this.signature = signature == null ? new byte[0] : signature.clone();
    }

    public UUID playerId() {
        return playerId;
    }

    public long epoch() {
        return epoch;
    }

    public byte[] signature() {
        return signature.clone();
    }

    /**
     * 当前共享密钥（默认由常量派生；部署时主控 A/B 通过
     * {@link #setSharedKey} 统一覆盖——密钥分发属部署事项，T8 落地）。
     */
    public static byte[] sharedKey() {
        return sharedKey;
    }

    public static void setSharedKey(byte[] key) {
        sharedKey = key == null || key.length == 0 ? defaultSharedKey() : key.clone();
    }

    /** 签名输入：uuid(16B) + epoch(8B BE) */
    public static byte[] signingInput(UUID playerId, long epoch) {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.putLong(epoch);
        return buffer.array();
    }

    public static byte[] sign(UUID playerId, long epoch, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signingInput(playerId, epoch));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** 验签（常数时间比较） */
    public boolean verify(byte[] key) {
        if (signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(signature, sign(playerId, epoch, key));
    }

    /** 使用当前共享密钥验签 */
    public boolean verify() {
        return verify(sharedKey);
    }

    /** 编码：uuid + epoch + sig（56 字节） */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_LENGTH);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.putLong(epoch);
        buffer.put(signature);
        return buffer.array();
    }

    /** 解码；长度不符抛 {@link IllegalArgumentException} */
    public static ResumeTicket decode(byte[] data) {
        if (data == null || data.length != ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    "ResumeTicket: bad length " + (data == null ? "null" : data.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);
        UUID id = new UUID(buffer.getLong(), buffer.getLong());
        long epoch = buffer.getLong();
        byte[] sig = new byte[SIGNATURE_LENGTH];
        buffer.get(sig);
        return new ResumeTicket(id, epoch, sig);
    }

    private static byte[] defaultSharedKey() {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest("hassium-resume-shared-key-v1".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            byte[] key = new byte[SIGNATURE_LENGTH];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (0xA5 + i);
            }
            return key;
        }
    }

    @Override
    public String toString() {
        return "ResumeTicket{player=" + playerId + ", epoch=" + epoch + ", sig=" + signature.length + "B}";
    }
}
