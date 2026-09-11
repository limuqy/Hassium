package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 进服「脚下柱」焦点：加载屏只认脚下 mesh，Pull 管线按切比雪夫距离优先这一柱。
 * <p>
 * 不是原版 trackChunk 直推。焦点在 {@code handleLogin} 写入，断连清零。
 */
public final class JoinWorldFocus {

    /** 加载屏未关时只 apply 脚下 3×3（chebyshev ≤ 1）。 */
    public static final int LOADING_SCREEN_CHEBYSHEV = 1;

    private static volatile boolean hasFocus;
    private static volatile int focusX;
    private static volatile int focusZ;
    /** 本会话还没有焦点时，第一根注入柱直接回传（登录包没有坐标）。 */
    private static final AtomicBoolean firstImmediateEmit = new AtomicBoolean();

    private JoinWorldFocus() {
    }

    public static void setFocusChunk(int chunkX, int chunkZ) {
        focusX = chunkX;
        focusZ = chunkZ;
        hasFocus = true;
    }

    public static void updateFromClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        ChunkPos pos = player.chunkPosition();
        setFocusChunk(pos.x, pos.z);
    }

    public static void clear() {
        hasFocus = false;
        firstImmediateEmit.set(false);
    }

    /**
     * 注入后立刻回传、不等光屏障：脚下柱，或尚无焦点时本会话第一柱。
     */
    public static boolean shouldEmitImmediately(int chunkX, int chunkZ) {
        if (isStandingColumn(chunkX, chunkZ)) {
            return true;
        }
        if (hasFocus) {
            return false;
        }
        if (!firstImmediateEmit.compareAndSet(false, true)) {
            return false;
        }
        setFocusChunk(chunkX, chunkZ);
        return true;
    }

    public static boolean hasFocus() {
        return hasFocus;
    }

    public static int focusX() {
        return focusX;
    }

    public static int focusZ() {
        return focusZ;
    }

    public static int chebyshev(int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - focusX), Math.abs(chunkZ - focusZ));
    }

    public static boolean isStandingColumn(int chunkX, int chunkZ) {
        return hasFocus && chunkX == focusX && chunkZ == focusZ;
    }

    public static boolean isStandingKey(long dimensionKey) {
        return isStandingColumn(DimensionKey.chunkXOf(dimensionKey), DimensionKey.chunkZOf(dimensionKey));
    }

    /**
     * 加载屏仍在时推迟 3×3 以外的柱，把主线程预算留给脚下 mesh。
     */
    public static boolean shouldDeferFarChunk(int chunkX, int chunkZ, boolean loadingScreenVisible) {
        return loadingScreenVisible && hasFocus && chebyshev(chunkX, chunkZ) > LOADING_SCREEN_CHEBYSHEV;
    }

    /**
     * drainReady 优先级：数值越小越先出队。有焦点时切比雪夫主导，FIFO 序号只作并列打破。
     */
    public static double chunkApplyPriority(int chunkX, int chunkZ, double fifo) {
        if (!hasFocus) {
            return fifo;
        }
        return chebyshev(chunkX, chunkZ) * 1_000_000_000.0d + fifo;
    }

    public static <T> Long findStandingKey(ConcurrentHashMap<Long, T> source) {
        if (!hasFocus || source == null || source.isEmpty()) {
            return null;
        }
        for (Long key : source.keySet()) {
            if (isStandingKey(key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * 取批：脚下柱（若在表内）永远第一，其余保持表迭代序。
     */
    public static <T> void fillDistanceFirst(ConcurrentHashMap<Long, T> source,
                                             List<Map.Entry<Long, T>> batch, int limit) {
        if (source == null || source.isEmpty() || limit <= 0) {
            return;
        }
        Map.Entry<Long, T> standing = null;
        if (hasFocus) {
            for (Map.Entry<Long, T> entry : source.entrySet()) {
                if (isStandingKey(entry.getKey())) {
                    standing = entry;
                    break;
                }
            }
        }
        if (standing != null) {
            batch.add(standing);
        }
        for (Map.Entry<Long, T> entry : source.entrySet()) {
            if (batch.size() >= limit) {
                break;
            }
            if (standing != null && standing.getKey().longValue() == entry.getKey().longValue()) {
                continue;
            }
            batch.add(entry);
        }
    }
}
