package io.github.limuqy.mc.hassium.storage;

import java.util.zip.CRC32C;

/**
 * 校验和工具类
 */
public final class ChecksumUtils {

    private ChecksumUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算 CRC32C 校验和
     *
     * @param data 数据
     * @return 校验和
     */
    public static long crc32c(byte[] data) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(data);
        return crc32c.getValue();
    }

    /**
     * 计算 CRC32C 校验和
     *
     * @param data   数据
     * @param offset 偏移量
     * @param length 长度
     * @return 校验和
     */
    public static long crc32c(byte[] data, int offset, int length) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(data, offset, length);
        return crc32c.getValue();
    }

    /**
     * 验证校验和
     *
     * @param data     数据
     * @param expected 期望的校验和
     * @return 如果校验通过返回 true
     */
    public static boolean verifyCrc32c(byte[] data, long expected) {
        return crc32c(data) == expected;
    }

    /**
     * 计算 CRC32C 校验和（兼容方法）
     *
     * @param data 数据
     * @return 校验和
     */
    public static long calculateCRC32C(byte[] data) {
        return crc32c(data);
    }
}
