package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.world.level.ChunkPos;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每维度 section 哈希存储（阶段二 / unload 跳过重写用）。
 * <p>
 * 与热度索引完全分离；命中路径不读本文件。
 * <p>
 * 文件格式（{@code section_hashes.bin}）：
 * <pre>
 * SHSH (4) + version u16(1) + count u32
 * + 重复 count 次：chunkX i32 + chunkZ i32 + n u16 + n×i64
 * </pre>
 */
public class SectionHashStore implements Closeable {

    private static final int MAGIC = 0x53485348; // "SHSH"
    private static final short VERSION = 1;
    private static final String FILE_NAME = "section_hashes.bin";

    private final Path filePath;
    private final ConcurrentHashMap<Long, long[]> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object flushLock = new Object();
    private final Thread writerThread;
    private volatile boolean closed = false;

    public SectionHashStore(Path cacheRoot) throws IOException {
        Files.createDirectories(cacheRoot);
        this.filePath = cacheRoot.resolve(FILE_NAME);
        load();
        writerThread = new Thread(this::drainLoop, "Hassium-SectionHash-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void put(int chunkX, int chunkZ, long[] sectionHashes) {
        if (closed || sectionHashes == null || sectionHashes.length == 0) {
            return;
        }
        entries.put(ChunkPos.asLong(chunkX, chunkZ), sectionHashes.clone());
        dirty.set(true);
    }

    public long[] get(int chunkX, int chunkZ) {
        long[] hashes = entries.get(ChunkPos.asLong(chunkX, chunkZ));
        return hashes == null ? null : hashes.clone();
    }

    public void remove(int chunkX, int chunkZ) {
        if (entries.remove(ChunkPos.asLong(chunkX, chunkZ)) != null) {
            dirty.set(true);
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            dirty.set(true);
        }
    }

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            byte[] all = Files.readAllBytes(filePath);
            if (all.length < 10) {
                Constants.LOG.warn("Hassium: section_hashes.bin too small, rebuilding");
                return;
            }
            ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int magic = buf.getInt();
            short version = buf.getShort();
            if (magic != MAGIC || version != VERSION) {
                Constants.LOG.warn("Hassium: section_hashes.bin magic/version mismatch, rebuilding");
                return;
            }
            int count = buf.getInt();
            if (count < 0 || count > 1_000_000) {
                Constants.LOG.warn("Hassium: section_hashes.bin corrupt count={}, rebuilding", count);
                return;
            }
            for (int i = 0; i < count; i++) {
                if (buf.remaining() < 10) {
                    Constants.LOG.warn("Hassium: section_hashes.bin truncated, loaded {} entries", i);
                    break;
                }
                int chunkX = buf.getInt();
                int chunkZ = buf.getInt();
                int n = Short.toUnsignedInt(buf.getShort());
                if (n > 64 || buf.remaining() < n * 8) {
                    Constants.LOG.warn("Hassium: section_hashes.bin corrupt entry, stopping load");
                    break;
                }
                long[] hashes = new long[n];
                for (int j = 0; j < n; j++) {
                    hashes[j] = buf.getLong();
                }
                entries.put(ChunkPos.asLong(chunkX, chunkZ), hashes);
            }
            Constants.LOG.debug("Hassium: Loaded {} section hash entries from {}", entries.size(), filePath);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to load section_hashes.bin, starting empty", e);
            entries.clear();
        }
    }

    private void drainLoop() {
        while (!closed) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (dirty.get()) {
                flush();
            }
        }
        flush();
    }

    /** 若有脏数据则整文件重写 */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        synchronized (flushLock) {
            Path tmp = filePath.resolveSibling(FILE_NAME + ".tmp");
            try {
                int count = entries.size();
                int payload = 0;
                for (long[] h : entries.values()) {
                    payload += 10 + h.length * 8;
                }
                ByteBuffer buf = ByteBuffer.allocate(10 + payload).order(ByteOrder.BIG_ENDIAN);
                buf.putInt(MAGIC);
                buf.putShort(VERSION);
                buf.putInt(count);
                for (Map.Entry<Long, long[]> e : entries.entrySet()) {
                    long key = e.getKey();
                    long[] hashes = e.getValue();
                    buf.putInt(ChunkPos.getX(key));
                    buf.putInt(ChunkPos.getZ(key));
                    buf.putShort((short) hashes.length);
                    for (long h : hashes) {
                        buf.putLong(h);
                    }
                }
                buf.flip();
                try (FileChannel ch = FileChannel.open(tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    ch.write(buf);
                    ch.force(true);
                }
                try {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                dirty.set(true);
                Constants.LOG.warn("Hassium: Failed to flush section_hashes.bin", e);
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        writerThread.interrupt();
        try {
            writerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }
}
