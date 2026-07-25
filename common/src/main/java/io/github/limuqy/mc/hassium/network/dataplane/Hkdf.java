package io.github.limuqy.mc.hassium.network.dataplane;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * 纯 JDK HKDF-SHA256 (RFC 5869)。
 * extract-then-expand 两步式；不依赖 BouncyCastle。
 */
public class Hkdf {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * HKDF extract + expand 一步完成。
     *
     * @param ikm    Initial Keying Material
     * @param salt   盐（可以为空）
     * @param info   上下文信息
     * @param length 目标密钥长度（字节）
     * @return 派生密钥
     */
    public static byte[] extractAndExpand(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            byte[] prk = extract(ikm, salt);
            return expand(prk, info, length);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, IKM) */
    static byte[] extract(byte[] ikm, byte[] salt) throws GeneralSecurityException {
        if (salt == null || salt.length == 0) salt = new byte[32];
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(salt, HMAC_SHA256));
        return mac.doFinal(ikm);
    }

    /** HKDF-Expand: OKM = T(1) || T(2) || ... */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(prk, HMAC_SHA256));

        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (byte i = 1; pos < length; i++) {
            mac.update(t);
            if (info != null) mac.update(info);
            mac.update(i);
            t = mac.doFinal();
            int copyLen = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, result, pos, copyLen);
            pos += copyLen;
        }
        return result;
    }
}
