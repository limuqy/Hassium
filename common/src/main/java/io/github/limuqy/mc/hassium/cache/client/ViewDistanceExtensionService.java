package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.MixinClientLevel;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * 视距扩展渲染服务
 * <p>
 * 当客户端渲染距离 > 服务端视距时，从本地缓存加载超视距区块用于视觉渲染。
 * 这些区块仅参与渲染，不执行实体AI、方块更新等逻辑。
 */
public class ViewDistanceExtensionService {

    private static final Minecraft mc = Minecraft.getInstance();

    // 已加载的 renderOnly 区块
    private final Set<ChunkPos> loadedRenderOnly = new HashSet<>();

    // 上次更新的玩家位置
    private ChunkPos lastPlayerPos = null;
    private int lastServerVD = -1;
    private int lastClientVD = -1;

    /**
     * 更新视距扩展
     * <p>
     * 应在客户端 tick 中调用。
     */
    public void update() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }

        int clientVD = mc.options.renderDistance().get();
        // 使用 simulationDistance 作为服务端视距的近似值
        // 实际上服务端视距可能不同，但这是最接近的客户端可用值
        int serverVD = mc.options.simulationDistance().get();

        if (serverVD <= 0) {
            return;
        }

        ChunkPos playerPos = mc.player.chunkPosition();

        // 检查是否需要更新
        if (playerPos.equals(lastPlayerPos) && clientVD == lastClientVD && serverVD == lastServerVD) {
            return;
        }

        // 如果客户端视距 <= 服务端视距，清理所有 renderOnly 区块
        if (clientVD <= serverVD) {
            clearAllRenderOnly();
            lastPlayerPos = playerPos;
            lastClientVD = clientVD;
            lastServerVD = serverVD;
            return;
        }

        // 计算需要的 renderOnly 区块范围
        Set<ChunkPos> neededChunks = calculateNeededChunks(playerPos, serverVD, clientVD);

        // 移除不再需要的区块
        Set<ChunkPos> toRemove = new HashSet<>(loadedRenderOnly);
        toRemove.removeAll(neededChunks);
        for (ChunkPos pos : toRemove) {
            unloadRenderOnlyChunk(pos);
        }

        // 加载新的区块
        Set<ChunkPos> toLoad = new HashSet<>(neededChunks);
        toLoad.removeAll(loadedRenderOnly);
        for (ChunkPos pos : toLoad) {
            loadRenderOnlyChunk(pos);
        }

        lastPlayerPos = playerPos;
        lastClientVD = clientVD;
        lastServerVD = serverVD;
    }

    /**
     * 计算需要的 renderOnly 区块
     */
    private Set<ChunkPos> calculateNeededChunks(ChunkPos playerPos, int serverVD, int clientVD) {
        Set<ChunkPos> chunks = new HashSet<>();

        for (int dx = -clientVD; dx <= clientVD; dx++) {
            for (int dz = -clientVD; dz <= clientVD; dz++) {
                // 只加载服务端视距外的区块
                if (Math.abs(dx) > serverVD || Math.abs(dz) > serverVD) {
                    // 检查是否在圆形视距内
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance <= clientVD) {
                        chunks.add(new ChunkPos(playerPos.x + dx, playerPos.z + dz));
                    }
                }
            }
        }

        return chunks;
    }

    /**
     * 加载 renderOnly 区块
     * <p>
     * 使用异步加载队列，避免阻塞主线程。
     * renderOnly 区块优先级较低（priority=1），让位于正常的缓存命中区块。
     */
    private void loadRenderOnlyChunk(ChunkPos pos) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        // 检查是否已经是 renderOnly
        MixinClientLevel accessor = (MixinClientLevel) (Object) level;
        if (accessor.hassium$isRenderOnly(pos)) {
            return;
        }

        // 使用异步加载队列（优先级=1，低于正常的缓存命中区块，renderOnly=true）
        ClientCacheLoadQueue.getInstance().enqueue(pos, 1.0, true);
        loadedRenderOnly.add(pos);
        Constants.LOG.debug("Hassium: Queued render-only chunk {} for async loading", pos);
    }

    /**
     * 卸载 renderOnly 区块
     */
    private void unloadRenderOnlyChunk(ChunkPos pos) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        MixinClientLevel accessor = (MixinClientLevel) (Object) level;
        accessor.hassium$removeRenderOnlyChunk(pos);
        loadedRenderOnly.remove(pos);

        // 注意：我们不从 ClientChunkCache 中移除区块，因为这可能导致渲染问题
        // 只是移除 renderOnly 标记，让原版的卸载机制处理
    }

    /**
     * 清理所有 renderOnly 区块
     */
    public void clearAllRenderOnly() {
        ClientLevel level = mc.level;
        if (level != null) {
            MixinClientLevel accessor = (MixinClientLevel) (Object) level;
            for (ChunkPos pos : new HashSet<>(loadedRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos);
            }
        }
        loadedRenderOnly.clear();
        Constants.LOG.debug("Hassium: Cleared all render-only chunks");
    }

    /**
     * 获取已加载的 renderOnly 区块数量
     */
    public int getLoadedCount() {
        return loadedRenderOnly.size();
    }

    /**
     * 检查是否启用了视距扩展
     */
    public boolean isEnabled() {
        if (mc.getConnection() == null) {
            return false;
        }
        int clientVD = mc.options.renderDistance().get();
        // 使用 simulationDistance 作为服务端视距的近似值
        int serverVD = mc.options.simulationDistance().get();
        return clientVD > serverVD;
    }
}
