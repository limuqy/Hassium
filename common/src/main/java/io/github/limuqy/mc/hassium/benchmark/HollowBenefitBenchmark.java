package io.github.limuqy.mc.hassium.benchmark;

import com.github.luben.zstd.Zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.SimpleBitStorage;

import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

/**
 * 服务端空心区块（hollow chunk）收益基准 demo。
 * <p>
 * 数据源：原版 .mca region 文件（storage.enabled=false 的服务端存档即此格式）。
 * 处理链与 docs/server-chunk-surface-shell.md §4 对齐：
 * <pre>
 *   .mca → chunk NBT → section palette+data → packet 格式编码（PalettedContainer 规则）
 *        → palette 级 dense 分类 → 六方向 section 图 BFS 壳层（shellDepth 可配）
 *        → full vs hollow 的 raw / ZSTD 字节对比
 * </pre>
 * 口径：仅 section 的 blockCount + blockStates 域。biomes / blockEntity / heightmaps /
 * light 为 hollow 变换两侧不变项，不计入（对收益比例无影响）。
 * <p>
 * 分类谓词：{@code isCollisionShapeFullBlock}（全版本稳定名，语义 = 碰撞形状完整方块，
 * 等价旧版 isOpaqueFullBlock/isSolidRender）+ {@code !hasBlockEntity()}。
 * properties 忽略（按 block 类型判定，与属性几乎无关；安全方向误判仅多保留）。
 * <p>
 * 编码规则（与 vanilla packet 一致，已按 1.21.11 源码核对）：
 * <ul>
 *   <li>n==1：bits=0，写单 id；</li>
 *   <li>1.21.9+ 本地模式（n≤16）统一 4 bits（LinearPalette），&lt;1.21.9 为 ceil(log2(n))；</li>
 *   <li>5-8 bits：HashMapPalette 本地列表（两版本一致）；</li>
 *   <li>&gt;8 bits：全局模式——1.21.9+ 不写 palette 表（GlobalPalette.write 为空），
 *       旧版写全 registry 表（demo 按 1.21.9+ 语义，实际世界 n&gt;256 罕见）。</li>
 * </ul>
 * NBT data 位宽与 packet data 位宽一致（pack 输出即内存/packet 布局），直接抄录。
 * <p>
 * 运行：{@code ./gradlew --no-daemon common:runJava -Pmc_ver=1.21.11
 * -PmainClass=io.github.limuqy.mc.hassium.benchmark.HollowBenefitBenchmark
 * -Pargs=<worldDir>,<maxShellDepth>}
 */
public final class HollowBenefitBenchmark {

    private static final int ZSTD_LEVEL = 3;

    /** 一个 section 的磁盘表示（已剥离 properties）。 */
    private record SectionData(int y, List<String> palette, long[] data) {}

    private record ChunkData(ChunkPos pos, List<SectionData> sections) {}

    /** palette 解析缓存（按方块名，跨 section/chunk 复用）。 */
    private static final Map<String, BlockState> STATE_CACHE = new HashMap<>();
    private static BlockState AIR_STATE;
    private static int registrySize;
    /** 解析异常计数。异常 section 保守视为非 dense。 */
    private static int decodeFailures;

    public static void main(String[] args) throws Exception {
        // 1.20.x 与 1.21.11（BuiltInRegistries 重新引入 bootstrap 检查）都需要先 bootstrap；
        // Bootstrap.bootStrap 幂等，全版本调用。
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        // 1.20.x 的 MappedRegistry <clinit 需要 bootstrap；bootstrap 后注册表就绪。
        // 1.21.11 的 BLOCK_STATE_REGISTRY 是 IdMapper（无 getValue），按名查用 BuiltInRegistries.BLOCK。
        // getValue(ResourceLocation) 仅 1.21.2+（Registry 接口更名）；1.20.x/1.21.1 用 get()。
#if MC_VER < MC_1_21_2
        AIR_STATE = BuiltInRegistries.BLOCK.get(ResourceLocationCompat.create("minecraft:air")).defaultBlockState();
#else
        AIR_STATE = BuiltInRegistries.BLOCK.getValue(ResourceLocationCompat.create("minecraft:air")).defaultBlockState();
#endif
        registrySize = Block.BLOCK_STATE_REGISTRY.size();

        String worldDir = args.length > 0 && !args[0].isEmpty()
                ? args[0]
                : "build/smoke-test/pregen-world/fabric-1.21.11/world";
        int maxShellDepth = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        System.out.println("=== Hassium Hollow Chunk Benefit Benchmark ===");
        System.out.println("world: " + worldDir);
        System.out.println("MC registry: " + registrySize + " block states");
        System.out.println();

        Map<ChunkPos, ChunkData> chunks = scanWorld(Path.of(worldDir));
        System.out.println("loaded chunks: " + chunks.size());
        System.out.println("口径: section blockCount+blockStates 域; biomes/BE/heightmaps/light 两侧相同不计入");
        System.out.println();

        // 1) 分类（palette 级，与生产 maybeHas/count 同构）
        Map<ChunkPos, boolean[]> denseMap = new HashMap<>();
        long totalSections = 0;
        long denseSections = 0;
        for (Map.Entry<ChunkPos, ChunkData> e : chunks.entrySet()) {
            boolean[] dense = classify(e.getValue());
            denseMap.put(e.getKey(), dense);
            for (boolean sectionDense : dense) {
                totalSections++;
                if (sectionDense) {
                    denseSections++;
                }
            }
        }
        System.out.printf("sections total: %d, dense: %d (%.1f%%), decodeFailures: %d%n",
                totalSections, denseSections, denseSections * 100.0 / Math.max(1, totalSections), decodeFailures);

        // 2) shellDepth sweep：BFS + 重组 + ZSTD
        long totalFullRaw = 0, totalFullZstd = 0;
        List<Map<Integer, int[]>> yCullPerDepth = new ArrayList<>();
        System.out.printf("%-10s | %12s | %12s | %8s | %12s | %12s | %8s | %9s%n",
                "shellDepth", "fullRaw", "hollowRaw", "rawSave%", "fullZstd", "hollowZstd", "zstdSave%", "culledSec");
        System.out.println("-".repeat(110));
        for (int depth = 0; depth <= maxShellDepth; depth++) {
            Map<ChunkPos, int[]> dist = bfsShell(denseMap, depth);
            long fullRaw = 0, hollowRaw = 0, fullZstd = 0, hollowZstd = 0;
            long culled = 0;
            Map<Integer, int[]> yCull = new TreeMap<>();
            for (Map.Entry<ChunkPos, ChunkData> e : chunks.entrySet()) {
                ChunkData cd = e.getValue();
                boolean[] dense = denseMap.get(cd.pos());
                int[] d = dist.get(cd.pos());
                byte[] full = encodeChunk(cd, null, null, depth);
                byte[] hollow = encodeChunk(cd, dense, d, depth);
                fullRaw += full.length;
                hollowRaw += hollow.length;
                fullZstd += Zstd.compress(full, ZSTD_LEVEL).length;
                hollowZstd += Zstd.compress(hollow, ZSTD_LEVEL).length;
                for (int i = 0; i < cd.sections().size(); i++) {
                    int y = cd.sections().get(i).y();
                    int[] v = yCull.computeIfAbsent(y, k -> new int[2]);
                    v[1]++;
                    if (dense[i] && d[i] > depth) {
                        culled++;
                        v[0]++;
                    }
                }
            }
            totalFullRaw = fullRaw;
            totalFullZstd = fullZstd;
            yCullPerDepth.add(yCull);
            System.out.printf("%-10d | %12d | %12d | %7.2f%% | %12d | %12d | %7.2f%% | %9d%n",
                    depth, fullRaw, hollowRaw, pct(fullRaw, hollowRaw),
                    fullZstd, hollowZstd, pct(fullZstd, hollowZstd), culled);
        }
        System.out.println();

        // 3) 按 Y 分布（最后一档 depth）
        Map<Integer, int[]> yCull = yCullPerDepth.get(yCullPerDepth.size() - 1);
        System.out.println("culled sections by Y (depth=" + maxShellDepth + "):");
        for (Map.Entry<Integer, int[]> e : yCull.entrySet()) {
            int y = e.getKey();
            int[] v = e.getValue();
            System.out.printf("  Y=%4d: %6d / %6d (%.1f%%)%n", y, v[0], v[1], v[0] * 100.0 / Math.max(1, v[1]));
        }
        System.out.println();
        System.out.printf("totals: fullRaw=%d fullZstd=%d%n", totalFullRaw, totalFullZstd);
    }

    // ------------------------------------------------------------------
    // region 扫描
    // ------------------------------------------------------------------

    private static Map<ChunkPos, ChunkData> scanWorld(Path worldDir) throws IOException {
        Path regionDir = worldDir.resolve("region");
        Map<ChunkPos, ChunkData> out = new HashMap<>();
        if (!Files.isDirectory(regionDir)) {
            throw new IOException("region 目录不存在: " + regionDir);
        }
        List<Path> regionFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path p : stream) {
                regionFiles.add(p);
            }
        }
        regionFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path regionFile : regionFiles) {
            try {
                scanRegionFile(regionFile, out);
            } catch (IOException e) {
                System.err.println("跳过无法读取的 region 文件: " + regionFile + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static void scanRegionFile(Path regionFile, Map<ChunkPos, ChunkData> out) throws IOException {
        String name = regionFile.getFileName().toString(); // r.X.Z.mca
        String[] parts = name.substring(2, name.length() - 4).split("\\.");
        int rx = Integer.parseInt(parts[0]);
        int rz = Integer.parseInt(parts[1]);
        byte[] header = new byte[8192];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (raf.length() < 8192) {
                return;
            }
            raf.readFully(header);
            ByteBuffer offsets = ByteBuffer.wrap(header, 0, 4096);
            try (FileChannel channel = raf.getChannel()) {
                for (int i = 0; i < 1024; i++) {
                    int entry = offsets.getInt(i * 4);
                    if (entry == 0) {
                        continue;
                    }
                    long fileOffset = (long) (entry >>> 8) * 4096;
                    if (fileOffset + 5 > raf.length()) {
                        continue;
                    }
                    ByteBuffer hb = ByteBuffer.allocate(5);
                    channel.read(hb, fileOffset);
                    hb.flip();
                    int length = hb.getInt();
                    byte type = hb.get();
                    if (length <= 1 || fileOffset + 5L + length - 1 > raf.length()) {
                        continue;
                    }
                    ByteBuffer pb = ByteBuffer.allocate(length - 1);
                    channel.read(pb, fileOffset + 5);
                    pb.flip();
                    byte[] payload = new byte[pb.remaining()];
                    pb.get(payload);
                    byte[] raw = decompressVanilla(type, payload);
                    if (raw == null || raw.length == 0) {
                        continue;
                    }
                    CompoundTag tag;
                    try {
                        tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)));
                    } catch (Exception ex) {
                        continue; // 数据版本/结构不匹配等，跳过
                    }
                    int cx = rx * 32 + (i & 31);
                    int cz = rz * 32 + (i >> 5);
                    List<SectionData> sections = parseSections(tag);
                    if (!sections.isEmpty()) {
                        ChunkPos p = new ChunkPos(cx, cz);
                        out.put(p, new ChunkData(p, sections));
                    }
                }
            }
        }
    }

    private static byte[] decompressVanilla(byte type, byte[] payload) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.length * 4);
            switch (type) {
                case 1 -> {
                    try (GZIPInputStream gz = new GZIPInputStream(bais)) {
                        gz.transferTo(baos);
                    }
                }
                case 2 -> {
                    try (InflaterInputStream zl = new InflaterInputStream(bais)) {
                        zl.transferTo(baos);
                    }
                }
                default -> {
                    return null;
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static List<SectionData> parseSections(CompoundTag chunkTag) {
        ListTag sections = getListTag(chunkTag, "sections");
        CompoundTag level = getCompoundOrNull(chunkTag, "Level");
        if (sections.isEmpty() && level != null) {
            sections = getListTag(level, "sections");
        }
        if (sections.isEmpty()) {
            return List.of();
        }
        TreeMap<Integer, SectionData> byY = new TreeMap<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag sec = listCompoundOrNull(sections, i);
            if (sec == null) {
                continue;
            }
            int y = getByte(sec, "Y");
            CompoundTag bs = getCompoundOrNull(sec, "block_states");
            if (bs == null) {
                continue; // 全空气 section：无 block_states，等价 canonical air，无需编码/分类
            }
            ListTag palette = getListTag(bs, "palette");
            List<String> names = new ArrayList<>(palette.size());
            for (int j = 0; j < palette.size(); j++) {
                CompoundTag palEntry = listCompoundOrNull(palette, j);
                String n = palEntry == null ? "minecraft:air" : getString(palEntry, "Name");
                int bracket = n.indexOf('[');
                names.add(bracket >= 0 ? n.substring(0, bracket) : n);
            }
            long[] data = getLongArray(bs, "data");
            byY.put(y, new SectionData(y, names, data));
        }
        return new ArrayList<>(byY.values());
    }

    // ---- NBT 读取兼容（1.21.5+ Optional API）----

    private static ListTag getListTag(CompoundTag t, String key) {
#if MC_VER < MC_1_21_5
        return t.getList(key, 10);
#else
        return t.getList(key).orElseGet(ListTag::new);
#endif
    }

    private static int getByte(CompoundTag t, String key) {
#if MC_VER < MC_1_21_5
        return t.getByte(key);
#else
        return t.getByteOr(key, (byte) 0);
#endif
    }

    private static CompoundTag getCompoundOrNull(CompoundTag t, String key) {
#if MC_VER < MC_1_21_5
        return t.contains(key, 10) ? t.getCompound(key) : null;
#else
        return t.getCompound(key).orElse(null);
#endif
    }

    private static CompoundTag listCompoundOrNull(ListTag l, int i) {
#if MC_VER < MC_1_21_5
        return l.getCompound(i);
#else
        return l.getCompound(i).orElse(null);
#endif
    }

    private static String getString(CompoundTag t, String key) {
#if MC_VER < MC_1_21_5
        return t.getString(key);
#else
        return t.getString(key).orElse("");
#endif
    }

    private static long[] getLongArray(CompoundTag t, String key) {
#if MC_VER < MC_1_21_5
        return t.getLongArray(key);
#else
        return t.getLongArray(key).orElse(new long[0]);
#endif
    }

    // ------------------------------------------------------------------
    // dense 分类（palette 级，与生产 maybeHas/count 同构）
    // ------------------------------------------------------------------

    private static boolean[] classify(ChunkData cd) {
        boolean[] dense = new boolean[cd.sections().size()];
        for (int i = 0; i < cd.sections().size(); i++) {
            dense[i] = classifySection(cd.sections().get(i));
        }
        return dense;
    }

    private static boolean classifySection(SectionData s) {
        int n = s.palette().size();
        if (n == 1) {
            return isSolidOpaque(state(s.palette().get(0)));
        }
        // palette 级拒绝：全部实心 → 无需解码 storage（等价 maybeHas=false 精确路径）
        boolean allSolid = true;
        for (String name : s.palette()) {
            if (!isSolidOpaque(state(name))) {
                allSolid = false;
                break;
            }
        }
        if (allSolid) {
            return true;
        }
        // 确认式：解码 storage 检查非实心条目是否真的出现（等价 count 精确路径）
        int bits = packedBits(n);
        if (s.data().length == 0) {
            return isSolidOpaque(state(s.palette().get(0))); // 无 data = 全 palette[0]
        }
        try {
            SimpleBitStorage storage = new SimpleBitStorage(bits, 4096, s.data());
            int[] values = new int[4096];
            storage.unpack(values);
            for (int idx : values) {
                if (idx < n && !isSolidOpaque(state(s.palette().get(idx)))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            decodeFailures++;
            return false; // 异常数据：保守非 dense
        }
    }

    /**
     * 磁盘/packet 编码位宽（1.20.1–1.21.11 实测一致）：
     * n≤16 → 4 bits（旧版 HashMapPalette 与新版 LinearPalette 磁盘均统一 4 位打包）；
     * 17-256 → ceil(log2(n))（5-8）；>256 → 全局 id 位宽（≈14）。
     */
    private static int packedBits(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 256) {
            return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        }
        return 32 - Integer.numberOfLeadingZeros(registrySize - 1);
    }

    private static boolean isSolidOpaque(BlockState s) {
        return s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !s.hasBlockEntity();
    }

    private static BlockState state(String name) {
        return STATE_CACHE.computeIfAbsent(name, n -> {
            try {
                Block b;
#if MC_VER < MC_1_21_2
                b = BuiltInRegistries.BLOCK.get(ResourceLocationCompat.create(n));
#else
                b = BuiltInRegistries.BLOCK.getValue(ResourceLocationCompat.create(n));
#endif
                if (b != null) {
                    return b.defaultBlockState();
                }
            } catch (Exception e) {
                // fall through
            }
            return AIR_STATE;
        });
    }

    // ------------------------------------------------------------------
    // BFS 壳层（六方向 section 图，深度 ≤ shellDepth）
    // ------------------------------------------------------------------

    private static Map<ChunkPos, int[]> bfsShell(Map<ChunkPos, boolean[]> denseMap, int depth) {
        Map<ChunkPos, int[]> dist = new HashMap<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>(); // (chunkX, chunkZ, y)
        // 源：非 dense section；邻 chunk 缺失的 chunk 整列为源（fail-open，文档 §3.3）
        for (Map.Entry<ChunkPos, boolean[]> e : denseMap.entrySet()) {
            ChunkPos p = e.getKey();
            boolean[] dense = e.getValue();
            boolean missingNeighbor = !denseMap.containsKey(new ChunkPos(p.x + 1, p.z))
                    || !denseMap.containsKey(new ChunkPos(p.x - 1, p.z))
                    || !denseMap.containsKey(new ChunkPos(p.x, p.z + 1))
                    || !denseMap.containsKey(new ChunkPos(p.x, p.z - 1));
            int[] d = new int[dense.length];
            Arrays.fill(d, -1);
            for (int y = 0; y < dense.length; y++) {
                if (!dense[y] || missingNeighbor) {
                    d[y] = 0;
                    queue.add(new long[]{p.x, p.z, y});
                }
            }
            dist.put(p, d);
        }
        // BFS：仅穿过 dense；额外标注壳层外第一层（depth + 1）供剔除判定。
        while (!queue.isEmpty()) {
            long[] node = queue.poll();
            int cx = (int) node[0], cz = (int) node[1], y = (int) node[2];
            int cur = dist.get(new ChunkPos(cx, cz))[y];
            if (cur > depth) {
                continue;
            }
            int[][] neighbors = {
                    {cx, cz, y - 1}, {cx, cz, y + 1},
                    {cx + 1, cz, y}, {cx - 1, cz, y}, {cx, cz + 1, y}, {cx, cz - 1, y},
            };
            for (int[] nb : neighbors) {
                int nx = nb[0], nz = nb[1], ny = nb[2];
                boolean[] nDense = denseMap.get(new ChunkPos(nx, nz));
                if (nDense == null) {
                    continue; // 缺失邻 chunk（其所在侧的当前 chunk 已按 fail-open 整列源处理）
                }
                if (ny < 0 || ny >= nDense.length) {
                    continue; // y 方向世界边界
                }
                if (!nDense[ny]) {
                    continue; // 非 dense 已是源
                }
                int[] nd = dist.get(new ChunkPos(nx, nz));
                if (nd[ny] == -1) {
                    nd[ny] = cur + 1;
                    queue.add(new long[]{nx, nz, ny});
                }
            }
        }
        return dist;
    }

    // ------------------------------------------------------------------
    // packet 编码（PalettedContainer 规则，NBT data 位宽与 packet 一致可直接抄）
    // ------------------------------------------------------------------

    /** 编码整 chunk：按 section 顺序拼接；hollow 时剔除 section 替换 canonical air。 */
    private static byte[] encodeChunk(ChunkData cd, boolean[] dense, int[] dist, int shellDepth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        for (int i = 0; i < cd.sections().size(); i++) {
            boolean culled = dense != null && dist != null && dense[i] && dist[i] > shellDepth;
            if (culled) {
                writeCanonicalAirSection(out);
            } else {
                encodeSection(out, cd.sections().get(i));
            }
        }
        return out.toByteArray();
    }

    private static void encodeSection(ByteArrayOutputStream out, SectionData s) {
        int blockCount = blockCount(s);
        out.write(blockCount >> 8);
        out.write(blockCount & 0xFF);
        encodeStates(out, s);
    }

    /** states PalettedContainer 编码（不含 blockCount 前缀）。 */
    private static void encodeStates(ByteArrayOutputStream out, SectionData s) {
        int n = s.palette().size();
        if (n == 1) {
            out.write(0); // bits = 0
            writeVarInt(out, stateId(s.palette().get(0)));
            return;
        }
        int bits = packedBits(n);
        out.write(bits);
        if (bits <= 8) {
            // 本地 palette（1-4 / 5-8）：写 size + id 列表（旧版 5-8 也是本地列表）
            writeVarInt(out, n);
            for (String name : s.palette()) {
                writeVarInt(out, stateId(name));
            }
        }
        // bits > 8：全局 palette，1.21.9+ 不写表；旧版写全 registry 表（demo 按 1.21.9+ 语义）
        writeLongs(out, s.data());
    }

    /** 非空气方块数：palette 全非 air → 4096；否则解码统计。 */
    private static int blockCount(SectionData s) {
        int n = s.palette().size();
        if (n == 1) {
            return state(s.palette().get(0)).isAir() ? 0 : 4096;
        }
        boolean allNonAir = true;
        for (String name : s.palette()) {
            if (state(name).isAir()) {
                allNonAir = false;
                break;
            }
        }
        if (allNonAir) {
            return 4096;
        }
        if (s.data().length == 0) {
            return state(s.palette().get(0)).isAir() ? 0 : 4096;
        }
        try {
            SimpleBitStorage storage = new SimpleBitStorage(packedBits(n), 4096, s.data());
            int[] values = new int[4096];
            storage.unpack(values);
            int air = 0;
            for (int idx : values) {
                if (idx < n && state(s.palette().get(idx)).isAir()) {
                    air++;
                }
            }
            return 4096 - air;
        } catch (Exception e) {
            return 4096;
        }
    }

    private static void writeCanonicalAirSection(ByteArrayOutputStream out) {
        out.write(0); // blockCount high
        out.write(0); // blockCount low
        out.write(0); // bits = 0
        writeVarInt(out, stateId("minecraft:air"));
    }

    private static int stateId(String name) {
        return Block.BLOCK_STATE_REGISTRY.getId(state(name));
    }

    private static void writeLongs(ByteArrayOutputStream out, long[] data) {
        writeVarInt(out, data.length);
        for (long v : data) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (v >>> shift));
            }
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static double pct(long full, long hollow) {
        return (full - hollow) * 100.0 / Math.max(1, full);
    }
}
