package io.github.limuqy.mc.hassium.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 字典更新语料库（落盘侧）。
 * <p>
 * 存在激活字典后，每分钟随机取一个玩家的下一帧聚合包（<b>压缩前</b>原始字节——训练语料
 * 必须是未压缩帧，与 {@code collectSample} 同源）写盘，作为每日重训练语料；采集点设计
 * 参照 {@link AggregatedPacketExporter}：热路径只做引用比较，写盘在后台调度器执行，
 * 写盘失败自禁用（warn 一次），绝不影响发包路径。
 * <p>
 * 目录：{@code <serverRunDir>/config/hassium/aggregation_corpus/}；文件名
 * {@code agg-<yyyyMMdd-HHmmss-SSS>.bin} 字典序即时间序。保留上限（文件数 / 总字节）
 * 超出按最旧删除。{@code retrain.marker} 记录最近一次字典训练完成时刻（毫秒），
 * 供跨重启推算下一次每日重训练时间。
 */
final class DictionaryCorpus {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DictionaryCorpus");

    /** 保留文件数上限（~1.5 天；每分钟 1 帧 = 1440/天）。 */
    static final int MAX_FILES = 2160;
    /** 语料总字节上限（超出按最旧删除）。 */
    static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    /** 单帧采集上限：超大帧不采集（对齐训练样本量级）。 */
    static final int MAX_FRAME_BYTES = 256 * 1024;
    static final String MARKER_FILE = "retrain.marker";

    private static final DateTimeFormatter NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path dir;
    private volatile boolean disabled;

    /** 冒烟模式攒批缓冲（{@code appendBatch}：少文件多字节，尽快凑够最低数据集） */
    private final List<byte[]> batchBuf = new ArrayList<>();
    private int batchBytes;

    DictionaryCorpus(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    boolean isDisabled() {
        return disabled;
    }

    /**
     * 冒烟模式采集：帧进内存攒批，攒满 {@code flushBytes} 返回一个合并 blob（多次调用
     * 返回 null 表示未满）。blob 作为单个语料文件落盘——语料样本是字节流，多帧合并不影响
     * 训练语义；这样冒烟几分钟窗口内就能凑够最低数据集，且不产生每秒上百个小文件。
     * 返回非 null 时由调用方异步 {@link #writeSample}（本方法只在冲刷线程执行，拷贝廉价）。
     */
    synchronized byte[] appendBatch(byte[] frame, int flushBytes) {
        batchBuf.add(frame);
        batchBytes += frame.length;
        if (batchBytes < flushBytes) {
            return null;
        }
        byte[] blob = new byte[batchBytes];
        int pos = 0;
        for (byte[] part : batchBuf) {
            System.arraycopy(part, 0, blob, pos, part.length);
            pos += part.length;
        }
        batchBuf.clear();
        batchBytes = 0;
        return blob;
    }

    /** 语料现有总字节数（文件面；触发判断用）。 */
    long totalBytes() {
        try {
            long total = 0;
            for (Path file : listSamples()) {
                total += Files.size(file);
            }
            return total;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 语料现有样本（文件）数（触发判断用）。 */
    int sampleCount() {
        try {
            return listSamples().size();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 清空语料（重训练消费后调用，防无限增长）；marker 保留。内存攒批一并丢弃。 */
    void clear() {
        synchronized (this) {
            batchBuf.clear();
            batchBytes = 0;
        }
        try {
            for (Path file : listSamples()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOGGER.debug("Hassium: dictionary corpus clear failed", e);
        }
    }

    /**
     * 落盘一帧语料（后台调度线程调用；一次一帧/分钟）。写失败自禁用。
     */
    void writeSample(byte[] rawFrame) {
        if (disabled) {
            return;
        }
        try {
            Files.createDirectories(dir);
            String name = "agg-" + NAME_FORMAT.format(LocalDateTime.now()) + ".bin";
            Path tmp = dir.resolve(name + ".tmp");
            Files.write(tmp, rawFrame);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            prune(MAX_FILES, MAX_TOTAL_BYTES);
        } catch (Exception e) {
            disabled = true;
            LOGGER.warn("Hassium: dictionary corpus write failed, corpus sampling disabled for this session", e);
        }
    }

    /**
     * 保留上限裁剪：超出文件数 / 总字节上限时按最旧删除（文件名字典序 = 时间序）。
     * 包私有参数化供测试。
     */
    void prune(int maxFiles, long maxTotalBytes) throws IOException {
        List<Path> files = listSamples();
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        int index = 0; // files 为最旧在前
        while (files.size() - index > maxFiles || total > maxTotalBytes) {
            Path oldest = files.get(index++);
            long size = Files.size(oldest);
            Files.deleteIfExists(oldest);
            total -= size;
        }
    }

    /**
     * 重训练语料快照：按时间<b>新→旧</b>读取，直到样本数 / 总字节上限（语料是滑动窗口，
     * 新样本更能代表当前 mod 组合的包型）。
     */
    List<byte[]> snapshotForTraining(int maxSamples, long maxBytes) {
        List<byte[]> out = new ArrayList<>();
        try {
            List<Path> files = listSamples();
            long total = 0;
            for (int i = files.size() - 1; i >= 0 && out.size() < maxSamples && total < maxBytes; i--) {
                try {
                    byte[] bytes = Files.readAllBytes(files.get(i));
                    if (bytes.length == 0) {
                        continue;
                    }
                    if (total + bytes.length > maxBytes && !out.isEmpty()) {
                        break;
                    }
                    out.add(bytes);
                    total += bytes.length;
                } catch (IOException e) {
                    LOGGER.debug("Hassium: dictionary corpus sample unreadable, skipping: {}", files.get(i), e);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Hassium: dictionary corpus directory unreadable", e);
        }
        return out;
    }

    /** 最近一次字典训练完成时刻（毫秒）；无 marker 返回 0。 */
    long readMarker() {
        try {
            Path marker = dir.resolve(MARKER_FILE);
            if (Files.exists(marker)) {
                return Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception e) {
            LOGGER.debug("Hassium: dictionary retrain marker unreadable, treating as unset", e);
        }
        return 0L;
    }

    /** 记录最近一次字典训练完成时刻（毫秒）。 */
    void writeMarker(long millis) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(MARKER_FILE), Long.toString(millis), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("Hassium: failed to write dictionary retrain marker", e);
        }
    }

    private List<Path> listSamples() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .toList());
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return files;
        }
    }
}
