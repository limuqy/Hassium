package io.github.limuqy.mc.hassium.benchmark;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * ZSTD 字典训练工具
 * <p>
 * 支持两种样本来源：
 * 1. 真实世界数据：从 .mca Region 文件提取区块 NBT（推荐，效果最好）
 * 2. 模拟数据：用于无存档时的快速测试
 */
public class DictionaryTrainer {

    /**
     * 字典训练参数
     */
    public record TrainingParams(
            int dictionarySize,
            int sampleSize,
            int sampleCount
    ) {
        public static final TrainingParams DEFAULT = new TrainingParams(
                112640,  // 110KB 字典大小
                1024,    // 1KB 样本大小
                1000     // 1000 个样本
        );

        public static final TrainingParams COMPACT = new TrainingParams(
                32768,   // 32KB 字典大小
                512,     // 512B 样本大小
                500      // 500 个样本
        );
    }

    /**
     * 训练结果
     */
    public record TrainingResult(
            byte[] dictionary,
            int dictionarySize,
            int sampleCount,
            long trainingTimeMs,
            double compressionRatioWithoutDict,
            double compressionRatioWithDict
    ) {
        public double improvementPercent() {
            return compressionRatioWithoutDict - compressionRatioWithDict;
        }

        public String toFormattedString() {
            return String.format(
                    "字典训练完成:%n" +
                            "  字典大小: %d bytes (%.1f KB)%n" +
                            "  样本数量: %d%n" +
                            "  训练时间: %d ms%n" +
                            "  无字典压缩率: %.2f%%%n" +
                            "  有字典压缩率: %.2f%%%n" +
                            "  压缩率提升: %.2f%%",
                    dictionarySize, dictionarySize / 1024.0,
                    sampleCount,
                    trainingTimeMs,
                    compressionRatioWithoutDict,
                    compressionRatioWithDict,
                    improvementPercent()
            );
        }
    }

    /**
     * 生成模拟区块样本数据
     */
    public static byte[] generateChunkSample(int size, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[size];

        // 模拟区块数据的特征
        // 方块 ID（0-255，但集中在常见方块）
        for (int i = 0; i < size; i++) {
            if (i % 256 < 200) {
                // 80% 是常见方块
                data[i] = (byte) (random.nextInt(50) + 1);
            } else if (i % 256 < 240) {
                // 15% 是较少见方块
                data[i] = (byte) (random.nextInt(100) + 50);
            } else {
                // 5% 是其他数据（NBT、光照等）
                data[i] = (byte) (random.nextInt(256));
            }
        }

        return data;
    }

    /**
     * 生成训练样本集
     */
    public static List<byte[]> generateTrainingSamples(TrainingParams params) {
        List<byte[]> samples = new ArrayList<>(params.sampleCount());

        for (int i = 0; i < params.sampleCount(); i++) {
            byte[] sample = generateChunkSample(params.sampleSize(), i * 31L);
            samples.add(sample);
        }

        return samples;
    }

    /**
     * 从真实存档目录提取区块 NBT 样本
     * <p>
     * 扫描指定目录下的所有 .mca Region 文件，解压每个已写入的区块 payload，
     * 得到未压缩的原始 NBT 字节流作为字典训练样本。
     * <p>
     * <b>确定性保证</b>：region 文件按文件名排序后依次读取，每个 region 内按 chunk index 0-1023 顺序扫描。
     * 只要存档不变，相同参数的两次调用将返回相同的样本集（除非中途有区块被修改）。
     *
     * @param regionDir  存档 region 目录（例如 world/region 或 world/DIM-1/region）
     * @param maxSamples 最大样本数量，达到后停止扫描
     * @param seed       随机种子，若非 null 则对提取到的样本进行随机采样（用于多样性）
     * @return 提取到的区块 NBT 样本列表
     */
    public static List<byte[]> extractRealChunkSamples(Path regionDir, int maxSamples, Long seed) throws IOException {
        List<byte[]> samples = new ArrayList<>();

        if (!Files.isDirectory(regionDir)) {
            throw new IOException("Region 目录不存在: " + regionDir);
        }

        // 确定性：按文件名排序
        List<Path> regionFiles;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            List<Path> collected = new ArrayList<>();
            for (Path p : stream) {
                collected.add(p);
            }
            collected.sort(null); // 字典序排序
            regionFiles = collected;
        }

        // 顺序扫描所有 region 文件
        for (Path regionFile : regionFiles) {
            if (samples.size() >= maxSamples) {
                break;
            }
            try {
                extractSamplesFromRegionFile(regionFile, maxSamples - samples.size(), samples);
            } catch (IOException e) {
                System.err.println("跳过无法读取的 region 文件: " + regionFile + " (" + e.getMessage() + ")");
            }
        }

        // 可选：随机采样以增加多样性
        if (seed != null && samples.size() > maxSamples) {
            Random random = new Random(seed);
            List<byte[]> shuffled = new ArrayList<>(samples);
            for (int i = shuffled.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                byte[] temp = shuffled.get(i);
                shuffled.set(i, shuffled.get(j));
                shuffled.set(j, temp);
            }
            return shuffled.subList(0, maxSamples);
        }

        return samples;
    }

    /**
     * 从单个 .mca 文件提取区块 payload 并解压为原始 NBT 字节
     */
    private static void extractSamplesFromRegionFile(Path regionFile, int limit, List<byte[]> out) throws IOException {
        byte[] header = new byte[8192];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (raf.length() < 8192) {
                return;
            }
            raf.readFully(header);
            IntBuffer offsets = ByteBuffer.wrap(header, 0, 4096).asIntBuffer();

            try (FileChannel channel = raf.getChannel()) {
                for (int i = 0; i < 1024 && out.size() < limit; i++) {
                    int entry = offsets.get(i);
                    if (entry == 0) {
                        continue;
                    }

                    int sectorNumber = entry >>> 8;
                    long fileOffset = (long) sectorNumber * 4096;
                    if (fileOffset + 5 > raf.length()) {
                        continue;
                    }

                    ByteBuffer headerBuf = ByteBuffer.allocate(5);
                    channel.read(headerBuf, fileOffset);
                    headerBuf.flip();
                    if (headerBuf.remaining() < 5) {
                        continue;
                    }

                    int length = headerBuf.getInt();
                    byte compressionType = headerBuf.get();
                    if (length <= 1 || fileOffset + 5 + (long) (length - 1) > raf.length()) {
                        continue;
                    }

                    ByteBuffer payloadBuf = ByteBuffer.allocate(length - 1);
                    channel.read(payloadBuf, fileOffset + 5);
                    payloadBuf.flip();
                    byte[] payload = new byte[payloadBuf.remaining()];
                    payloadBuf.get(payload);

                    byte[] rawNbt = decodeVanillaPayload(compressionType, payload);
                    if (rawNbt != null && rawNbt.length > 0) {
                        out.add(rawNbt);
                    }
                }
            }
        }
    }

    /**
     * 解压原版 payload（type 1 = GZip, type 2 = Zlib）。
     * 未知或 Hassium 扩展（type 126 等）类型直接跳过，训练样本必须是未压缩 NBT。
     */
    private static byte[] decodeVanillaPayload(byte compressionType, byte[] payload) {
        try {
            InputStream in = switch (compressionType) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(payload));
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(payload));
                default -> null;
            };
            if (in == null) {
                return null;
            }
            try (InputStream stream = in; ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.length * 4)) {
                stream.transferTo(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 训练 ZSTD 字典（使用模拟样本）
     */
    public static TrainingResult trainDictionary(TrainingParams params) throws IOException {
        List<byte[]> sampleList = generateTrainingSamples(params);
        return trainDictionaryFromSamples(sampleList, params.dictionarySize());
    }

    /**
     * 维度采样权重配置
     */
    public record DimensionWeights(double overworld, double nether, double end) {
        public static final DimensionWeights BALANCED = new DimensionWeights(0.33, 0.33, 0.34);
        public static final DimensionWeights FREQUENCY_WEIGHTED = new DimensionWeights(0.60, 0.25, 0.15);
        public static final DimensionWeights OVERWORLD_FOCUSED = new DimensionWeights(0.70, 0.20, 0.10);

        public DimensionWeights {
            double sum = overworld + nether + end;
            if (Math.abs(sum - 1.0) > 0.01) {
                throw new IllegalArgumentException("权重总和必须为 1.0，当前为: " + sum);
            }
        }

        public int[] calculateSampleCounts(int totalSamples) {
            return new int[]{
                    (int) (totalSamples * overworld),
                    (int) (totalSamples * nether),
                    (int) (totalSamples * end)
            };
        }
    }

    /**
     * 从真实存档中提取样本并训练 ZSTD 字典（支持多维度加权采样）
     *
     * @param worldSaveDir   存档根目录（包含 region、DIM-1/region、DIM1/region）
     * @param dictionarySize 目标字典大小
     * @param maxSamples     最多提取的区块样本数
     * @param weights        维度采样权重（null 表示从单个 region 目录采样）
     */
    public static TrainingResult trainDictionaryFromWorld(
            Path worldSaveDir,
            int dictionarySize,
            int maxSamples,
            DimensionWeights weights
    ) throws IOException {
        System.out.println("从存档提取真实区块样本: " + worldSaveDir);

        List<byte[]> sampleList = new ArrayList<>();

        if (weights != null) {
            // 加权采样：按指定权重从三个维度提取
            int[] sampleCounts = weights.calculateSampleCounts(maxSamples);
            Path[] dimensions = {
                    worldSaveDir.resolve("region"),        // 主世界
                    worldSaveDir.resolve("DIM-1/region"),  // 下界
                    worldSaveDir.resolve("DIM1/region")    // 末地
            };
            String[] dimNames = {"主世界", "下界", "末地"};
            double[] weightValues = {weights.overworld(), weights.nether(), weights.end()};

            System.out.printf("  采样策略: 主世界 %.0f%%, 下界 %.0f%%, 末地 %.0f%%%n",
                    weights.overworld() * 100, weights.nether() * 100, weights.end() * 100);

            for (int i = 0; i < dimensions.length; i++) {
                Path dimDir = dimensions[i];
                if (!Files.isDirectory(dimDir)) {
                    System.out.printf("  跳过不存在的维度: %s (%s)%n", dimNames[i], dimDir);
                    continue;
                }
                System.out.printf("  从 %s 提取样本 (目标: %d, 权重: %.0f%%)...%n",
                        dimNames[i], sampleCounts[i], weightValues[i] * 100);
                List<byte[]> dimSamples = extractRealChunkSamples(dimDir, sampleCounts[i], null);
                System.out.printf("    实际提取: %d 个样本%n", dimSamples.size());
                sampleList.addAll(dimSamples);
            }
        } else {
            // 单一目录采样：从 region 目录直接提取
            Path regionDir = worldSaveDir.resolve("region");
            if (!Files.isDirectory(regionDir)) {
                regionDir = worldSaveDir; // 如果传入的就是 region 目录
            }
            sampleList = extractRealChunkSamples(regionDir, maxSamples, null);
        }

        if (sampleList.isEmpty()) {
            throw new IOException("未能从 " + worldSaveDir + " 提取到任何可用样本（可能全部为 Hassium 或未知压缩格式）");
        }
        System.out.printf("  总共提取到 %d 个真实区块样本%n", sampleList.size());
        return trainDictionaryFromSamples(sampleList, dictionarySize);
    }

    /**
     * 使用给定样本集训练 ZSTD 字典（真实 ZSTD 训练算法，通过 zstd-jni 的 ZstdDictTrainer）
     */
    public static TrainingResult trainDictionaryFromSamples(List<byte[]> sampleList, int dictionarySize) throws IOException {
        System.out.println("开始训练 ZSTD 字典...");
        System.out.printf("  字典大小: %d bytes (%.1f KB)%n", dictionarySize, dictionarySize / 1024.0);
        System.out.printf("  样本数量: %d%n", sampleList.size());

        long totalSampleSize = 0L;
        for (byte[] sample : sampleList) {
            totalSampleSize += sample.length;
        }

        long startTime = System.currentTimeMillis();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) Math.min(totalSampleSize, Integer.MAX_VALUE), dictionarySize);
        for (byte[] sample : sampleList) {
            trainer.addSample(sample);
        }
        byte[] dictionary = trainer.trainSamples();
        long endTime = System.currentTimeMillis();

        System.out.printf("  字典训练完成，耗时 %d ms%n", endTime - startTime);

        System.out.println("验证字典效果...");
        double ratioWithoutDict = testCompressionWithoutDict(sampleList);
        double ratioWithDict = testCompressionWithDict(sampleList, dictionary);

        return new TrainingResult(
                dictionary,
                dictionary.length,
                sampleList.size(),
                endTime - startTime,
                ratioWithoutDict,
                ratioWithDict
        );
    }

    /**
     * 测试无字典压缩率
     */
    private static double testCompressionWithoutDict(List<byte[]> samples) {
        long totalOriginal = 0;
        long totalCompressed = 0;

        for (byte[] sample : samples) {
            try {
                byte[] compressed = Zstd.compress(sample, 3);
                totalOriginal += sample.length;
                totalCompressed += compressed.length;
            } catch (Exception e) {
                // 忽略错误
            }
        }

        return (double) totalCompressed / totalOriginal * 100.0;
    }

    /**
     * 测试有字典压缩率
     */
    private static double testCompressionWithDict(List<byte[]> samples, byte[] dictionary) {
        long totalOriginal = 0;
        long totalCompressed = 0;

        for (byte[] sample : samples) {
            try {
                // 使用字典压缩
                byte[] compressed = Zstd.compressUsingDict(sample, dictionary, 3);
                if (compressed != null) {
                    totalOriginal += sample.length;
                    totalCompressed += compressed.length;
                }
            } catch (Exception e) {
                // 忽略错误
            }
        }

        return totalOriginal > 0 ? (double) totalCompressed / totalOriginal * 100.0 : 0.0;
    }

    /**
     * 保存字典到文件
     */
    public static void saveDictionary(byte[] dictionary, Path outputPath) throws IOException {
        Files.write(outputPath, dictionary);
        System.out.println("字典已保存到: " + outputPath);
    }

    /**
     * 加载字典
     */
    public static byte[] loadDictionary(Path dictionaryPath) throws IOException {
        if (!Files.exists(dictionaryPath)) {
            throw new IOException("字典文件不存在: " + dictionaryPath);
        }
        return Files.readAllBytes(dictionaryPath);
    }

    /**
     * 主入口
     */
    public static void main(String[] args) {
        try {
            TrainingParams params = TrainingParams.COMPACT;
            Path outputPath = Path.of("hassium-dictionary.bin");
            Path worldRegionDir = Path.of("D:\\MC\\HMCL\\.minecraft\\versions\\Fabulously Optimized 1.21.1\\saves\\大世界");
            int maxWorldSamples = 10000;
            DimensionWeights weights = DimensionWeights.FREQUENCY_WEIGHTED;

            // 解析参数
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--compact":
                        params = TrainingParams.COMPACT;
                        break;
                    case "--output":
                        if (i + 1 < args.length) {
                            outputPath = Path.of(args[++i]);
                        }
                        break;
                    case "--samples":
                        if (i + 1 < args.length) {
                            int count = Integer.parseInt(args[++i]);
                            params = new TrainingParams(params.dictionarySize(), params.sampleSize(), count);
                        }
                        break;
                    case "--dict-size":
                        if (i + 1 < args.length) {
                            int size = Integer.parseInt(args[++i]);
                            params = new TrainingParams(size, params.sampleSize(), params.sampleCount());
                        }
                        break;
                    case "--world":
                        if (i + 1 < args.length) {
                            worldRegionDir = Path.of(args[++i]);
                        }
                        break;
                    case "--max-world-samples":
                        if (i + 1 < args.length) {
                            maxWorldSamples = Integer.parseInt(args[++i]);
                        }
                        break;
                    case "--weights":
                        if (i + 1 < args.length) {
                            String preset = args[++i].toLowerCase();
                            weights = switch (preset) {
                                case "balanced" -> DimensionWeights.BALANCED;
                                case "frequency" -> DimensionWeights.FREQUENCY_WEIGHTED;
                                case "overworld" -> DimensionWeights.OVERWORLD_FOCUSED;
                                default -> throw new IllegalArgumentException("未知的权重预设: " + preset);
                            };
                        }
                        break;
                    case "--custom-weights":
                        if (i + 3 < args.length) {
                            double ow = Double.parseDouble(args[++i]);
                            double nether = Double.parseDouble(args[++i]);
                            double end = Double.parseDouble(args[++i]);
                            weights = new DimensionWeights(ow, nether, end);
                        }
                        break;
                    case "--help":
                        printUsage();
                        return;
                }
            }

            // 训练字典：优先使用真实存档数据
            TrainingResult result = worldRegionDir != null
                    ? trainDictionaryFromWorld(worldRegionDir, params.dictionarySize(), maxWorldSamples, weights)
                    : trainDictionary(params);
            System.out.println();
            System.out.println(result.toFormattedString());

            // 保存字典
            saveDictionary(result.dictionary(), outputPath);

        } catch (Exception e) {
            System.err.println("字典训练失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("用法: DictionaryTrainer [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --world <存档根目录>     使用真实存档区块数据训练（推荐）");
        System.out.println("                           需传入存档根目录（包含 region、DIM-1/region、DIM1/region）");
        System.out.println("  --max-world-samples <N>  最多提取的区块样本数（默认: 5000）");
        System.out.println("  --weights <preset>       维度采样权重预设（默认: balanced）");
        System.out.println("                           - balanced: 均衡采样，三维度各 33% (通用性最好)");
        System.out.println("                           - frequency: 频率加权，主世界 60%, 下界 25%, 末地 15%");
        System.out.println("                           - overworld: 主世界优先，主世界 70%, 下界 20%, 末地 10%");
        System.out.println("  --custom-weights <ow> <nether> <end>");
        System.out.println("                           自定义维度权重（三个小数，总和为 1.0）");
        System.out.println("                           例: --custom-weights 0.5 0.3 0.2");
        System.out.println("  --compact                使用紧凑参数（32KB 字典，500 样本，仅模拟数据模式）");
        System.out.println("  --output <path>          输出文件路径（默认: hassium-dictionary.bin）");
        System.out.println("  --samples <count>        模拟样本数量（默认: 1000，仅模拟数据模式）");
        System.out.println("  --dict-size <size>       字典大小（默认: 112640）");
        System.out.println("  --help                   显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  # 均衡采样（默认）");
        System.out.println("  java DictionaryTrainer --world \"D:\\\\MC\\\\saves\\\\MyWorld\" --max-world-samples 6000");
        System.out.println();
        System.out.println("  # 频率加权（优化常见场景）");
        System.out.println("  java DictionaryTrainer --world \"D:\\\\MC\\\\saves\\\\MyWorld\" --weights frequency");
        System.out.println();
        System.out.println("  # 自定义权重");
        System.out.println("  java DictionaryTrainer --world \"D:\\\\MC\\\\saves\\\\MyWorld\" --custom-weights 0.5 0.3 0.2");
    }
}
