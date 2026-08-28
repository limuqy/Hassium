package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端网格编译诊断：vanilla {@code LevelRenderer.addRecentlyCompiledChunk/Section}
 * 在 RenderChunk/RenderSection 网格就绪时回调，晚于区块数据 apply。
 * <p>
 * {@code first_column}：每柱第一次编译（可能是空网格）。
 * {@code eye_section}：玩家所在 sectionY 的每次编译（含 apply 后重建，对照可见地形）。
 * 开关：{@code debug.chunkApplyLogging} 或 {@code -Dhassium.smokeTest=true}。
 */
public final class ChunkMeshCompileLog {

    private static final Set<Long> FIRST_COLUMN = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, AtomicInteger> EYE_SEQ = new ConcurrentHashMap<>();

    private ChunkMeshCompileLog() {
    }

    public static void reset() {
        FIRST_COLUMN.clear();
        EYE_SEQ.clear();
    }

    public static void onCompiled(int originX, int originY, int originZ) {
        if (!enabled()) {
            return;
        }
        int cx = originX >> 4;
        int cz = originZ >> 4;
        int sectionY = originY >> 4;
        boolean firstColumn = FIRST_COLUMN.add(ChunkPos.asLong(cx, cz));
        Minecraft mc = Minecraft.getInstance();
        if (firstColumn) {
            io.github.limuqy.mc.hassium.network.seedgen.SmokeChunkTrace.recordMeshCompiled(
                    io.github.limuqy.mc.hassium.network.seedgen.ShadowVanillaLightPipeline.currentDimension(),
                    new ChunkPos(cx, cz));
        }
        int playerSectionY = Integer.MIN_VALUE;
        int pcx = 0;
        int pcz = 0;
        boolean havePlayer = mc != null && mc.player != null;
        if (havePlayer) {
            playerSectionY = (int) Math.floor(mc.player.getY()) >> 4;
            pcx = (int) Math.floor(mc.player.getX()) >> 4;
            pcz = (int) Math.floor(mc.player.getZ()) >> 4;
        }
        boolean eye = havePlayer && sectionY == playerSectionY;
        if (!firstColumn && !eye) {
            return;
        }
        long eventMs = System.currentTimeMillis();
        int cheb = havePlayer ? Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) : -1;
        if (firstColumn) {
            log("first_column", eventMs, cx, cz, sectionY, cheb, havePlayer, pcx, pcz, 1);
        }
        if (eye) {
            long eyeKey = ChunkPos.asLong(cx, cz);
            int seq = EYE_SEQ.computeIfAbsent(eyeKey, k -> new AtomicInteger()).incrementAndGet();
            log("eye_section", eventMs, cx, cz, sectionY, cheb, true, pcx, pcz, seq);
        }
    }

    private static void log(String phase, long eventMs, int cx, int cz, int sectionY, int cheb,
                            boolean havePlayer, int pcx, int pcz, int seq) {
        if (!havePlayer) {
            Constants.LOG.info(
                    "[CHUNK_MESH] eventMs={} phase={} origin=client_compile target=({},{}) sectionY={} seq={} cheb=? player=unavailable",
                    eventMs, phase, cx, cz, sectionY, seq);
            return;
        }
        Constants.LOG.info(
                "[CHUNK_MESH] eventMs={} phase={} origin=client_compile target=({},{}) sectionY={} seq={} cheb={} playerChunk=({},{})",
                eventMs, phase, cx, cz, sectionY, seq, cheb, pcx, pcz);
    }

    private static boolean enabled() {
        return ClientSmokeTest.isEnabled() || DebugLogger.isEnabled(LogType.CHUNK_APPLY);
    }
}
