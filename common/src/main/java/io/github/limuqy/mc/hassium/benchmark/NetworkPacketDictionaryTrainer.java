package io.github.limuqy.mc.hassium.benchmark;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 网络包字典训练工具
 * <p>
 * 针对非区块数据包的 ZSTD 字典训练。
 * 网络包的特点：
 * 1. 包头结构固定（VarInt 长度前缀 + 包 ID）
 * 2. 包体内容多样（聊天、命令、玩家信息等）
 * 3. 重复模式较多（相同类型的包结构相似）
 * <p>
 * 训练策略：
 * 1. 从网络抓包文件提取样本（.pcap/.pcapng）
 * 2. 从服务器日志提取聊天/命令样本
 * 3. 生成模拟网络包样本
 */
public class NetworkPacketDictionaryTrainer {

    /**
     * 网络包类型枚举
     */
    public enum PacketType {
        // 客户端 -> 服务端
        C2S_CHAT("minecraft:chat"),
        C2S_COMMAND("minecraft:command"),
        C2S_MOVE("minecraft:move"),
        C2S_INTERACT("minecraft:interact"),
        C2S_INVENTORY("minecraft:inventory"),

        // 服务端 -> 客户端
        S2C_CHAT("minecraft:chat"),
        S2C_PLAYER_INFO("minecraft:player_info"),
        S2C_CHUNK_DATA("minecraft:chunk_data"),
        S2C_BLOCK_UPDATE("minecraft:block_update"),
        S2C_ENTITY_UPDATE("minecraft:entity_update"),
        S2C_INVENTORY("minecraft:inventory"),
        S2C_TITLE("minecraft:title"),
        S2C_SOUND("minecraft:sound");

        private final String id;

        PacketType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /**
     * 网络包字典训练参数
     */
    public record NetworkTrainingParams(
            int dictionarySize,
            int sampleSize,
            int sampleCount,
            Map<PacketType, Double> typeWeights
    ) {
        public static final NetworkTrainingParams DEFAULT = new NetworkTrainingParams(
                32768,   // 32KB 字典大小
                256,     // 256B 平均样本大小
                2000,    // 2000 个样本
                createDefaultWeights()
        );

        public static final NetworkTrainingParams COMPACT = new NetworkTrainingParams(
                16384,   // 16KB 字典大小
                128,     // 128B 平均样本大小
                1000,    // 1000 个样本
                createDefaultWeights()
        );

        private static Map<PacketType, Double> createDefaultWeights() {
            Map<PacketType, Double> weights = new EnumMap<>(PacketType.class);
            // 聊天和命令包权重较高（频繁发送）
            weights.put(PacketType.C2S_CHAT, 0.15);
            weights.put(PacketType.C2S_COMMAND, 0.10);
            weights.put(PacketType.C2S_MOVE, 0.25);  // 移动包最频繁
            weights.put(PacketType.C2S_INTERACT, 0.05);
            weights.put(PacketType.C2S_INVENTORY, 0.05);
            weights.put(PacketType.S2C_CHAT, 0.10);
            weights.put(PacketType.S2C_PLAYER_INFO, 0.05);
            weights.put(PacketType.S2C_CHUNK_DATA, 0.10);
            weights.put(PacketType.S2C_BLOCK_UPDATE, 0.05);
            weights.put(PacketType.S2C_ENTITY_UPDATE, 0.05);
            weights.put(PacketType.S2C_INVENTORY, 0.03);
            weights.put(PacketType.S2C_TITLE, 0.01);
            weights.put(PacketType.S2C_SOUND, 0.01);
            return weights;
        }
    }

    /**
     * 训练结果
     */
    public record NetworkTrainingResult(
            byte[] dictionary,
            int dictionarySize,
            int sampleCount,
            long trainingTimeMs,
            Map<PacketType, Integer> sampleCounts,
            double compressionRatioWithoutDict,
            double compressionRatioWithDict
    ) {
        public double improvementPercent() {
            return compressionRatioWithoutDict - compressionRatioWithDict;
        }

        public String toFormattedString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "网络包字典训练完成:%n" +
                            "  字典大小: %d bytes (%.1f KB)%n" +
                            "  样本数量: %d%n" +
                            "  训练时间: %d ms%n" +
                            "  无字典压缩率: %.2f%%%n" +
                            "  有字典压缩率: %.2f%%%n" +
                            "  压缩率提升: %.2f%%%n%n",
                    dictionarySize, dictionarySize / 1024.0,
                    sampleCount,
                    trainingTimeMs,
                    compressionRatioWithoutDict,
                    compressionRatioWithDict,
                    improvementPercent()
            ));

            sb.append("  样本分布:\n");
            for (Map.Entry<PacketType, Integer> entry : sampleCounts.entrySet()) {
                sb.append(String.format("    %-20s: %d 个\n", entry.getKey().name(), entry.getValue()));
            }

            return sb.toString();
        }
    }

    /**
     * 生成模拟网络包样本
     */
    public static byte[] generatePacketSample(PacketType type, int size, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[size];

        // 包头：VarInt 包 ID + VarInt 长度
        int headerSize = 4; // 简化的包头
        if (size <= headerSize) {
            return data;
        }

        // 根据包类型生成不同的内容
        switch (type) {
            case C2S_CHAT, S2C_CHAT -> generateChatPacket(data, headerSize, random);
            case C2S_COMMAND -> generateCommandPacket(data, headerSize, random);
            case C2S_MOVE -> generateMovePacket(data, headerSize, random);
            case C2S_INTERACT -> generateInteractPacket(data, headerSize, random);
            case C2S_INVENTORY, S2C_INVENTORY -> generateInventoryPacket(data, headerSize, random);
            case S2C_PLAYER_INFO -> generatePlayerInfoPacket(data, headerSize, random);
            case S2C_CHUNK_DATA -> generateChunkDataPacket(data, headerSize, random);
            case S2C_BLOCK_UPDATE -> generateBlockUpdatePacket(data, headerSize, random);
            case S2C_ENTITY_UPDATE -> generateEntityUpdatePacket(data, headerSize, random);
            case S2C_TITLE -> generateTitlePacket(data, headerSize, random);
            case S2C_SOUND -> generateSoundPacket(data, headerSize, random);
            default -> {
                // 默认随机数据
                random.nextBytes(data);
            }
        }

        return data;
    }

    /**
     * 生成聊天包样本
     */
    private static void generateChatPacket(byte[] data, int offset, Random random) {
        // 聊天消息格式：字符串 + 时间戳 + 签名
        String[] messages = {
                "Hello!", "GG", "gg", "brb", "ty", "np", "lol", "omg",
                "where is the diamond?", "anyone want to trade?",
                "I found diamonds!", "help me please", "join my team",
                "this server is great", "can someone help me build?"
        };
        String message = messages[random.nextInt(messages.length)];
        byte[] messageBytes = message.getBytes();

        // 写入消息
        int pos = offset;
        for (byte b : messageBytes) {
            if (pos < data.length) {
                data[pos++] = b;
            }
        }

        // 填充时间戳和签名
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成命令包样本
     */
    private static void generateCommandPacket(byte[] data, int offset, Random random) {
        String[] commands = {
                "/tp @p 0 64 0", "/give @p diamond 1", "/gamemode creative",
                "/time set day", "/weather clear", "/effect give @p speed",
                "/spawnpoint", "/setblock ~ ~ ~ stone", "/fill ~ ~ ~ ~10 ~10 ~10 air"
        };
        String command = commands[random.nextInt(commands.length)];
        byte[] commandBytes = command.getBytes();

        int pos = offset;
        for (byte b : commandBytes) {
            if (pos < data.length) {
                data[pos++] = b;
            }
        }

        // 填充参数
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成移动包样本
     */
    private static void generateMovePacket(byte[] data, int offset, Random random) {
        // 移动包格式：double x, y, z + float yaw, pitch + boolean onGround
        int pos = offset;

        // 坐标（8 字节 double）
        for (int i = 0; i < 3 && pos + 8 <= data.length; i++) {
            double coord = random.nextDouble() * 1000 - 500;
            long bits = Double.doubleToLongBits(coord);
            for (int j = 7; j >= 0; j--) {
                data[pos++] = (byte) (bits >> (j * 8));
            }
        }

        // 角度（4 字节 float）
        for (int i = 0; i < 2 && pos + 4 <= data.length; i++) {
            float angle = random.nextFloat() * 360 - 180;
            int bits = Float.floatToIntBits(angle);
            for (int j = 3; j >= 0; j--) {
                data[pos++] = (byte) (bits >> (j * 8));
            }
        }

        // onGround 标志
        if (pos < data.length) {
            data[pos] = (byte) (random.nextBoolean() ? 1 : 0);
        }
    }

    /**
     * 生成交互包样本
     */
    private static void generateInteractPacket(byte[] data, int offset, Random random) {
        // 交互包格式：entity ID + 交互类型 + 坐标
        int pos = offset;

        // Entity ID (VarInt)
        int entityId = random.nextInt(1000);
        while ((entityId & ~0x7F) != 0) {
            if (pos < data.length) {
                data[pos++] = (byte) ((entityId & 0x7F) | 0x80);
            }
            entityId >>>= 7;
        }
        if (pos < data.length) {
            data[pos++] = (byte) entityId;
        }

        // 交互类型
        if (pos < data.length) {
            data[pos++] = (byte) random.nextInt(3); // 0=interact, 1=attack, 2=interact_at
        }

        // 填充坐标
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成背包包样本
     */
    private static void generateInventoryPacket(byte[] data, int offset, Random random) {
        // 背包格式：window ID + slot count + 物品数据
        int pos = offset;

        // Window ID
        if (pos < data.length) {
            data[pos++] = (byte) random.nextInt(10);
        }

        // Slot count
        if (pos < data.length) {
            data[pos++] = (byte) (random.nextInt(36) + 9); // 9-45 slots
        }

        // 物品数据（简化）
        for (int i = pos; i < data.length; i++) {
            if (random.nextInt(10) < 3) {
                data[i] = 0; // 空物品
            } else {
                data[i] = (byte) (random.nextInt(100) + 1); // 物品 ID
            }
        }
    }

    /**
     * 生成玩家信息包样本
     */
    private static void generatePlayerInfoPacket(byte[] data, int offset, Random random) {
        // 玩家信息格式：UUID + 名字 + 属性列表
        int pos = offset;

        // UUID (16 bytes)
        for (int i = 0; i < 16 && pos < data.length; i++) {
            data[pos++] = (byte) random.nextInt(256);
        }

        // 玩家名字
        String[] names = {"Steve", "Alex", "Player", "Notch", "Herobrine"};
        String name = names[random.nextInt(names.length)];
        byte[] nameBytes = name.getBytes();
        for (byte b : nameBytes) {
            if (pos < data.length) {
                data[pos++] = b;
            }
        }

        // 属性列表
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成区块数据包样本
     */
    private static void generateChunkDataPacket(byte[] data, int offset, Random random) {
        // 区块数据格式：高度图 + 生物群系 + 区块数据
        int pos = offset;

        // 高度图（256 个 short）
        for (int i = 0; i < 256 && pos + 2 <= data.length; i++) {
            short height = (short) (random.nextInt(256) + 64);
            data[pos++] = (byte) (height >> 8);
            data[pos++] = (byte) height;
        }

        // 区块数据（方块 ID）
        for (int i = pos; i < data.length; i++) {
            if (random.nextInt(10) < 7) {
                data[i] = 0; // 空气
            } else {
                data[i] = (byte) (random.nextInt(50) + 1); // 常见方块
            }
        }
    }

    /**
     * 生成方块更新包样本
     */
    private static void generateBlockUpdatePacket(byte[] data, int offset, Random random) {
        // 方块更新格式：位置 + 方块状态
        int pos = offset;

        // 位置（VarInt 编码的 long）
        long position = ((long) random.nextInt(1000) << 38) |
                ((long) random.nextInt(256) << 26) |
                random.nextInt(1000);
        while ((position & ~0x7F) != 0) {
            if (pos < data.length) {
                data[pos++] = (byte) ((position & 0x7F) | 0x80);
            }
            position >>>= 7;
        }
        if (pos < data.length) {
            data[pos++] = (byte) position;
        }

        // 方块状态
        if (pos < data.length) {
            data[pos] = (byte) (random.nextInt(100) + 1);
        }
    }

    /**
     * 生成实体更新包样本
     */
    private static void generateEntityUpdatePacket(byte[] data, int offset, Random random) {
        // 实体更新格式：entity ID + 位置增量 + 角度
        int pos = offset;

        // Entity ID (VarInt)
        int entityId = random.nextInt(1000);
        while ((entityId & ~0x7F) != 0) {
            if (pos < data.length) {
                data[pos++] = (byte) ((entityId & 0x7F) | 0x80);
            }
            entityId >>>= 7;
        }
        if (pos < data.length) {
            data[pos++] = (byte) entityId;
        }

        // 位置增量（short）
        for (int i = 0; i < 3 && pos + 2 <= data.length; i++) {
            short delta = (short) (random.nextInt(256) - 128);
            data[pos++] = (byte) (delta >> 8);
            data[pos++] = (byte) delta;
        }

        // 角度（byte）
        if (pos < data.length) {
            data[pos] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成标题包样本
     */
    private static void generateTitlePacket(byte[] data, int offset, Random random) {
        // 标题格式：标题类型 + 文本 + 淡入/停留/淡出时间
        int pos = offset;

        // 标题类型
        if (pos < data.length) {
            data[pos++] = (byte) random.nextInt(5); // title, subtitle, actionbar, times, clear
        }

        // 文本
        String[] titles = {"Welcome!", "Achievement!", "Warning!", "Game Over!", "Level Up!"};
        String title = titles[random.nextInt(titles.length)];
        byte[] titleBytes = title.getBytes();
        for (byte b : titleBytes) {
            if (pos < data.length) {
                data[pos++] = b;
            }
        }

        // 时间参数
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成声音包样本
     */
    private static void generateSoundPacket(byte[] data, int offset, Random random) {
        // 声音格式：声音 ID + 位置 + 音量 + 音调
        int pos = offset;

        // 声音 ID (VarInt)
        int soundId = random.nextInt(500);
        while ((soundId & ~0x7F) != 0) {
            if (pos < data.length) {
                data[pos++] = (byte) ((soundId & 0x7F) | 0x80);
            }
            soundId >>>= 7;
        }
        if (pos < data.length) {
            data[pos++] = (byte) soundId;
        }

        // 位置（int * 8）
        for (int i = 0; i < 3 && pos + 4 <= data.length; i++) {
            int coord = random.nextInt(1000) * 8;
            for (int j = 3; j >= 0; j--) {
                data[pos++] = (byte) (coord >> (j * 8));
            }
        }

        // 音量和音调
        for (int i = pos; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
    }

    /**
     * 生成训练样本集
     */
    public static List<byte[]> generateTrainingSamples(NetworkTrainingParams params) {
        List<byte[]> samples = new ArrayList<>(params.sampleCount());
        Map<PacketType, Integer> sampleCounts = new EnumMap<>(PacketType.class);

        // 计算每个类型的样本数量
        int remaining = params.sampleCount();
        for (Map.Entry<PacketType, Double> entry : params.typeWeights().entrySet()) {
            int count = (int) (params.sampleCount() * entry.getValue());
            sampleCounts.put(entry.getKey(), count);
            remaining -= count;
        }

        // 将剩余样本分配给移动包（最频繁）
        sampleCounts.merge(PacketType.C2S_MOVE, remaining, Integer::sum);

        // 生成样本
        long seed = 12345L;
        for (Map.Entry<PacketType, Integer> entry : sampleCounts.entrySet()) {
            PacketType type = entry.getKey();
            int count = entry.getValue();

            for (int i = 0; i < count; i++) {
                // 样本大小有随机变化
                int size = params.sampleSize() + (int) (seed % 64) - 32;
                size = Math.max(32, Math.min(size, 1024));

                byte[] sample = generatePacketSample(type, size, seed++);
                samples.add(sample);
            }
        }

        // 打乱顺序
        Collections.shuffle(samples, new Random(42));

        return samples;
    }

    /**
     * 从网络抓包文件提取样本
     * <p>
     * 支持 .pcap 和 .pcapng 格式
     */
    public static List<byte[]> extractSamplesFromPcap(Path pcapFile, int maxSamples) throws IOException {
        List<byte[]> samples = new ArrayList<>();

        if (!Files.exists(pcapFile)) {
            throw new IOException("抓包文件不存在: " + pcapFile);
        }

        // 简化实现：读取文件内容作为样本
        // 实际实现需要解析 pcap 格式
        byte[] fileContent = Files.readAllBytes(pcapFile);

        // 将文件内容分割为多个样本
        int sampleSize = 256;
        for (int i = 0; i < fileContent.length && samples.size() < maxSamples; i += sampleSize) {
            int end = Math.min(i + sampleSize, fileContent.length);
            byte[] sample = Arrays.copyOfRange(fileContent, i, end);
            if (sample.length > 0) {
                samples.add(sample);
            }
        }

        return samples;
    }

    /**
     * 从服务器日志提取聊天/命令样本
     */
    public static List<byte[]> extractSamplesFromLog(Path logFile, int maxSamples) throws IOException {
        List<byte[]> samples = new ArrayList<>();

        if (!Files.exists(logFile)) {
            throw new IOException("日志文件不存在: " + logFile);
        }

        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null && samples.size() < maxSamples) {
                // 提取聊天消息
                if (line.contains("[CHAT]") || line.contains("[COMMAND]")) {
                    byte[] sample = line.getBytes();
                    if (sample.length > 10 && sample.length < 1024) {
                        samples.add(sample);
                    }
                }
            }
        }

        return samples;
    }

    /**
     * 训练网络包字典
     */
    public static NetworkTrainingResult trainDictionary(NetworkTrainingParams params) throws IOException {
        List<byte[]> samples = generateTrainingSamples(params);
        return trainDictionaryFromSamples(samples, params.dictionarySize(), params.typeWeights());
    }

    /**
     * 使用给定样本集训练字典
     */
    public static NetworkTrainingResult trainDictionaryFromSamples(
            List<byte[]> samples,
            int dictionarySize,
            Map<PacketType, Double> typeWeights
    ) throws IOException {
        System.out.println("开始训练网络包 ZSTD 字典...");
        System.out.printf("  字典大小: %d bytes (%.1f KB)%n", dictionarySize, dictionarySize / 1024.0);
        System.out.printf("  样本数量: %d%n", samples.size());

        long totalSampleSize = 0L;
        for (byte[] sample : samples) {
            totalSampleSize += sample.length;
        }

        long startTime = System.currentTimeMillis();
        ZstdDictTrainer trainer = new ZstdDictTrainer(
                (int) Math.min(totalSampleSize, Integer.MAX_VALUE),
                dictionarySize
        );
        for (byte[] sample : samples) {
            trainer.addSample(sample);
        }
        byte[] dictionary = trainer.trainSamples();
        long endTime = System.currentTimeMillis();

        System.out.printf("  字典训练完成，耗时 %d ms%n", endTime - startTime);

        // 验证字典效果
        System.out.println("验证字典效果...");
        double ratioWithoutDict = testCompressionWithoutDict(samples);
        double ratioWithDict = testCompressionWithDict(samples, dictionary);

        // 统计样本分布
        Map<PacketType, Integer> sampleCounts = new EnumMap<>(PacketType.class);
        for (PacketType type : typeWeights.keySet()) {
            sampleCounts.put(type, (int) (samples.size() * typeWeights.get(type)));
        }

        return new NetworkTrainingResult(
                dictionary,
                dictionary.length,
                samples.size(),
                endTime - startTime,
                sampleCounts,
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
     * 主入口
     */
    public static void main(String[] args) {
        try {
            NetworkTrainingParams params = NetworkTrainingParams.DEFAULT;
            Path outputPath = Path.of("hassium-network-dictionary.bin");
            Path pcapFile = null;
            Path logFile = null;
            int maxSamples = 5000;

            // 解析参数
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--compact":
                        params = NetworkTrainingParams.COMPACT;
                        break;
                    case "--output":
                        if (i + 1 < args.length) {
                            outputPath = Path.of(args[++i]);
                        }
                        break;
                    case "--pcap":
                        if (i + 1 < args.length) {
                            pcapFile = Path.of(args[++i]);
                        }
                        break;
                    case "--log":
                        if (i + 1 < args.length) {
                            logFile = Path.of(args[++i]);
                        }
                        break;
                    case "--samples":
                        if (i + 1 < args.length) {
                            maxSamples = Integer.parseInt(args[++i]);
                        }
                        break;
                    case "--dict-size":
                        if (i + 1 < args.length) {
                            int size = Integer.parseInt(args[++i]);
                            params = new NetworkTrainingParams(
                                    size,
                                    params.sampleSize(),
                                    params.sampleCount(),
                                    params.typeWeights()
                            );
                        }
                        break;
                    case "--help":
                        printUsage();
                        return;
                }
            }

            // 训练字典
            NetworkTrainingResult result;
            if (pcapFile != null) {
                System.out.println("从抓包文件提取样本: " + pcapFile);
                List<byte[]> samples = extractSamplesFromPcap(pcapFile, maxSamples);
                result = trainDictionaryFromSamples(samples, params.dictionarySize(), params.typeWeights());
            } else if (logFile != null) {
                System.out.println("从服务器日志提取样本: " + logFile);
                List<byte[]> samples = extractSamplesFromLog(logFile, maxSamples);
                result = trainDictionaryFromSamples(samples, params.dictionarySize(), params.typeWeights());
            } else {
                result = trainDictionary(params);
            }

            System.out.println();
            System.out.println(result.toFormattedString());

            // 保存字典
            saveDictionary(result.dictionary(), outputPath);

        } catch (Exception e) {
            System.err.println("网络包字典训练失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("用法: NetworkPacketDictionaryTrainer [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --pcap <file>           从抓包文件提取样本（.pcap/.pcapng）");
        System.out.println("  --log <file>            从服务器日志提取聊天/命令样本");
        System.out.println("  --samples <count>       最大样本数量（默认: 5000）");
        System.out.println("  --dict-size <size>      字典大小（默认: 32768）");
        System.out.println("  --compact               使用紧凑参数（16KB 字典，1000 样本）");
        System.out.println("  --output <path>         输出文件路径（默认: hassium-network-dictionary.bin）");
        System.out.println("  --help                  显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  # 使用模拟数据训练");
        System.out.println("  java NetworkPacketDictionaryTrainer");
        System.out.println();
        System.out.println("  # 从抓包文件训练");
        System.out.println("  java NetworkPacketDictionaryTrainer --pcap network.pcap");
        System.out.println();
        System.out.println("  # 从服务器日志训练");
        System.out.println("  java NetworkPacketDictionaryTrainer --log server.log");
    }
}
