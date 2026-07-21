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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.DictionaryRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * @param seed      世界种子
     * @param voidWorld 是否使用空岛生成器（防止区块污染）
     * @param progress  进度回调（主线程）
     * @return true = 已启动；false = 已有任务在跑
     */
    public static boolean exportAsync(long seed, boolean voidWorld, ProgressCallback progress) {
        if (RUNNING_TASK.get() != null) {
            return false;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null) {
            return false;
        }
        Future<?> task = executor.submit(() -> {
            try {
                doExport(seed, voidWorld, progress);
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

    /** 缓存服务器信息。 */
    public record ServerCacheInfo(String serverId, String displayName, Path cacheDir, List<String> dimensions) {}

    /**
     * 扫描本地缓存的服务器列表。
     *
     * @param gameDir 游戏根目录
     * @return 可用的缓存服务器列表
     */
    public static List<ServerCacheInfo> listCachedServers(Path gameDir) {
        List<ServerCacheInfo> result = new ArrayList<>();
        Path cacheRoot = gameDir.resolve("hassium_cache");
        if (!Files.isDirectory(cacheRoot)) return result;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheRoot)) {
            for (Path serverDir : ds) {
                if (!Files.isDirectory(serverDir)) continue;
                String serverId = serverDir.getFileName().toString();
                List<String> dims = new ArrayList<>();
                try (DirectoryStream<Path> dimStream = Files.newDirectoryStream(serverDir)) {
                    for (Path dimDir : dimStream) {
                        if (Files.isDirectory(dimDir)) {
                            dims.add(dimDir.getFileName().toString());
                        }
                    }
                }
                if (!dims.isEmpty()) {
                    result.add(new ServerCacheInfo(serverId, prettifyServerId(serverId), serverDir, dims));
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: Failed to scan cached servers", e);
        }
        return result;
    }

    /** server_127.0.0.1_25565 → 127.0.0.1:25565 */
    private static String prettifyServerId(String serverId) {
        if (serverId.startsWith("server_")) {
            String addr = serverId.substring("server_".length());
            // 端口部分：最后一个 _ 后面是数字则替换为 :
            int lastUnderscore = addr.lastIndexOf('_');
            if (lastUnderscore > 0 && lastUnderscore < addr.length() - 1) {
                String port = addr.substring(lastUnderscore + 1);
                if (port.chars().allMatch(Character::isDigit)) {
                    return addr.substring(0, lastUnderscore) + ":" + port;
                }
            }
            return addr;
        }
        return serverId;
    }

    /**
     * 离线导出指定服务器的缓存为原版世界（不依赖服务器连接）。
     *
     * @param serverCacheDir 服务器缓存目录（{@code hassium_cache/<serverId>}）
     * @param seed           世界种子
     * @param voidWorld      是否使用空岛生成器（防止区块污染）
     * @param progress       进度回调
     */
    public static void exportOffline(Path serverCacheDir, long seed, boolean voidWorld, ProgressCallback progress) {
        if (RUNNING_TASK.get() != null) {
            notifyProgress(progress, -1, -1, "已有导出任务在运行");
            return;
        }
        java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        RUNNING_TASK.set(future);
        Thread thread = new Thread(() -> {
            try {
                doExportFromDir(serverCacheDir, seed, voidWorld, progress);
                future.complete(null);
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: Offline cache export failed", t);
                notifyProgress(progress, -1, -1, "导出失败: " + t.getMessage());
                future.completeExceptionally(t);
            } finally {
                RUNNING_TASK.set(null);
            }
        }, "hassium-cache-export");
        thread.setDaemon(true);
        thread.start();
    }

    private static void doExport(long seed, boolean voidWorld, ProgressCallback progress) throws IOException {
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
        String worldName = "HassiumCache_" + System.currentTimeMillis();
        Path outputDir = gameDir.resolve("saves").resolve(worldName);

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            notifyProgress(progress, -1, -1, "未连接服务器，无缓存可导出");
            return;
        }

        Path cacheDimRoot = storage.getCacheRoot();
        Path serverRoot = cacheDimRoot != null ? cacheDimRoot.getParent() : null;
        if (serverRoot == null || !Files.exists(serverRoot)) {
            notifyProgress(progress, -1, -1, "缓存目录不存在: " + serverRoot);
            return;
        }

        doExportCore(serverRoot, outputDir, worldName, seed, voidWorld, registryAccess, minSection, progress);
    }

    private static void doExportFromDir(Path serverCacheDir, long seed, boolean voidWorld,
                                        ProgressCallback progress) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            notifyProgress(progress, -1, -1, "Minecraft 实例不可用");
            return;
        }
        // 需要当前世界的 RegistryAccess 来获取生物群系注册表
        if (mc.level == null) {
            notifyProgress(progress, -1, -1, "请先进入一个世界（单人或多人）再执行导出");
            return;
        }
        RegistryAccess registryAccess = mc.level.registryAccess();
        int minSection = -4; // 1.18+ 标准
        Path gameDir = mc.gameDirectory.toPath();
        String worldName = "HassiumCache_" + System.currentTimeMillis();
        Path outputDir = gameDir.resolve("saves").resolve(worldName);

        doExportCore(serverCacheDir, outputDir, worldName, seed, voidWorld, registryAccess, minSection, progress);
    }

    /** 核心导出逻辑（在线/离线共用）。 */
    private static void doExportCore(Path serverRoot, Path outputDir, String worldName,
                                     long seed, boolean voidWorld,
                                     RegistryAccess registryAccess, int minSection,
                                     ProgressCallback progress) throws IOException {
        Files.createDirectories(outputDir);

        notifyProgress(progress, 0, 1, "正在写入 level.dat...");
        writeLevelDat(outputDir, worldName, seed, voidWorld);

        List<Path> dimDirs = new ArrayList<>();
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
                        failedChunks += exportOneRegion(regionFile, regionOutDir, registryAccess, minSection);
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

        String summary;
        if (failedRegions == 0 && failedChunks == 0) {
            summary = "§a导出完成: " + outputDir;
        } else {
            StringBuilder sb = new StringBuilder("§a导出完成: ").append(outputDir).append("\n§e⚠ 跳过 ");
            if (failedRegions > 0 && failedChunks > 0) {
                sb.append(failedRegions).append(" 个 region、").append(failedChunks).append(" 个 chunk");
            } else if (failedRegions > 0) {
                sb.append(failedRegions).append(" 个 region");
            } else {
                sb.append(failedChunks).append(" 个 chunk");
            }
            summary = sb.append("§r").toString();
        }
        notifyProgress(progress, total, total, summary);
    }

    /** 把单个 Hassium region 文件转码为原版 region 文件。 */
    private static int exportOneRegion(Path hassiumRegionFile, Path outRegionDir,
                                       RegistryAccess registryAccess, int minSection) throws IOException {
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
                    byte[] compressed = new byte[chunkData.length - 1];
                    System.arraycopy(chunkData, 1, compressed, 0, compressed.length);
                    byte[] nbtBytes = decompressForExport(compressed);
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

    /** 使用内置 ZSTD 字典解压缓存数据。 */
    private static byte[] decompressForExport(byte[] compressedData) {
        CompressionService service = CompressionService.getInstance();
        DictionaryRegistry registry = service.getDictionaryRegistry().orElse(null);
        if (registry == null) return null;

        byte[] dictionary = registry.findDictionary(Constants.DEFAULT_ZSTD_DICTIONARY_ID).orElse(null);
        if (dictionary == null) return null;

        com.github.luben.zstd.ZstdDictDecompress dict = new com.github.luben.zstd.ZstdDictDecompress(dictionary);
        int decompressedSize = (int) com.github.luben.zstd.Zstd.decompressedSize(compressedData);
        if (decompressedSize <= 0) {
            decompressedSize = compressedData.length * 4;
        }
        byte[] result = new byte[decompressedSize];
        long actualSize = com.github.luben.zstd.Zstd.decompressFastDict(result, 0, compressedData, 0, compressedData.length, dict);
        if (actualSize < 0) return null;
        if (actualSize == result.length) return result;
        byte[] trimmed = new byte[(int) actualSize];
        System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
        return trimmed;
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
    private static void writeLevelDat(Path outputDir, String worldName, long seed, boolean voidWorld) throws IOException {
        int dataVersion = getCurrentDataVersion();
        CompoundTag data = new CompoundTag();
        CompoundTag levelData = new CompoundTag();
        levelData.putString("LevelName", worldName);
        levelData.putInt("GameType", 0); // SURVIVAL
        levelData.putInt("SpawnX", 0);
        levelData.putInt("SpawnY", 64);
        levelData.putInt("SpawnZ", 0);
        levelData.putBoolean("allowCommands", true);
        levelData.putInt("DataVersion", dataVersion);
        levelData.putBoolean("WasModded", true);
        levelData.putInt("version", 19133); // Anvil 格式标识

        // Version 信息
        CompoundTag version = new CompoundTag();
        version.putInt("Id", dataVersion);
        version.putString("Name", net.minecraft.SharedConstants.getCurrentVersion().getName());
        version.putBoolean("Snapshot", false);
        levelData.put("Version", version);

        // 世界生成设置
        CompoundTag worldGenSettings = new CompoundTag();
        worldGenSettings.putBoolean("bonus_chest", false);
        worldGenSettings.putLong("seed", seed);
        worldGenSettings.putBoolean("generate_features", !voidWorld); // 空岛不生成特征
        CompoundTag dimensions = new CompoundTag();

        // overworld
        CompoundTag overworld = new CompoundTag();
        overworld.putString("type", "minecraft:overworld");
        CompoundTag overworldGen = new CompoundTag();

        if (voidWorld) {
            // 空岛生成器：使用 flat 类型 + the_void 生物群系
            overworldGen.putString("type", "minecraft:flat");
            CompoundTag settings = new CompoundTag();
            settings.putByte("features", (byte) 1);
            settings.putString("biome", "minecraft:the_void");
            ListTag layers = new ListTag();
            CompoundTag airLayer = new CompoundTag();
            airLayer.putString("block", "minecraft:air");
            airLayer.putInt("height", 1);
            layers.add(airLayer);
            settings.put("layers", layers);
            settings.put("structure_overrides", new ListTag());
            settings.putByte("lakes", (byte) 0);
            overworldGen.put("settings", settings);
        } else {
            // 默认生成器
            overworldGen.putString("type", "minecraft:noise");
            overworldGen.putString("settings", "minecraft:overworld");
            CompoundTag overworldBiome = new CompoundTag();
            overworldBiome.putString("type", "minecraft:multi_noise");
            overworldBiome.putString("preset", "minecraft:overworld");
            overworldGen.put("biome_source", overworldBiome);
        }

        overworld.put("generator", overworldGen);
        dimensions.put("minecraft:overworld", overworld);

        // the_nether — multi_noise 生物群系（使用相同 seed）
        CompoundTag nether = new CompoundTag();
        nether.putString("type", "minecraft:the_nether");
        CompoundTag netherGen = new CompoundTag();
        netherGen.putString("type", "minecraft:noise");
        netherGen.putString("settings", "minecraft:nether");
        CompoundTag netherBiome = new CompoundTag();
        netherBiome.putString("type", "minecraft:multi_noise");
        netherBiome.putString("preset", "minecraft:nether");
        netherGen.put("biome_source", netherBiome);
        nether.put("generator", netherGen);
        dimensions.put("minecraft:the_nether", nether);

        // the_end — the_end 生物群系（使用相同 seed）
        CompoundTag theEnd = new CompoundTag();
        theEnd.putString("type", "minecraft:the_end");
        CompoundTag endGen = new CompoundTag();
        endGen.putString("type", "minecraft:noise");
        endGen.putString("settings", "minecraft:end");
        CompoundTag endBiome = new CompoundTag();
        endBiome.putString("type", "minecraft:the_end");
        endGen.put("biome_source", endBiome);
        theEnd.put("generator", endGen);
        dimensions.put("minecraft:the_end", theEnd);

        worldGenSettings.put("dimensions", dimensions);
        levelData.put("WorldGenSettings", worldGenSettings);

        // 空玩家数据（单人世界需要）
        CompoundTag player = new CompoundTag();
        player.putInt("DataVersion", dataVersion);
        player.putByte("OnGround", (byte) 1);
        player.putByte("Sleeping", (byte) 0);
        player.putShort("Air", (short) 300);
        player.putShort("Fire", (short) -20);
        player.putShort("HurtTime", (short) 0);
        player.putShort("DeathTime", (short) 0);
        player.putShort("AttackTime", (short) 0);
        player.putInt("playerGameType", 0);
        player.putInt("XpLevel", 0);
        player.putFloat("XpP", 0.0f);
        player.putInt("XpTotal", 0);
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(0));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(64));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(0));
        player.put("Pos", pos);
        ListTag motion = new ListTag();
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0));
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0));
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0));
        player.put("Motion", motion);
        ListTag rotation = new ListTag();
        rotation.add(net.minecraft.nbt.FloatTag.valueOf(0));
        rotation.add(net.minecraft.nbt.FloatTag.valueOf(0));
        player.put("Rotation", rotation);
        CompoundTag abilities = new CompoundTag();
        abilities.putByte("invulnerable", (byte) 0);
        abilities.putByte("mayfly", (byte) 0);
        abilities.putByte("instabuild", (byte) 0);
        abilities.putByte("mayBuild", (byte) 1);
        abilities.putByte("flying", (byte) 0);
        abilities.putFloat("walkSpeed", 0.1f);
        abilities.putFloat("flySpeed", 0.05f);
        player.put("abilities", abilities);
        player.putString("Dimension", "minecraft:overworld");
        levelData.put("Player", player);

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
