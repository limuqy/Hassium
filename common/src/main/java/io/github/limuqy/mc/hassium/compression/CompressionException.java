package io.github.limuqy.mc.hassium.compression;

/**
 * 压缩异常
 */
public class CompressionException extends Exception {

    public CompressionException(String message) {
        super(message);
    }

    public CompressionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 压缩失败异常
     */
    public static class CompressionFailedException extends CompressionException {
        public CompressionFailedException(String message) {
            super(message);
        }

        public CompressionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 解压失败异常
     */
    public static class DecompressionFailedException extends CompressionException {
        public DecompressionFailedException(String message) {
            super(message);
        }

        public DecompressionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 字典加载失败异常
     */
    public static class DictionaryLoadException extends CompressionException {
        private final String dictionaryId;

        public DictionaryLoadException(String dictionaryId, String message) {
            super("Failed to load dictionary " + dictionaryId + ": " + message);
            this.dictionaryId = dictionaryId;
        }

        public DictionaryLoadException(String dictionaryId, String message, Throwable cause) {
            super("Failed to load dictionary " + dictionaryId + ": " + message, cause);
            this.dictionaryId = dictionaryId;
        }

        public String getDictionaryId() {
            return dictionaryId;
        }
    }

    /**
     * 校验和验证失败异常
     */
    public static class ChecksumMismatchException extends CompressionException {
        private final long expected;
        private final long actual;

        public ChecksumMismatchException(long expected, long actual) {
            super(String.format("Checksum mismatch: expected %016x, got %016x", expected, actual));
            this.expected = expected;
            this.actual = actual;
        }

        public long getExpected() {
            return expected;
        }

        public long getActual() {
            return actual;
        }
    }
}
