package io.github.limuqy.mc.hassium.migration;

/**
 * 迁移异常
 */
public class MigrationException extends Exception {

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 格式不兼容异常
     */
    public static class IncompatibleFormatException extends MigrationException {
        public IncompatibleFormatException(String message) {
            super(message);
        }
    }

    /**
     * 数据损坏异常
     */
    public static class DataCorruptedException extends MigrationException {
        public DataCorruptedException(String message) {
            super(message);
        }

        public DataCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 迁移已中止异常
     */
    public static class MigrationAbortedException extends MigrationException {
        public MigrationAbortedException(String message) {
            super(message);
        }

        public MigrationAbortedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
