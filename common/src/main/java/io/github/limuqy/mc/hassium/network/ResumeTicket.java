package io.github.limuqy.mc.hassium.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 续流票据（REQ §B9）：玩家 UUID + epoch + 签发时间戳 + HMAC-SHA256 签名。
 * <p>
 * 纯 Java（无 MC/netty 依赖），可单测。线格式（握手 append-only 尾部，由
 * {@link HandshakeStateTail} 承载）：
 * <ul>
 *   <li>旧格式（{@code ENCODED_LENGTH}=56）：{@code [uuid(16B) | epoch(8B BE) | sig(32B)]}，
 *       签名输入 = uuid(16B) + epoch(8B BE)，签发时间戳 0=无期限（向后兼容旧客户端）</li>
 *   <li>新格式 v1（{@code ENCODED_LENGTH_V1}=65）：{@code [version(1B)=1 | uuid(16B) | epoch(8B BE) | issuedAtMs(8B BE) | sig(32B)]}，
 *       签名输入 = uuid(16B) + epoch(8B BE) + issuedAtMs(8B BE)（时间戳受签名保护，防篡改延长）</li>
 * </ul>
 * 密钥 = 主控 A/B 共享密钥（{@link #setSharedKey}）。
 * 服务端（B）验签通过 + epoch 递增 + 时间窗口未过期（{@link ResumeTicketValidator}）→ 续流就绪。
 */
public final class ResumeTicket {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ResumeTicket");

    /** HMAC-SHA256 签名长度 */
    public static final int SIGNATURE_LENGTH = 32;

    /** 旧格式编码总长：uuid(16) + epoch(8) + sig(32)（无签发时间戳） */
    public static final int ENCODED_LENGTH = 16 + 8 + SIGNATURE_LENGTH;

    /** 线格式版本号（新格式首字节；旧格式无版本字节，按长度区分） */
    public static final byte FORMAT_VERSION = 1;

    /** 新格式 v1 编码总长：version(1) + uuid(16) + epoch(8) + issuedAtMs(8) + sig(32) */
    public static final int ENCODED_LENGTH_V1 = 1 + 16 + 8 + 8 + SIGNATURE_LENGTH;

    /** null = 未显式配置共享密钥 → 续流禁用（review-fix: T13-C2） */
    private static volatile byte[] sharedKey;

    private final UUID playerId;
    private final long epoch;
    /** 签发时间戳（ms；0 = 旧格式票据，无期限，时间窗口校验跳过） */
    private final long issuedAtMs;
    private final byte[] signature;

    /** 旧格式构造：无签发时间戳（issuedAtMs=0，向后兼容旧客户端/旧测试）。 */
    public ResumeTicket(UUID playerId, long epoch, byte[] signature) {
        this(playerId, epoch, 0L, signature);
    }

    public ResumeTicket(UUID playerId, long epoch, long issuedAtMs, byte[] signature) {
        this.playerId = playerId;
        this.epoch = epoch;
        this.issuedAtMs = issuedAtMs;
        this.signature = signature == null ? new byte[0] : signature.clone();
    }

    public UUID playerId() {
        return playerId;
    }

    public long epoch() {
        return epoch;
    }

    /** 签发时间戳（ms）；0 = 旧格式/无期限。 */
    public long issuedAtMs() {
        return issuedAtMs;
    }

    public byte[] signature() {
        return signature.clone();
    }

    /**
     * 当前共享密钥；未显式配置（{@link #setSharedKey} 未调用或传 null/空）时为 null，
     * 此时续流禁用：sign 返回 null、verify 返回 false（部署时主控 A/B 必须显式
     * 统一配置——密钥分发属部署事项，T8 落地）。
     */
    public static byte[] sharedKey() {
        return sharedKey;
    }

    public static void setSharedKey(byte[] key) {
        sharedKey = key == null || key.length == 0 ? null : key.clone();
    }

    /** 签名输入（旧格式）：uuid(16B) + epoch(8B BE) */
    public static byte[] signingInput(UUID playerId, long epoch) {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.putLong(epoch);
        return buffer.array();
    }

    /** 签名输入（新格式 v1）：uuid(16B) + epoch(8B BE) + issuedAtMs(8B BE)，时间戳受签名保护 */
    public static byte[] signingInput(UUID playerId, long epoch, long issuedAtMs) {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.putLong(epoch);
        buffer.putLong(issuedAtMs);
        return buffer.array();
    }

    public static byte[] sign(UUID playerId, long epoch, byte[] key) {
        if (key == null) {
            LOGGER.warn("ResumeTicket: shared key not configured, resume signing disabled");
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signingInput(playerId, epoch));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** 新格式签发（含签发时间戳）；密钥未配置时返回 null（续流禁用语义同旧格式）。 */
    public static byte[] sign(UUID playerId, long epoch, long issuedAtMs, byte[] key) {
        if (key == null) {
            LOGGER.warn("ResumeTicket: shared key not configured, resume signing disabled");
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signingInput(playerId, epoch, issuedAtMs));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** 验签（常数时间比较）；密钥未配置时返回 false。按格式选择签名输入：旧格式无时间戳。 */
    public boolean verify(byte[] key) {
        if (key == null) {
            LOGGER.warn("ResumeTicket: shared key not configured, resume verification disabled");
            return false;
        }
        if (signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        byte[] expected = issuedAtMs == 0
                ? sign(playerId, epoch, key)
                : sign(playerId, epoch, issuedAtMs, key);
        return MessageDigest.isEqual(signature, expected);
    }

    /** 使用当前共享密钥验签 */
    public boolean verify() {
        return verify(sharedKey);
    }

    /** 编码：旧格式（issuedAtMs=0）56 字节向后兼容；新格式 v1（含签发时间戳）65 字节。 */
    public byte[] encode() {
        if (issuedAtMs == 0) {
            ByteBuffer buffer = ByteBuffer.allocate(ENCODED_LENGTH);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(playerId.getMostSignificantBits());
            buffer.putLong(playerId.getLeastSignificantBits());
            buffer.putLong(epoch);
            buffer.put(signature);
            return buffer.array();
        }
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_LENGTH_V1);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(FORMAT_VERSION);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.putLong(epoch);
        buffer.putLong(issuedAtMs);
        buffer.put(signature);
        return buffer.array();
    }

    /** 解码；长度/版本不符抛 {@link IllegalArgumentException}。56B=旧格式，65B=v1（版本前缀）。 */
    public static ResumeTicket decode(byte[] data) {
        if (data == null || (data.length != ENCODED_LENGTH && data.length != ENCODED_LENGTH_V1)) {
            throw new IllegalArgumentException(
                    "ResumeTicket: bad length " + (data == null ? "null" : data.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);
        if (data.length == ENCODED_LENGTH_V1) {
            if (buffer.get() != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                        "ResumeTicket: unsupported format version " + (data[0] & 0xFF));
            }
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            long epoch = buffer.getLong();
            long issuedAtMs = buffer.getLong();
            byte[] sig = new byte[SIGNATURE_LENGTH];
            buffer.get(sig);
            return new ResumeTicket(id, epoch, issuedAtMs, sig);
        }
        UUID id = new UUID(buffer.getLong(), buffer.getLong());
        long epoch = buffer.getLong();
        byte[] sig = new byte[SIGNATURE_LENGTH];
        buffer.get(sig);
        return new ResumeTicket(id, epoch, sig);
    }

    @Override
    public String toString() {
        return "ResumeTicket{player=" + playerId + ", epoch=" + epoch + ", issuedAtMs=" + issuedAtMs
                + ", sig=" + signature.length + "B}";
    }
}
