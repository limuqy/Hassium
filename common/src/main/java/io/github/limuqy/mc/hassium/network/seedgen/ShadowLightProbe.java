package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.Base64;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * T3 影子光照残差探针（loader-parity 报告 §5 遗留项）。
 * <p>
 * 背景：影子光照收敛终值稳定 E=3，同 seed vanilla 真值 E=5，差 2 级。静态排查已排除
 * clearChunkLight 填充陷阱、引擎排空时序、getChunkForLighting fallback；剩余嫌疑集中在
 * 剥光包 decode 后方块状态保真 / {@code isFromEmptyShape} 边缘行为。本类在影子链路四个
 * 关键环节打点，一轮冒烟即可逐方块对比影子侧内部状态：
 * <ol>
 *   <li>{@link #onInjected}：剥光包 decode（{@code replaceWithPacketData}）后目标区块的
 *       方块状态快照 + WORLD_SURFACE 高度图——「decode 保真」直接证据</li>
 *   <li>{@link #onBeforeLightChunk}：{@code lightChunk} 提交前引擎输入面——本柱/东邻全
 *       section 空-实剖面、四向边界平面非空气方块、双方高度图。这些正是 vanilla
 *       {@code SkyLightEngine.isFromEmptyShape} 的判定输入面（该方法是版本敏感的
 *       vanilla 内部方法，不直接 mixin 拦截；此处暴露等价可观测输入）</li>
 *   <li>{@link #onLightChunkComplete}：{@code lightChunk} 完成后引擎对该 section 的
 *       sky/block 数据层（base64 全量 + 东缘列人读网格）——引擎输出</li>
 *   <li>{@link #onReturnPacket}：回传包内该 section 的 sky/block 字节数组——实际下发值</li>
 * </ol>
 * <p>
 * 门控：{@code debug.lightVerify}（CLIENT scope，默认 false）。关闭时每个钩子第一行即
 * 返回（一次配置读），零开销；开启后仅命中探针柱 SectionPos(-13,5,3)→区块(-13,3) 时输出，
 * 日志关键字统一前缀 {@code [SHADOW_LIGHT_PROBE]}。所有钩子吞 Throwable——诊断路径
 * 绝不允许影响影子管线。
 */
public final class ShadowLightProbe {

    private static final String PREFIX = "[SHADOW_LIGHT_PROBE] ";

    /** 探针区块（报告 §5：收敛终值 W=15 E=3 N=14 S=0；vanilla 真值 E=5）。 */
    static final int CHUNK_X = -13;
    static final int CHUNK_Z = 3;
    /** 探针 section（绝对坐标）。 */
    static final int SECTION_X = -13;
    static final int SECTION_Y = 5;
    static final int SECTION_Z = 3;

    private ShadowLightProbe() {}

    /** 门控：debug.lightVerify（CLIENT，默认 false）。 */
    public static boolean enabled() {
        try {
            return HassiumConfigService.getInstance().isLightVerifyEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isProbeChunk(ChunkPos pos) {
        return pos != null && pos.x == CHUNK_X && pos.z == CHUNK_Z;
    }


    /**
     * Hook 1：剥光包 decode 后（ShadowSeedServer.injectChunk，含重试收敛后的最终 chunk）。
     * 输出：目标 section（y=5）逐 y 平面的非空气方块清单 + 全柱 section 空-实剖面 +
     * WORLD_SURFACE 高度图 16×16 行主序。
     */
    public static void onInjected(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (!enabled() || !isProbeChunk(pos) || chunk == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(PREFIX).append("DECODE dim=").append(dimension).append(" pos=(")
                    .append(pos.x).append(',').append(pos.z).append(")\n");
            appendSectionProfile(sb, "DECODE", chunk);
            appendHeightmap(sb, "DECODE", chunk);
            appendSectionDump(sb, "DECODE-SECTION", chunk, SECTION_Y);
            Constants.LOG.info("{}", sb);
        } catch (Throwable t) {
            warnHookFailure("onInjected", t);
        }
    }

    /**
     * Hook 2：lightChunk 提交前（ShadowLightCompute.submitLightChunkLocked）。
     * 输出引擎输入面：本柱与四邻的注入状态、空-实剖面、四向边界平面的非空气方块
     * （isFromEmptyShape 边缘行为的决定面）、本柱与东邻高度图。
     */
    public static void onBeforeLightChunk(ShadowSeedServer server, LevelLightEngine engine, LevelChunk chunk) {
        if (!enabled() || !isProbeChunk(chunk.getPos()) || server == null || engine == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(PREFIX).append("LIGHTCHUNK-IN pos=(").append(CHUNK_X).append(',').append(CHUNK_Z)
                    .append(")\n");
            appendSectionProfile(sb, "SELF", chunk);
            appendHeightmap(sb, "SELF", chunk);
            // 本柱探针 section 四向边界平面（跨柱蔓延 / empty-shape 判定的接缝）
            appendPlane(sb, "SELF-E(x=15)", chunk, SECTION_Y, 'x', 15);
            appendPlane(sb, "SELF-W(x=0)", chunk, SECTION_Y, 'x', 0);
            appendPlane(sb, "SELF-N(z=0)", chunk, SECTION_Y, 'z', 0);
            appendPlane(sb, "SELF-S(z=15)", chunk, SECTION_Y, 'z', 15);
            // 四邻：E 邻是残差方向（E=3 vs vanilla 5），全剖面+接缝面+高度图；
            // 其余三邻只给接缝面。
            LevelChunk e = server.injectedChunk(DimensionKey.OVERWORLD, CHUNK_X + 1, CHUNK_Z);
            if (e != null) {
                appendSectionProfile(sb, "E-NEIGHBOR", e);
                appendHeightmap(sb, "E-NEIGHBOR", e);
                appendPlane(sb, "E-NEIGHBOR-W(x=0)", e, SECTION_Y, 'x', 0);
            } else {
                sb.append("E-NEIGHBOR: <not injected>\n");
            }
            LevelChunk w = server.injectedChunk(DimensionKey.OVERWORLD, CHUNK_X - 1, CHUNK_Z);
            appendPlaneOrMissing(sb, "W-NEIGHBOR-E(x=15)", w, 'x', 15);
            LevelChunk n = server.injectedChunk(DimensionKey.OVERWORLD, CHUNK_X, CHUNK_Z - 1);
            appendPlaneOrMissing(sb, "N-NEIGHBOR-S(z=15)", n, 'z', 15);
            LevelChunk s = server.injectedChunk(DimensionKey.OVERWORLD, CHUNK_X, CHUNK_Z + 1);
            appendPlaneOrMissing(sb, "S-NEIGHBOR-N(z=0)", s, 'z', 0);
            Constants.LOG.info("{}", sb);
        } catch (Throwable t) {
            warnHookFailure("onBeforeLightChunk", t);
        }
    }

    /**
     * Hook 3：lightChunk 完成后（submitLightChunkLocked 的 whenComplete 回调）。
     * 输出引擎输出面：探针 section 的 sky/block 数据层 base64 全量 + 东缘列
     * (x=15) sky 光人读网格（行=z，列=y）。
     */
    public static void onLightChunkComplete(LevelLightEngine engine, LevelChunk chunk,
                                            Throwable error, boolean converged) {
        if (!enabled() || !isProbeChunk(chunk.getPos()) || engine == null) {
            return;
        }
        try {
            if (error != null) {
                Constants.LOG.info("{}LIGHTCHUNK-DONE failed: {}", PREFIX, error.toString());
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(PREFIX).append("LIGHTCHUNK-DONE converged=").append(converged).append('\n');
            appendEngineLayers(sb, "DONE", engine);
            Constants.LOG.info("{}", sb);
        } catch (Throwable t) {
            warnHookFailure("onLightChunkComplete", t);
        }
    }

    /**
     * Hook 4：回传包构建后（SeedGenChunkCodec.buildPacket 成功路径）。
     * 输出包内探针 section 的 sky/block 字节数组（base64）——客户端实际收到的值。
     * 光包数据在本版本映射下是 {@code List<byte[]>}（按下标对应 section，自
     * {@code getMinLightSection()} 起），同时转储全量条目便于跨版本核对。
     */
    public static void onReturnPacket(LevelChunk chunk, LevelLightEngine engine,
                                      ClientboundLevelChunkWithLightPacket packet) {
        if (!enabled() || !isProbeChunk(chunk.getPos()) || packet == null) {
            return;
        }
        try {
            java.util.List<?> sky = packet.getLightData().getSkyUpdates();
            java.util.List<?> block = packet.getLightData().getBlockUpdates();
            int probeIdx = engine == null ? -1 : SECTION_Y - engine.getMinLightSection();
            Constants.LOG.info("{}PACKET probeIndex={} skyUpdates({}): {}",
                    PREFIX, probeIdx, sky.size(), dumpLightList(sky));
            Constants.LOG.info("{}PACKET probeIndex={} blockUpdates({}): {}",
                    PREFIX, probeIdx, block.size(), dumpLightList(block));
        } catch (Throwable t) {
            warnHookFailure("onReturnPacket", t);
        }
    }
    /** Hook 5 节流周期（毫秒）。 */
    private static final long SNAPSHOT_INTERVAL_MS = 5_000L;
    private static long lastSnapshotMs;

    /**
     * Hook 5：主线程帧尾周期快照（{@code ShadowLightCompute.drainReady} 调用）。
     * 输出影子引擎「当前」对探针 section 的层 + 客户端已落地同点读数——
     * 直接判定残差环节：引擎终态 ≠ vanilla 真值 → 输入面/引擎差异；
     * 引擎终态 = 真值而客户端读数偏离 → 修正下发/落地缺陷。
     */
    public static void onEngineTick() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapshotMs < SNAPSHOT_INTERVAL_MS) {
            return;
        }
        lastSnapshotMs = now;
        try {
            ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
            if (server == null) {
                return;
            }
            LevelChunk chunk = server.injectedChunk(CHUNK_X, CHUNK_Z);
            if (chunk == null) {
                return;
            }
            LevelLightEngine engine =
                    server.overworld().getChunkSource().getLightEngine();
            StringBuilder sb = new StringBuilder();
            sb.append(PREFIX).append("ENGINE-SNAPSHOT\n");
            appendEngineLayers(sb, "SNAP", engine);
            appendClientReadings(sb);
            Constants.LOG.info("{}", sb);
        } catch (Throwable t) {
            warnHookFailure("onEngineTick", t);
        }
    }

    /** 客户端已落地光：探针测点 (x=15,y=8,z=8) 的 sky/block 读数。 */
    private static void appendClientReadings(StringBuilder sb) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.client.multiplayer.ClientLevel level = mc != null ? mc.level : null;
            if (level == null) {
                return;
            }
            net.minecraft.core.BlockPos cell = new net.minecraft.core.BlockPos(
                    (CHUNK_X << 4) | 15, (SECTION_Y << 4) | 8, (CHUNK_Z << 4) | 8);
            int sky = level.getBrightness(LightLayer.SKY, cell);
            int blockLight = level.getBrightness(LightLayer.BLOCK, cell);
            sb.append(PREFIX).append("CLIENT-READ cell=local(15,8,8) world=(")
                    .append((CHUNK_X << 4) + 15).append(',').append((SECTION_Y << 4) + 8)
                    .append(',').append((CHUNK_Z << 4) + 8).append(") sky=").append(sky)
                    .append(" block=").append(blockLight).append('\n');
        } catch (Throwable t) {
            warnHookFailure("appendClientReadings", t);
        }
    }

    /** 光包 section 数据列表全量转储：{@code 下标=值}（byte[] 转 base64）。 */
    private static String dumpLightList(java.util.List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = values.get(i);
            sb.append(i).append('=').append(v instanceof byte[] bytes
                    ? "b64:" + Base64.getEncoder().encodeToString(bytes)
                    : String.valueOf(v));
        }
        return sb.length() == 0 ? "<empty>" : sb.toString();
    }
    private static void warnHookFailure(String hook, Throwable t) {
        Constants.LOG.warn("{}hook {} failed (probe disabled itself for safety)", PREFIX, hook, t);
    }

    /** 全柱 section 空-实剖面：sectionY:air / sectionY:*（非空）。 */
    private static void appendSectionProfile(StringBuilder sb, String tag, LevelChunk chunk) {
        int base = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk.getLevel());
        LevelChunkSection[] sections = chunk.getSections();
        sb.append(tag).append(" sections[").append(sections.length).append("]:");
        for (int i = 0; i < sections.length; i++) {
            sb.append(' ').append(base + i).append(sections[i].hasOnlyAir() ? ":air" : ":*");
        }
        sb.append('\n');
    }
    /** WORLD_SURFACE 高度图：16 行（z=0..15）×16 列（x=0..15）。 */
    private static void appendHeightmap(StringBuilder sb, String tag, LevelChunk chunk) {
        sb.append(tag).append(" heightmap[WORLD_SURFACE] rows=z cols=x:\n");
        for (int z = 0; z < 16; z++) {
            sb.append(tag).append("  z").append(z).append(':');
            for (int x = 0; x < 16; x++) {
                sb.append(' ').append(chunk.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z));
            }
            sb.append('\n');
        }
    }

    /**
     * 探针 section 逐 y 平面非空气方块清单（完整，无截断）：每行一个 y，
     * 列出全部非空气 {@code (x,z)=state}。
     */
    private static void appendSectionDump(StringBuilder sb, String tag, LevelChunk chunk, int sectionY) {
        int idx = sectionY - io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk.getLevel());
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            sb.append(tag).append(" section ").append(sectionY).append(": <out of range>\n");
            return;
        }
        LevelChunkSection sec = sections[idx];
        if (sec.hasOnlyAir()) {
            sb.append(tag).append(" section ").append(sectionY).append(": ALL AIR\n");
            return;
        }
        sb.append(tag).append(" section ").append(sectionY).append(" non-air by y:\n");
        int baseY = sectionY << 4;
        for (int dy = 15; dy >= 0; dy--) {
            StringBuilder row = new StringBuilder();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    net.minecraft.world.level.block.state.BlockState st = sec.getBlockState(x, dy, z);
                    if (!st.isAir()) {
                        row.append('(').append(x).append(',').append(z).append(")=").append(st).append(' ');
                    }
                }
            }
            if (row.length() > 0) {
                sb.append(tag).append("  y=").append(baseY + dy).append(": ").append(row).append('\n');
            }
        }
    }

    /**
     * section 内边界平面非空气清单：axis='x' 固定 localX=fixed 遍历 (y,z)；
     * axis='z' 固定 localZ=fixed 遍历 (y,x)。仅列非空气行。
     */
    private static void appendPlane(StringBuilder sb, String tag, LevelChunk chunk,
                                    int sectionY, char axis, int fixed) {
        int idx = sectionY - io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk.getLevel());
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            sb.append(tag).append(": <out of range>\n");
            return;
        }
        LevelChunkSection sec = sections[idx];
        if (sec.hasOnlyAir()) {
            sb.append(tag).append(": ALL AIR\n");
            return;
        }
        int baseY = sectionY << 4;
        for (int dy = 15; dy >= 0; dy--) {
            StringBuilder row = new StringBuilder();
            for (int t = 0; t < 16; t++) {
                net.minecraft.world.level.block.state.BlockState st =
                        axis == 'x' ? sec.getBlockState(fixed, dy, t) : sec.getBlockState(t, dy, fixed);
                if (!st.isAir()) {
                    row.append(axis == 'x' ? "z" : "x").append(t).append('=').append(st).append(' ');
                }
            }
            if (row.length() > 0) {
                sb.append(tag).append(" y=").append(baseY + dy).append(": ").append(row).append('\n');
            }
        }
    }

    private static void appendPlaneOrMissing(StringBuilder sb, String tag, LevelChunk neighbor,
                                             char axis, int fixed) {
        if (neighbor == null) {
            sb.append(tag).append(": <neighbor not injected>\n");
            return;
        }
        appendPlane(sb, tag, neighbor, SECTION_Y, axis, fixed);
    }

    /** 引擎当前对探针 section 的 sky/block 数据层：base64 全量 + 东缘列 sky 人读网格。 */
    private static void appendEngineLayers(StringBuilder sb, String tag, LevelLightEngine engine) {
        SectionPos sp = SectionPos.of(SECTION_X, SECTION_Y, SECTION_Z);
        DataLayer sky = engine.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
        DataLayer block = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
        sb.append(tag).append(" engine sky=")
                .append(sky == null ? "<null>" : "b64:" + toBase64(sky))
                .append(" block=")
                .append(block == null ? "<null>" : "b64:" + toBase64(block))
                .append('\n');
        if (sky != null) {
            sb.append(tag).append(" east-column sky grid (rows=z, cols=y):\n");
            for (int z = 0; z < 16; z++) {
                sb.append(tag).append("  z").append(z).append(':');
                for (int y = 0; y < 16; y++) {
                    sb.append(' ').append(sky.get(15, y, z));
                }
                sb.append('\n');
            }
        }
    }

    /**
     * DataLayer → base64（2048 字节，索引序 {@code x | z<<4 | y<<8}）。
     * 不走 {@code getData()}：仅用跨版本稳定的 {@code get(x,y,z)} 三参读取。
     */
    private static String toBase64(DataLayer layer) {
        byte[] out = new byte[2048];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x += 2) {
                    out[i++] = (byte) (layer.get(x, y, z) | (layer.get(x + 1, y, z) << 4));
                }
            }
        }
        return Base64.getEncoder().encodeToString(out);
    }
}
