package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.LevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.mixin.LightEngineAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Promethium 光照引擎的 Hassium 侧钩子实现。
 * <p>
 * 官方引擎原语（依赖 hassium.mixin accessor）与磁盘缓存读写全部留在此处；
 * 引擎接口（LightEngineHooks）在 Promethium MOD 内，Hassium 无编译依赖——本类不
 * implements 接口，由 {@link PromethiumLightBridge} 经反射 Proxy 包装注入，不反向依赖。
 */
public final class HassiumLightHooks {

    public static final HassiumLightHooks INSTANCE = new HassiumLightHooks();

    private HassiumLightHooks() {}

    public int safeRunLightUpdates(LevelLightEngine lightEngine) {
        try {
            return lightEngine.runLightUpdates();
        } catch (Throwable t) {
            Constants.LOG.error("Hassium: runLightUpdates failed; clearing residual light queues", t);
            clearLightQueues(lightEngine);
            return 0;
        }
    }

    /** 清空 sky/block engine 的 deferred 队列（失败兜底，避免渲染线程再崩）。 */
    public static void clearLightQueues(LevelLightEngine lightEngine) {
        if (lightEngine == null) {
            return;
        }
        LevelLightEngineAccessor accessor = (LevelLightEngineAccessor) lightEngine;
        clearEngineQueues(accessor.hassium$getBlockEngine());
        clearEngineQueues(accessor.hassium$getSkyEngine());
    }

    private static void clearEngineQueues(LightEngine<?, ?> engine) {
        if (engine == null) {
            return;
        }
        LightEngineAccessor acc = (LightEngineAccessor) engine;
        LongOpenHashSet nodes = acc.hassium$getBlockNodesToCheck();
        if (nodes != null) {
            nodes.clear();
        }
        LongArrayFIFOQueue decrease = acc.hassium$getDecreaseQueue();
        if (decrease != null) {
            decrease.clear();
        }
        LongArrayFIFOQueue increase = acc.hassium$getIncreaseQueue();
        if (increase != null) {
            increase.clear();
        }
    }

    public void ensureColumnDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                       ChunkPos chunkPos, int bottomSection, int topSection) {
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
            lightEngine.updateSectionStatus(sectionPos, false);
            level.setSectionDirtyWithNeighbors(chunkPos.x, sectionY, chunkPos.z);
        }
    }

    public void ensureNeighborDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                         ChunkPos chunkPos, int bottomSection, int topSection) {
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x + 1, chunkPos.z, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x - 1, chunkPos.z, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x, chunkPos.z + 1, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x, chunkPos.z - 1, bottomSection, topSection);
    }

    private static void ensureNeighborColumnIfLoaded(ClientLevel level, LevelLightEngine lightEngine,
                                                     int chunkX, int chunkZ,
                                                     int bottomSection, int topSection) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return;
        }
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkX, sectionY, chunkZ);
            lightEngine.updateSectionStatus(sectionPos, false);
        }
    }

    public void pullLightFromNeighborEdges(ClientLevel level, ChunkPos chunkPos,
                                           int bottomSection, int topSection) {
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener sky = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener block = lightEngine.getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos ourPos = new BlockPos.MutableBlockPos();

        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();

        if (level.getChunkSource().getChunkNow(chunkPos.x + 1, chunkPos.z) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x + 1, sectionY, chunkPos.z)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + 16, y, minZ + z);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + 15, y, minZ + z));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x - 1, chunkPos.z) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x - 1, sectionY, chunkPos.z)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX - 1, y, minZ + z);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX, y, minZ + z));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z + 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z + 1)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ + 16);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + x, y, minZ + 15));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z - 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z - 1)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ - 1);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + x, y, minZ));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean neighborSectionDark(LayerLightEventListener sky, LayerLightEventListener block,
                                               int sectionX, int sectionY, int sectionZ) {
        SectionPos sectionPos = SectionPos.of(sectionX, sectionY, sectionZ);
        return isLightLayerEmpty(sky.getDataLayerData(sectionPos))
                && isLightLayerEmpty(block.getDataLayerData(sectionPos));
    }

    private static boolean isLightLayerEmpty(DataLayer layer) {
        return layer == null || layer.isEmpty();
    }

    public void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos, CompoundTag cachedNbt) {
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) return;

            CompoundTag nbt = cachedNbt;
            if (nbt == null) {
                // fallback：从磁盘读取
                byte[] cachedData = storage.loadAndDecompress(chunkPos);
                if (cachedData == null) return;
                nbt = ChunkDiskCodec.bytesToNbt(cachedData);
                if (nbt == null) return;
            }

            // 刚重算完：始终用引擎态覆盖磁盘（勿因旧 is_light_on=1 提前 return，
            // SectionDelta 曾留下「flag=1 + 残缺 light」时会永久跳过回写）
            LevelLightEngine lightEngine = level.getLightEngine();
            LayerLightEventListener skyListener =
                    lightEngine.getLayerListener(LightLayer.SKY);
            LayerLightEventListener blockListener =
                    lightEngine.getLayerListener(LightLayer.BLOCK);

            int minSection = LevelHeightCompat.getMinSection(level);
            int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
            net.minecraft.nbt.ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");

            boolean hasAnyLight = false;
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int idx = sectionY - minSection;
                if (idx >= sectionsList.size()) break;

                net.minecraft.nbt.Tag t = sectionsList.get(idx);
                if (!(t instanceof net.minecraft.nbt.CompoundTag sectionTag)) continue;

                SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);

                DataLayer skyData = skyListener.getDataLayerData(sectionPos);
                if (skyData != null && !skyData.isEmpty()) {
                    sectionTag.putByteArray("sky_light", skyData.getData().clone());
                    hasAnyLight = true;
                } else {
                    sectionTag.remove("sky_light");
                }

                DataLayer blockData = blockListener.getDataLayerData(sectionPos);
                if (blockData != null && !blockData.isEmpty()) {
                    sectionTag.putByteArray("block_light", blockData.getData().clone());
                    hasAnyLight = true;
                } else {
                    sectionTag.remove("block_light");
                }
            }

            if (hasAnyLight) {
                nbt.putByte("is_light_on", (byte) 1);
                byte[] updatedBytes = ChunkDiskCodec.nbtToBytes(nbt);
                if (updatedBytes != null) {
                    // 保留原 contentHash / sectionHashes，避免 persist(0) 被 MetadataTable 写成 1
                    long contentHash = storage.readChunkHash(chunkPos);
                    if (contentHash == 0L || contentHash == 1L) {
                        // 入库尚未完成或元数据不可信：勿覆盖 hash，保持 dirty 等卸载补丁
                        Constants.LOG.debug("Hassium: Skip light writeback for {} (hash={})",
                                chunkPos, Long.toHexString(contentHash));
                        return;
                    }
                    long[] sectionHashes = storage.readSectionHashes(chunkPos);
                    // 后台化：压缩 + 写盘由 Cache-Saver 单消费者顺序执行（FIFO 保证与 ingest
                    // 任务最终一致：先入队的无光照 ingest 先写、光照任务后写覆盖；后入队的 ingest
                    // 在防护检查时命中写回记录被跳过）；persist 成功后由后台线程登记写回记录并 clear dirty
                    CacheSaveQueue.getInstance().enqueueLightWriteback(chunkPos, updatedBytes, contentHash, sectionHashes);
                    Constants.LOG.debug("Hassium: Enqueued light writeback for {}", chunkPos);
                }
            }
            // 引擎尚无光照可写：保持 dirty，留给卸载光照补丁
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to update cache with light data for {}", chunkPos, e);
        }
    }

    public CompoundTag loadChunkNbtFromCache(ChunkPos pos) {
        return ClientChunkHandler.loadChunkNbtFromCache(pos);
    }

    // —— 阶段二落地原语：双缓冲 swap 交换（反射，惰性发现） ——
    //
    // 目标结构（mojmap，1.20.1/1.21.11 同构，已 javap 验证）：
    //   LightEngine.storage（S extends LayerLightSectionStorage）→
    //     storage.visibleSectionData（volatile M，渲染读）/ storage.updatingSectionData（final M，传播写）
    //   DataLayerStorageMap.setLayer(long, DataLayer)：put 进 map，不清最近访问缓存
    //   DataLayerStorageMap.clearCache()：清 lastSectionKeys/lastSections 缓存（必须调，否则
    //     getDataLayerData 命中缓存返回旧对象 → 落地不生效）
    // 官方传播 swap 时 visible = updating.copy()（浅拷贝共享 DataLayer 对象）——因此两个 map
    // 都必须换，否则传播后旧对象回归覆盖落地值（现状 memcpy 安全正是因为改共享对象内部数组）。
    // 发现策略：字段名快路径（mojmap/layered named）；SRG/intermediary 下字段名不可用，按
    // 「声明类型 + 修饰符」兜底（storage 类型唯一；visible 带 volatile、updating 带 final）。
    // clearCache 只能按名（SRG 下与 disableCache 同签名无法区分）——发现失败整体降级 memcpy，
    // 功能保真（主线程税回退），不阻塞引擎。
    private static volatile Field ENGINE_STORAGE_FIELD;
    private static volatile Field VISIBLE_DATA_FIELD;
    private static volatile Field UPDATING_DATA_FIELD;
    private static volatile Method STORAGE_SET_LAYER;
    private static volatile Method STORAGE_CLEAR_CACHE;
    private static volatile boolean SWAP_PRIMITIVE_READY;

    public void swapDataLayer(LevelLightEngine lightEngine, LightLayer layer,
                              SectionPos sectionPos, DataLayer data) {
        try {
            if (!SWAP_PRIMITIVE_READY && !discoverSwapPrimitive(lightEngine, layer)) {
                fallbackMemcpySwap(lightEngine, layer, sectionPos, data);
                return;
            }
            Object listener = lightEngine.getLayerListener(layer);
            Object storage = ENGINE_STORAGE_FIELD.get(listener);
            long key = sectionPos.asLong();
            STORAGE_SET_LAYER.invoke(VISIBLE_DATA_FIELD.get(storage), key, data);
            STORAGE_SET_LAYER.invoke(UPDATING_DATA_FIELD.get(storage), key, data);
            STORAGE_CLEAR_CACHE.invoke(VISIBLE_DATA_FIELD.get(storage));
            STORAGE_CLEAR_CACHE.invoke(UPDATING_DATA_FIELD.get(storage));
        } catch (Throwable t) {
            // 原语不可用/失败：退化为可见层 memcpy 覆盖（正确性保真，主线程税回退）
            Constants.LOG.error("Hassium: swapDataLayer failed for {}, fallback memcpy", sectionPos, t);
            fallbackMemcpySwap(lightEngine, layer, sectionPos, data);
        }
    }

    private static boolean discoverSwapPrimitive(LevelLightEngine lightEngine, LightLayer layer) {
        try {
            Object listener = lightEngine.getLayerListener(layer);
            Class<?> listenerClass = listener.getClass();
            // LightEngine.storage：按声明类型 LayerLightSectionStorage 沿继承链找（SRG 稳）
            Field storageField = findFieldByType(listenerClass, "storage", LayerLightSectionStorage.class);
            if (storageField == null) {
                throw new NoSuchFieldException("storage");
            }
            Class<?> storageClass = storageField.getType();
            // visibleSectionData（volatile）/ updatingSectionData（final）：类型 DataLayerStorageMap
            // 有两个同类型字段，用修饰符区分；名字命中优先（快路径）。
            Field visibleField = findDualField(storageClass, "visibleSectionData",
                    java.lang.reflect.Modifier.VOLATILE, null);
            Field updatingField = findDualField(storageClass, "updatingSectionData",
                    java.lang.reflect.Modifier.FINAL, visibleField);
            if (visibleField == null || updatingField == null) {
                throw new NoSuchFieldException("visible/updating data map");
            }
            // setLayer(long, DataLayer) → void：按签名找（SRG 稳）
            Method setLayer = null;
            Method clearCache = null;
            for (Class<?> c = storageClass; c != null && (setLayer == null || clearCache == null); c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (setLayer == null && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == long.class
                            && m.getParameterTypes()[1] == DataLayer.class
                            && m.getReturnType() == void.class) {
                        setLayer = m;
                    }
                    if (clearCache == null && m.getParameterCount() == 0
                            && m.getReturnType() == void.class
                            && m.getName().equals("clearCache")) {
                        clearCache = m;
                    }
                }
            }
            if (setLayer == null) {
                throw new NoSuchMethodException("setLayer");
            }
            if (clearCache == null) {
                // 缓存不清 → 落地可能不生效；保守降级（调用方走 memcpy 兜底）
                throw new NoSuchMethodException("clearCache");
            }
            storageField.setAccessible(true);
            visibleField.setAccessible(true);
            updatingField.setAccessible(true);
            setLayer.setAccessible(true);
            clearCache.setAccessible(true);
            ENGINE_STORAGE_FIELD = storageField;
            VISIBLE_DATA_FIELD = visibleField;
            UPDATING_DATA_FIELD = updatingField;
            STORAGE_SET_LAYER = setLayer;
            STORAGE_CLEAR_CACHE = clearCache;
            SWAP_PRIMITIVE_READY = true;
            return true;
        } catch (Throwable t) {
            Constants.LOG.error("Hassium: swapDataLayer primitive discovery failed, using memcpy fallback", t);
            return false;
        }
    }

    /** 按声明类型沿继承链找字段；字段名命中优先（mojmap 快路径），否则首个类型匹配（SRG 兜底）。 */
    private static Field findFieldByType(Class<?> owner, String preferredName, Class<?> fieldType) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            Field nameHit = null;
            for (Field f : c.getDeclaredFields()) {
                if (!fieldType.isAssignableFrom(f.getType())) {
                    continue;
                }
                if (f.getName().equals(preferredName)) {
                    return f;
                }
                if (nameHit == null) {
                    nameHit = f;
                }
            }
            if (nameHit != null) {
                return nameHit;
            }
        }
        return null;
    }

    /** 双 map 字段（同类型 ×2）：修饰符区分（VOLATILE → visible；FINAL → updating，排除 visible）。 */
    private static Field findDualField(Class<?> owner, String preferredName, int requiredModifiers, Field excluded) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            Field nameHit = null;
            Field modHit = null;
            for (Field f : c.getDeclaredFields()) {
                if (f == excluded) {
                    continue;
                }
                if ((f.getModifiers() & requiredModifiers) == 0) {
                    continue;
                }
                if (!DataLayerStorageMap.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                if (f.getName().equals(preferredName)) {
                    return f;
                }
                if (nameHit == null) {
                    nameHit = f;
                }
                if (modHit == null) {
                    modHit = f;
                }
            }
            if (nameHit != null) {
                return nameHit;
            }
            if (modHit != null) {
                return modHit;
            }
        }
        return null;
    }

    /** 降级路径：对现有可见层做 memcpy 覆盖（与阶段二前行为一致；层不存在则跳过）。 */
    private static void fallbackMemcpySwap(LevelLightEngine lightEngine, LightLayer layer,
                                           SectionPos sectionPos, DataLayer data) {
        try {
            LayerLightEventListener listener = lightEngine.getLayerListener(layer);
            DataLayer existing = listener.getDataLayerData(sectionPos);
            if (existing != null) {
                System.arraycopy(data.getData(), 0, existing.getData(), 0, 2048);
            }
        } catch (Throwable ignored) {
            // 兜底失败不再抛：落地缺失由后续重算/传播补偿
        }
    }
}
