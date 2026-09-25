package io.github.limuqy.mc.hassium.protocol;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字典更新语料库单测：写盘/裁剪/快照/marker + DictionaryManager 采集门控
 * （目标连接匹配、每分钟一帧、存在字典前置）。
 */
class DictionaryCorpusTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetSession() {
        DictionaryManager.resetServerSession();
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = DictionaryManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    // ===== DictionaryCorpus =====

    @Test
    void writeSampleCreatesSortableFileAndMarkerRoundTrip(@TempDir Path dir) throws Exception {
        DictionaryCorpus corpus = new DictionaryCorpus(dir);
        corpus.writeSample(new byte[]{1, 2, 3});
        List<Path> files = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".bin")).toList();
        assertEquals(1, files.size());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(files.get(0)));
        // 临时文件不残留
        assertTrue(Files.list(dir).noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));

        corpus.writeMarker(1234567890L);
        assertEquals(1234567890L, corpus.readMarker());
        assertFalse(corpus.isDisabled());
    }

    @Test
    void pruneByFileCountRemovesOldest(@TempDir Path dir) throws Exception {
        DictionaryCorpus corpus = new DictionaryCorpus(dir);
        for (int i = 0; i < 5; i++) {
            corpus.writeSample(new byte[]{(byte) i});
            Thread.sleep(2); // 文件名含毫秒，保证名字有序
        }
        corpus.prune(3, Long.MAX_VALUE);
        List<Path> files = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".bin")).toList();
        assertEquals(3, files.size());
        // 保留最新的 3 个：内容为 2,3,4
        List<Byte> contents = files.stream()
                .map(p -> {
                    try {
                        return Files.readAllBytes(p)[0];
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .sorted()
                .toList();
        assertEquals(List.of((byte) 2, (byte) 3, (byte) 4), contents);
    }

    @Test
    void pruneByTotalBytesKeepsNewestFitting(@TempDir Path dir) throws Exception {
        DictionaryCorpus corpus = new DictionaryCorpus(dir);
        corpus.writeSample(new byte[100]);
        Thread.sleep(2);
        corpus.writeSample(new byte[50]);
        corpus.prune(99, 60); // 只容得下 50 字节那份（最新）
        List<Path> files = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".bin")).toList();
        assertEquals(1, files.size());
        assertEquals(50, Files.size(files.get(0)));
    }

    @Test
    void snapshotForTrainingIsNewestFirstAndCapped(@TempDir Path dir) throws Exception {
        DictionaryCorpus corpus = new DictionaryCorpus(dir);
        for (int i = 0; i < 4; i++) {
            corpus.writeSample(new byte[]{(byte) i});
            Thread.sleep(2);
        }
        List<byte[]> all = corpus.snapshotForTraining(10, Long.MAX_VALUE);
        assertEquals(4, all.size());
        assertEquals(3, all.get(0)[0]); // 最新在前
        assertEquals(0, all.get(3)[0]);

        List<byte[]> capped = corpus.snapshotForTraining(2, Long.MAX_VALUE);
        assertEquals(2, capped.size());
        assertEquals(3, capped.get(0)[0]);
        assertEquals(2, capped.get(1)[0]);
    }

    @Test
    void appendBatchFlushesMergedBlobAndClearRemovesAll(@TempDir Path dir) throws Exception {
        DictionaryCorpus corpus = new DictionaryCorpus(dir);
        int flushBytes = 64;
        assertNull(corpus.appendBatch(new byte[30], flushBytes));
        assertNull(corpus.appendBatch(new byte[30], flushBytes));
        byte[] blob = corpus.appendBatch(new byte[10], flushBytes); // 30+30+10=70 ≥ 64 → 满批
        assertNotNull(blob);
        assertEquals(70, blob.length);
        assertNull(corpus.appendBatch(new byte[5], flushBytes)); // 缓冲已清空重新累计

        corpus.writeSample(blob);
        assertEquals(70, corpus.totalBytes());
        corpus.clear();
        assertEquals(0, corpus.totalBytes());
    }

    @Test
    void writeFailureDisablesCollector(@TempDir Path base) throws Exception {
        // 目录位被同路径普通文件占用 → createDirectories 必败 → 自禁用且不抛
        Path blocking = base.resolve("corpus");
        Files.writeString(blocking, "not a directory");
        DictionaryCorpus corpus = new DictionaryCorpus(blocking);
        assertDoesNotThrow(() -> corpus.writeSample(new byte[]{1}));
        assertTrue(corpus.isDisabled());
    }

    // ===== DictionaryManager 采集门控 =====

    @Test
    void captureOnlyForTargetConnectionOncePerRotation() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("corpusConsumedGen", -1L);
        DictionaryManager.init(tempDir);
        try {
            // 存在激活字典是采集前置
            DictionaryManager.installAggregationSnapshot(
                    DictionarySnapshot.of(1, "dict".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            DictionaryCorpus corpus = getCollector();
            assertNotNull(corpus, "init 应创建语料库");

            Connection target = new Connection(PacketFlow.SERVERBOUND);
            Connection other = new Connection(PacketFlow.SERVERBOUND);
            setStatic("corpusTarget", target);
            setStatic("corpusTargetGen", 7L);

            DictionaryManager.collectCorpusSample(other, new byte[]{9});   // 非目标：忽略
            DictionaryManager.collectCorpusSample(target, new byte[]{1, 2, 3});
            DictionaryManager.collectCorpusSample(target, new byte[]{4}); // 同代已消费：忽略

            List<byte[]> snapshot = waitForSnapshot(corpus, 1, 2000);
            assertEquals(1, snapshot.size());
            assertArrayEquals(new byte[]{1, 2, 3}, snapshot.get(0));

            // 下一分钟（代次推进）后目标再次可采
            setStatic("corpusTargetGen", 8L);
            DictionaryManager.collectCorpusSample(target, new byte[]{5});
            snapshot = waitForSnapshot(corpus, 2, 2000);
            assertEquals(2, snapshot.size());
        } finally {
            DictionaryManager.resetServerSession();
        }
    }

    @Test
    void captureRequiresActiveDictionary() throws Exception {
        DictionaryManager.resetServerSession();
        setStatic("corpusConsumedGen", -1L);
        DictionaryManager.init(tempDir);
        try {
            // 无激活字典（首训前）：语料采集不工作（采样走 collectSample）
            DictionaryCorpus corpus = getCollector();
            Connection target = new Connection(PacketFlow.SERVERBOUND);
            setStatic("corpusTarget", target);
            setStatic("corpusTargetGen", 3L);
            DictionaryManager.collectCorpusSample(target, new byte[]{1});
            assertTrue(corpus.snapshotForTraining(10, Long.MAX_VALUE).isEmpty());
        } finally {
            DictionaryManager.resetServerSession();
        }
    }

    private static DictionaryCorpus getCollector() throws Exception {
        Field field = DictionaryManager.class.getDeclaredField("corpusCollector");
        field.setAccessible(true);
        return (DictionaryCorpus) field.get(null);
    }

    /** 写盘在调度器线程异步执行：轮询直到快照达到期望条数（或超时）。 */
    private static List<byte[]> waitForSnapshot(DictionaryCorpus corpus, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<byte[]> snapshot = List.of();
        while (System.currentTimeMillis() < deadline) {
            snapshot = corpus.snapshotForTraining(10, Long.MAX_VALUE);
            if (snapshot.size() >= expected) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        return snapshot;
    }
}
