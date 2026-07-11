package io.github.limuqy.mc.hassium.storage;

/**
 * 存储异常基类
 */
public class StorageException extends Exception {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 区块未找到异常
     */
    public static class ChunkNotFoundException extends StorageException {
        private final ChunkStorageKey key;

        public ChunkNotFoundException(ChunkStorageKey key) {
            super("Chunk not found: " + key.dimension() + " [" + key.chunkX() + ", " + key.chunkZ() + "]");
            this.key = key;
        }

        public ChunkStorageKey getKey() {
            return key;
        }
    }

    /**
     * 存储损坏异常
     */
    public static class StorageCorruptedException extends StorageException {
        public StorageCorruptedException(String message) {
            super(message);
        }

        public StorageCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 字典缺失异常
     */
    public static class DictionaryMissingException extends StorageException {
        private final String dictionaryId;

        public DictionaryMissingException(String dictionaryId) {
            super("Dictionary not found: " + dictionaryId);
            this.dictionaryId = dictionaryId;
        }

        public String getDictionaryId() {
            return dictionaryId;
        }
    }

    /**
     * 只读模式异常
     */
    public static class ReadOnlyException extends StorageException {
        public ReadOnlyException(String message) {
            super(message);
        }
    }
}
