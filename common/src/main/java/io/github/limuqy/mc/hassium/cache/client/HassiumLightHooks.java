package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Hassium 光照原语（影子端落光/提取用）。
 * <p>
 * 官方引擎原语（依赖 hassium.mixin accessor）与磁盘缓存读写全部留在此处；
 * 由影子端（ShadowSeedServer 提取 / ShadowLightCompute 落光）与卸载写光链调用。
 */
public final class HassiumLightHooks {

    public static final HassiumLightHooks INSTANCE = new HassiumLightHooks();

    private HassiumLightHooks() {}

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
            // setLayer(long, DataLayer) → void / clearCache()：receiver 是 DataLayerStorageMap
            // （visible/updating 字段值），只从 map 树找。storageClass（LayerLightSectionStorage）
            // 树上若有同名方法（自定义实现），receiver 不匹配会 IllegalArgumentException。
            Method setLayer = null;
            Method clearCache = null;
            for (Class<?> c = visibleField.getType(); c != null && (setLayer == null || clearCache == null); c = c.getSuperclass()) {
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
