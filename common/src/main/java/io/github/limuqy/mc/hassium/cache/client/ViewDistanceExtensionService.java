package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor;
import io.github.limuqy.mc.hassium.mixin.MixinClientLevel;
import io.github.limuqy.mc.hassium.mixin.OptionsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * 视距扩展渲染服务（OVD，Out-of-View-Distance）
 * <p>
 * 当客户端渲染距离（RD） &gt; 服务端视距时，从本地 {@code hassium_cache} 加载超视距区块用于视觉渲染。
 * 这些区块标记为 {@code renderOnly}：
 * <ul>
 *   <li>仅参与渲染，不执行实体 AI、方块更新等逻辑</li>
 *   <li>不向服务器请求区块数据或 blockEntity（缓存 miss 静默跳过）</li>
 *   <li>真实区块到达时由 applier 清除标记，覆盖为正常区块</li>
 * </ul>
 * <p>
 * 单例：供 {@code MixinClientTick}（每 tick {@link #update}）、
 * {@link ClientCacheLoadQueue}（miss 回调 {@link #onRenderOnlyMiss}）、
 * {@code ClientLifecycleHelper}（断连清理 {@link #clearAllRenderOnly}）共用。
 * <p>
 * 单人游戏不启用（无缓存数据源，且单人 RD 不受 server 钳制）。
 */
public class ViewDistanceExtensionService {

    private static final ViewDistanceExtensionService INSTANCE = new ViewDistanceExtensionService();

    public static ViewDistanceExtensionService getInstance() {
        return INSTANCE;
    }

    /** 已加载的 renderOnly 区块 */
    private final Set<ChunkPos> loadedRenderOnly = new HashSet<>();

    /** 上次更新的玩家位置与视距（避免每 tick 重复计算） */
    private ChunkPos lastPlayerPos = null;
    private int lastServerVD = -1;
    private int lastClientVD = -1;

    private ViewDistanceExtensionService() {
    }

    /**
     * 更新视距扩展。应在客户端 tick 中调用。
     */
    public void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }
        // 单人游戏不启用（无缓存数据源，且单人 RD 不受 server 钳制）
        if (mc.getSingleplayerServer() != null) {
            return;
        }

        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            clearAllRenderOnly();
            return;
        }

        int clientVD = mc.options.renderDistance().get();
        // serverRenderDistance 由 ClientboundSetChunkCacheRadiusPacket 在 login 后写入（原版 private 字段，经 OptionsAccessor 暴露）
        int serverVD = ((OptionsAccessor) mc.options).hassium$getServerRenderDistance();
        if (serverVD <= 0) {
            // 未登录或异常 fallback
            serverVD = mc.options.simulationDistance().get();
        }
        if (serverVD <= 0 || clientVD <= serverVD) {
            clearAllRenderOnly();
            return;
        }

        ChunkPos playerPos = mc.player.chunkPosition();
        if (playerPos.equals(lastPlayerPos) && clientVD == lastClientVD && serverVD == lastServerVD) {
            return;
        }

        Set<ChunkPos> needed = calculateNeededChunks(playerPos, serverVD, clientVD);

        // 移除不再需要的 renderOnly 区块（P0：仅清标记；P1 真正 drop）
        Set<ChunkPos> toRemove = new HashSet<>(loadedRenderOnly);
        toRemove.removeAll(needed);
        for (ChunkPos pos : toRemove) {
            unloadRenderOnlyChunk(pos);
        }

        // 加载新的 renderOnly 区块（跳过已有真实区块）
        Set<ChunkPos> toLoad = new HashSet<>(needed);
        toLoad.removeAll(loadedRenderOnly);
        for (ChunkPos pos : toLoad) {
            loadRenderOnlyChunk(pos);
        }

        lastPlayerPos = playerPos;
        lastClientVD = clientVD;
        lastServerVD = serverVD;
    }

    /**
     * 计算需要的 renderOnly 区块（圆形环带：serverVD &lt; dist ≤ clientVD）
     */
    private Set<ChunkPos> calculateNeededChunks(ChunkPos playerPos, int serverVD, int clientVD) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (int dx = -clientVD; dx <= clientVD; dx++) {
            for (int dz = -clientVD; dz <= clientVD; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > clientVD) continue;      // 圆形外
                if (dist <= serverVD) continue;      // 视距内（服务端覆盖）
                chunks.add(new ChunkPos(playerPos.x + dx, playerPos.z + dz));
            }
        }
        return chunks;
    }

    /**
     * 加载 renderOnly 区块。优先级为真实距离（越近越优先）。
     * 已是 renderOnly 或已有真实区块则跳过。
     */
    private void loadRenderOnlyChunk(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        MixinClientLevel accessor = (MixinClientLevel) (Object) level;
        // 已是 renderOnly → 不重复 enqueue
        if (accessor.hassium$isRenderOnly(pos)) {
            return;
        }
        // 已有真实区块（在 ClientChunkCache 中且非 renderOnly）→ 不覆盖
        ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
        if (cache.hasChunk(pos.x, pos.z)) {
            return;
        }

        double dx = pos.x - (mc.player.getX() / 16.0);
        double dz = pos.z - (mc.player.getZ() / 16.0);
        double priority = Math.sqrt(dx * dx + dz * dz);
        ClientCacheLoadQueue.getInstance().enqueue(pos, priority, true);
        loadedRenderOnly.add(pos);
        Constants.LOG.debug("Hassium: Queued render-only chunk {} for async loading", pos);
    }

    /**
     * 卸载 renderOnly 区块。
     * <p>
     * 反射 {@code ClientChunkCache.Storage.drop} 真正从客户端缓存移除区块，
     * 并调 {@code level.unload} 触发 BE 清理 + 缓存保存（经 MixinClientLevel.hassium$onUnload）。
     * 仅卸载我们标记的 renderOnly 区块，不动真实区块。
     */
    private void unloadRenderOnlyChunk(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            loadedRenderOnly.remove(pos);
            return;
        }
        MixinClientLevel accessor = (MixinClientLevel) (Object) level;
        // 仅当区块仍是 renderOnly 标记时才 drop（防止误删真实区块）
        if (accessor.hassium$isRenderOnly(pos)) {
            dropChunkFromClientCache(level, pos);
        }
        accessor.hassium$removeRenderOnlyChunk(pos);
        loadedRenderOnly.remove(pos);
    }

    /**
     * 反射 {@code ClientChunkCache.Storage.drop(int, int)} 真正从客户端缓存移除区块。
     * 拿到旧 {@link LevelChunk} 后调 {@link ClientLevel#unload} 触发 BE 清理 + 缓存保存。
     * <p>
     * {@code Storage.drop(int, int)} 签名在 Mojmap 九段稳定；反射模式与现有
     * {@code *ClientChunkApplier.injectChunkViaReflection} 一致。
     */
    private void dropChunkFromClientCache(ClientLevel level, ChunkPos pos) {
        try {
            ClientChunkCache cache = ((ClientLevelAccessor) level).hassium$getChunkSource();
            java.lang.reflect.Field storageField = ClientChunkCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(cache);
            if (storage == null) {
                return;
            }
            java.lang.reflect.Method dropMethod = storage.getClass().getDeclaredMethod("drop", int.class, int.class);
            dropMethod.setAccessible(true);
            LevelChunk old = (LevelChunk) dropMethod.invoke(storage, pos.x, pos.z);
            if (old != null) {
                level.unload(old);  // 触发 MixinClientLevel.hassium$onUnload（BE 清理 + 缓存保存）
            }
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to drop renderOnly chunk {}", pos, e);
        }
    }

    /**
     * renderOnly 缓存 miss 时由 {@link ClientCacheLoadQueue} 回调。
     * <p>
     * 静默处理：从 loadedRenderOnly 移除并清 level 标记，<b>不</b>向服务器请求。
     */
    public void onRenderOnlyMiss(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        loadedRenderOnly.remove(pos);
        if (mc.level != null) {
            ((MixinClientLevel) (Object) mc.level).hassium$removeRenderOnlyChunk(pos);
        }
    }

    /**
     * 真实区块到达 renderOnly pos 时由 applier 回调（P1 边界替换）。
     * 从 loadedRenderOnly 摘除，防止后续 update 误判为待加载。
     */
    public void onRealChunkApplied(ChunkPos pos) {
        loadedRenderOnly.remove(pos);
    }

    /**
     * 清理所有 renderOnly 区块（断连 / 配置关闭 / clientVD≤serverVD 时调用）。
     */
    public void clearAllRenderOnly() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            MixinClientLevel accessor = (MixinClientLevel) (Object) mc.level;
            for (ChunkPos pos : new HashSet<>(loadedRenderOnly)) {
                accessor.hassium$removeRenderOnlyChunk(pos);
            }
        }
        if (!loadedRenderOnly.isEmpty()) {
            Constants.LOG.debug("Hassium: Cleared {} render-only chunks", loadedRenderOnly.size());
        }
        loadedRenderOnly.clear();
        lastPlayerPos = null;
        lastServerVD = -1;
        lastClientVD = -1;
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.getSingleplayerServer() != null) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            return false;
        }
        int clientVD = mc.options.renderDistance().get();
        int serverVD = ((OptionsAccessor) mc.options).hassium$getServerRenderDistance();
        if (serverVD <= 0) {
            serverVD = mc.options.simulationDistance().get();
        }
        return serverVD > 0 && clientVD > serverVD;
    }
}
