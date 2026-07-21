package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.storage.HassiumRegionFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * 客户端缓存 → 原版 Anvil 世界导出器。
 * <p>
 * 把 {@code hassium_cache/<serverId>/<dim>/r.*.mca}（type 126 ZSTD + 3-sector header）
 * 转码为原版 Region 文件（type 2 zlib + 2-sector header），写入 {@code saves/<worldName>/}。
 * <p>
 * <b>输出结构</b>：
 * <ul>
 *   <li>{@code level.dat} + {@code level.dat_old}：最小可进世界脚手架</li>
 *   <li>{@code region/}：overworld 区块</li>
 *   <li>{@code DIM-1/region/}：the_nether</li>
 *   <li>{@code DIM1/region/}：the_end</li>
 *   <li>{@code dimensions/<ns>/<path>/region/}：其它维度</li>
 * </ul>
 * <p>
 * <b>限制</b>：无实体、无玩家背包；仅为「去过的区块」快照；首次单机打开会重算光照；模组方块需相同模组与相近 MC 版本。
 */
public final class CacheWorldExporter {

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final AtomicReference<Future<?>> RUNNING_TASK = new AtomicReference<>();

    private CacheWorldExporter() {}

    /** 进度回调（主线程调度）。 */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param done   已完成 region 数
         * @param total  总 region 数
         * @param message 状态消息
         */
        void onProgress(int done, int total, String message);
    }

    /**
     * 异步导出缓存为原版世界。
     *
     * @param worldName 输出世界名（{@code saves/<worldName>/}）
     * @param progress  进度回调（主线程）
     * @return true = 已启动；false = 已有任务在跑
     */
    public static boolean exportAsync(String worldName, ProgressCallback progress) {
        if (RUNNING_TASK.get() != null) {
            return false;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null) {
            return false;
        }
        Future<?> task = executor.submit(() -> {
            try {
                doExport(worldName, progress);
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: Cache export failed", t);
                notifyProgress(progress, -1, -1, "导出失败: " + t.getMessage());
            } finally {
                RUNNING_TASK.set(null);
            }
            return null;
        }, TaskCategory.MISSION_CRITICAL);
        RUNNING_TASK.set(task);
        return true;
    }

    /** 是否正在导出。 */
    public static boolean isRunning() {
        return RUNNING_TASK.get() != null;
    }

    private static void doExport(String worldName, ProgressCallback progress) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            notifyProgress(progress, -1, -1, "Minecraft 实例不可用");
            return;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            notifyProgress(progress, -1, -1, "未连接世界，无法读取注册表");
            return;
        }
        RegistryAccess registryAccess = level.registryAccess();
#if MC_VER < MC_1_21_2
        int minSection = level.getMinSection();
#else
        int minSection = level.getMinSectionY();
#endif
        Path gameDir = mc.gameDirectory.toPath();
        Path outputDir = gameDir.resolve("saves").resolve(worldName);
        Files.createDirectories(outputDir);

        notifyProgress(progress, 0, 1, "正在写入 level.dat...");
        writeLevelDat(outputDir, worldName);

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            notifyProgress(progress, 1, 1, "未连接服务器，无缓存可导出（仅 level.dat）");
            return;
        }

        Path cacheDimRoot = storage.getCacheRoot(); // .../hassium_cache/<serverId>/<dim>/
        Path serverRoot = cacheDimRoot != null ? cacheDimRoot.getParent() : null; // .../hassium_cache/<serverId>/
        if (serverRoot == null || !Files.exists(serverRoot)) {
            notifyProgress(progress, 1, 1, "缓存目录不存在: " + serverRoot);
            return;
        }

        // 枚举所有维度目录
        java.util.List<Path> dimDirs = new java.util.ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(serverRoot)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    dimDirs.add(p);
                }
            }
        }
        int total = dimDirs.size();
        int done = 0;
        int failedRegions = 0;
        int failedChunks = 0;

        for (Path dimDir : dimDirs) {
            String dimName = dimDir.getFileName().toString();
            Path regionOutDir = resolveRegionDir(outputDir, dimName);
            Files.createDirectories(regionOutDir);

            try (DirectoryStream<Path> regionFiles = Files.newDirectoryStream(dimDir, "r.*.mca")) {
                for (Path regionFile : regionFiles) {
                    try {
                        failedChunks += exportOneRegion(regionFile, regionOutDir, storage, registryAccess, minSection);
                    } catch (Throwable t) {
                        failedRegions++;
                        Constants.LOG.warn("Hassium: Failed to export region {}", regionFile, t);
                    }
                }
            }
            done++;
            notifyProgress(progress, done, total,
                    "已导出维度 " + dimName + " (" + done + "/" + total + ")");
        }

        String summary = "导出完成: " + outputDir + " (失败 region 数: " + failedRegions
                + ", 转换失败 chunk 数: " + failedChunks + ")";
        notifyProgress(progress, total, total, summary);
    }

    /** 把单个 Hassium region 文件转码为原版 region 文件。 */
    private static int exportOneRegion(Path hassiumRegionFile, Path outRegionDir,
                                       ClientHassiumStorage storage, RegistryAccess registryAccess,
                                       int minSection) throws IOException {
        int failedChunks = 0;
        Matcher m = REGION_FILE_PATTERN.matcher(hassiumRegionFile.getFileName().toString());
        if (!m.matches()) return 0;
        int regionX = Integer.parseInt(m.group(1));
        int regionZ = Integer.parseInt(m.group(2));

        Path outFile = outRegionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        try (HassiumRegionFile src = new HassiumRegionFile(hassiumRegionFile);
             VanillaRegionWriter dst = new VanillaRegionWriter(outFile)) {
            for (int i = 0; i < 1024; i++) {
                int cx = regionX * 32 + (i & 31);
                int cz = regionZ * 32 + (i >> 5);
                ChunkPos pos = new ChunkPos(cx, cz);
                if (!src.hasChunk(pos)) continue;
                try {
                    byte[] chunkData = src.readChunk(pos); // [type=126][ZSTD bytes]
                    if (chunkData == null || chunkData.length < 1 || chunkData[0] != (byte) 126) continue;
                    // 解压 ZSTD（用 storage 的字典实例）
                    byte[] compressed = new byte[chunkData.length - 1];
                    System.arraycopy(chunkData, 1, compressed, 0, compressed.length);
                    byte[] nbtBytes = storage.decompressForExport(compressed);
                    if (nbtBytes == null) continue;
                    CompoundTag cachedNbt = ChunkDiskCodec.bytesToNbt(nbtBytes);
                    if (cachedNbt == null) throw new VanillaChunkNbtCompat.ConversionException("invalid cache NBT");
                    CompoundTag vanillaNbt = VanillaChunkNbtCompat.convert(cachedNbt, registryAccess, minSection);
                    byte[] pureNbt = rawNbtBytes(vanillaNbt);
                    byte[] zlibData = zlibCompress(pureNbt);
                    dst.writeChunk(pos, zlibData);
                } catch (Throwable t) {
                    failedChunks++;
                    Constants.LOG.debug("Hassium: Skip chunk {} during export", pos, t);
                }
            }
        }
        return failedChunks;
    }

    /** Serialize raw, uncompressed NBT for Anvil's type-2 zlib payload. */
    private static byte[] rawNbtBytes(CompoundTag nbt) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.io.DataOutputStream data = new java.io.DataOutputStream(output)) {
            NbtIo.write(nbt, data);
        }
        return output.toByteArray();
    }

    /** 解析维度目录名 → 原版世界结构下的 region 目录。 */
    private static Path resolveRegionDir(Path outputDir, String dimName) {
        // dimName 是维度标识被 sanitize 后的字符串（如 minecraft_overworld）
        // 反推原始维度 ID
        String dimId = desanitizeDimension(dimName);
        if ("minecraft:overworld".equals(dimId) || "overworld".equals(dimName)) {
            return outputDir.resolve("region");
        } else if ("minecraft:the_nether".equals(dimId) || "the_nether".equals(dimName)
                || "DIM-1".equals(dimName)) {
            return outputDir.resolve("DIM-1").resolve("region");
        } else if ("minecraft:the_end".equals(dimId) || "the_end".equals(dimName)
                || "DIM1".equals(dimName)) {
            return outputDir.resolve("DIM1").resolve("region");
        } else {
            // 自定义维度：dimensions/<ns>/<path>/region/
            String[] parts = dimId.split(":", 2);
            if (parts.length == 2) {
                return outputDir.resolve("dimensions").resolve(parts[0]).resolve(parts[1]).resolve("region");
            }
            return outputDir.resolve(dimName).resolve("region");
        }
    }

    /** 反 sanitize 维度目录名（ClientChunkHandler.initStorage 把非字母数字替换为 _）。 */
    private static String desanitizeDimension(String sanitized) {
        // 简单还原：把 _ 还原为 :（仅对 minecraft_xxx 模式）
        if (sanitized.startsWith("minecraft_")) {
            return "minecraft:" + sanitized.substring("minecraft_".length());
        }
        return sanitized;
    }

    /** 写最小可进的 level.dat 脚手架。 */
    private static void writeLevelDat(Path outputDir, String worldName) throws IOException {
        CompoundTag data = new CompoundTag();
        CompoundTag levelData = new CompoundTag();
        levelData.putString("LevelName", worldName);
        levelData.putInt("GameType", 0); // SURVIVAL
        levelData.putInt("SpawnX", 0);
        levelData.putInt("SpawnY", 64);
        levelData.putInt("SpawnZ", 0);
        levelData.putString("generatorName", "default");
        levelData.putBoolean("allowCommands", true);
        levelData.putInt("DataVersion", getCurrentDataVersion());
        data.put("Data", levelData);

        Path levelDat = outputDir.resolve("level.dat");
        Path levelDatOld = outputDir.resolve("level.dat_old");
        writeNbtToFile(data, levelDat);
        writeNbtToFile(data, levelDatOld);
    }

    /** Writes one gzip-compressed NBT file in the vanilla level.dat format. */
    static void writeNbtToFile(CompoundTag nbt, Path path) throws IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            NbtIo.writeCompressed(nbt, output);
        }
    }

    /** 用 JDK Deflater 做 zlib 压缩（不依赖 VanillaZlibCodec 注册）。 */
    private static byte[] zlibCompress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length / 4);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                bos.write(buffer, 0, count);
            }
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** 获取当前客户端 DataVersion（跨版本兼容，反射兜底）。 */
    private static int getCurrentDataVersion() {
        try {
            Object version = net.minecraft.SharedConstants.getCurrentVersion();
            // version.getDataVersion().getVersion()
            java.lang.reflect.Method getDataVersion = version.getClass().getMethod("getDataVersion");
            Object dataVersion = getDataVersion.invoke(version);
            java.lang.reflect.Method getVersion = dataVersion.getClass().getMethod("getVersion");
            Object result = getVersion.invoke(dataVersion);
            if (result instanceof Integer i) return i;
            if (result instanceof Number n) return n.intValue();
        } catch (Throwable t) {
            Constants.LOG.debug("Hassium: Failed to get DataVersion reflectively", t);
        }
        return 0;
    }

    private static void notifyProgress(ProgressCallback cb, int done, int total, String message) {
        if (cb == null) return;
        MainThreadDispatcher.execute(() -> {
            try {
                cb.onProgress(done, total, message);
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: progress callback failed", t);
            }
        });
    }
}
