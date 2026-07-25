package io.github.limuqy.mc.hassium.network.dataplane;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import java.security.GeneralSecurityException;

/**
 * DataPlane 帧加密/解密。
 * 使用 AES/CFB8/NoPadding；派生密钥按设计稿 §6.4。
 * frameLen 保持明文；加密范围：type||payload。
 */
public class DataPlaneCodec {

    private static final String CIPHER = "AES/CFB8/NoPadding";
    private static final String KEY_ALGO = "AES";
    /** CFB8 的 IV = 全零 16 字节（每次加密重置偏移量） */
    private static final byte[] ZERO_IV = new byte[16];

    /**
     * 加密帧：编码 type + payload → 加密 type||payload → 拼接 VarInt(frameLen) + 密文。
     * frameLen 计算在加密前（包含明文的 type 长度），但写入的是加密后的 payload。
     * 注意：frameLen 是指 type + payload 的长度，type 占 1 字节不变。
     */
    public static byte[] encrypt(byte[] key, int type, byte[] payload) {
        try {
            // 先组明文 type||payload
            byte[] plaintext;
            if (payload != null && payload.length > 0) {
                plaintext = new byte[1 + payload.length];
                plaintext[0] = (byte) (type & 0xFF);
                System.arraycopy(payload, 0, plaintext, 1, payload.length);
            } else {
                plaintext = new byte[]{ (byte) (type & 0xFF) };
            }
            // 加密
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGO), new IvParameterSpec(ZERO_IV));
            byte[] encrypted = cipher.doFinal(plaintext);
            // 编码为 DataPlaneFrame：frameLen(cleartext) + encrypted(type||payload)
            // frameLen = encrypted.length (因为 frameLen = type + payload, 加密后长度不变)
            // 用 ByteArrayOutputStream 拼接
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            DataPlaneFrame.writeVarInt(out, encrypted.length);
            out.writeBytes(encrypted);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static class FrameDecryptResult {
        public final int type;
        public final byte[] payload;
        public FrameDecryptResult(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    /**
     * 解密帧：输入为 encode 完整输出，跳过 frameLen → 解密 → 拆 type + payload
     */
    public static FrameDecryptResult decrypt(byte[] key, byte[] frame) {
        try {
            // 跳过 frameLen VarInt
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
            int frameLen = DataPlaneFrame.readVarInt(buf);
            int headerSize = DataPlaneFrame.varIntSize(frameLen);
            int encryptedLen = frame.length - headerSize;
            byte[] encrypted = new byte[encryptedLen];
            buf.get(encrypted);
            // 解密
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGO), new IvParameterSpec(ZERO_IV));
            byte[] decrypted = cipher.doFinal(encrypted);
            int type = decrypted[0] & 0xFF;
            byte[] payload;
            if (decrypted.length > 1) {
                payload = new byte[decrypted.length - 1];
                System.arraycopy(decrypted, 1, payload, 0, payload.length);
            } else {
                payload = new byte[0];
            }
            return new FrameDecryptResult(type, payload);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed / wrong key", e);
        }
    }
}
