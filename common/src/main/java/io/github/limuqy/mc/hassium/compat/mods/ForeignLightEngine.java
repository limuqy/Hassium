package io.github.limuqy.mc.hassium.compat.mods;

import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 外部光照引擎（Starlight / ScalableLux）识别与兼容开关。
 * <p>
 * 二者都是 Starlight 血缘，在 {@code LevelLightEngine} 上实现
 * {@code ca.spottedleaf.starlight.common.light.StarLightLightingProvider} 并整体替换引擎。
 * <p>
 * <b>天然兼容的部分（无需适配）：</b>出光走
 * {@code ClientboundLightUpdatePacketData.prepareSectionData} →
 * {@code LevelLightEngine.getLayerListener(layer).getDataLayerData(sp)}，而该公开入口被
 * 替换为返回 starlight reader，因此影子端回传给客户端的即是对方算好的光；
 * {@code getMinLightSection} / {@code getLightSectionCount} / {@code tryScheduleUpdate}
 * 未被覆写，语义不变。
 * <p>
 * <b>失效的 4 个控制面（本类逐一给出降级语义）：</b>
 * <ol>
 *   <li>{@code queueSectionData} 被覆写为 no-op → 清光失效，必须改为"强制全量重算"；</li>
 *   <li>{@code initializeLight} 立即完成、{@code lightChunk(lit=true)} 只做
 *       {@code forceLoadInChunk}（不重算）→ 有 delta 时必须传 {@code lit=false}；</li>
 *   <li>{@code ThreadedLevelLightEngine.lightTasks} 不再被填充 → 收敛判定只能看
 *       {@code hasLightWork()}；</li>
 *   <li>{@code ChunkSkyLightSources.update} 被 {@code @Redirect} 成 no-op →
 *       {@code getLowestSourceY} 数值过期，天光源子检查不可信。</li>
 * </ol>
 * 详见 {@code docs/mod-compat.md}。
 */
public final class ForeignLightEngine {

    private static final Class<?> PROVIDER =
            load("ca.spottedleaf.starlight.common.light.StarLightLightingProvider");

    private ForeignLightEngine() {
    }

    /** 目标光源是否已被外部 Mod 替换。 */
    public static boolean isForeign(LevelLightEngine engine) {
        return PROVIDER != null && engine != null && PROVIDER.isInstance(engine);
    }

    /** 清光是否仍走 vanilla 的 {@code queueSectionData}（外部引擎下为 no-op）。 */
    public static boolean usesSectionDataClear(LevelLightEngine engine) {
        return !isForeign(engine);
    }

    /** 是否需要把 {@code lit} 强制为 false（外部引擎下 {@code lit=true} 不重算）。 */
    public static boolean mustForceFullRelight(LevelLightEngine engine, boolean hasDelta) {
        return isForeign(engine) && hasDelta;
    }

    /** 收敛判定是否可依赖 {@code lightTasks} 水位（外部引擎下该队列恒空）。 */
    public static boolean usesLightTaskWatermark(LevelLightEngine engine) {
        return !isForeign(engine);
    }

    /** 天光源（{@code ChunkSkyLightSources}）子检查是否可信。 */
    public static boolean skySourcesReliable(LevelLightEngine engine) {
        return !isForeign(engine);
    }

    /**
     * 反射取外部引擎挂在**柱实例**上的 nibble 数组（{@code ExtendedChunk}）；非 Starlight 血缘返回 null。
     * <p>
     * 为什么不能走 {@code engine.getLayerListener(SKY).getDataLayerData(sp)}：Starlight 的 reader 用
     * {@code ServerWorldMixin.getAnyChunkImmediately → chunkMap.getVisibleChunkIfPresent} 反查柱，
     * 影子端注入的柱大多不是「可见 holder」→ 返回 null → 整包 0 光。实测（1.21.1 fabric + ScalableLux，
     * 1400 柱）：交付柱上明明有 8 UNINIT + 3 INIT 的真数据（{@code avgUninit=8 avgInit=2}），
     * 同一引擎同一批 SectionPos 的 reader 却 {@code readerNonNull=0}。故改从柱上直取。
     */
    public static Object[] nibbles(net.minecraft.world.level.chunk.ChunkAccess chunk, boolean sky) {
        if (chunk == null) {
            return null;
        }
        try {
            return (Object[]) chunk.getClass()
                    .getMethod(sky ? "getSkyNibbles" : "getBlockNibbles")
                    .invoke(chunk);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射把单个 nibble 转成 vanilla {@code DataLayer}（Starlight 语义：NULL/HIDDEN → null）。 */
    public static net.minecraft.world.level.chunk.DataLayer toVanillaNibble(Object nibble) {
        if (nibble == null) {
            return null;
        }
        try {
            return (net.minecraft.world.level.chunk.DataLayer) nibble.getClass()
                    .getMethod("toVanillaNibble")
                    .invoke(nibble);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把外部引擎写在 {@code from} 上的 nibble 数组搬到 {@code to}（反射，无 Starlight 时返回 false）。
     * <p>
     * <b>为什么必须搬</b>：原版光照存在**引擎自己的 SectionPos 索引存储**里，
     * {@code getDataLayerData(sp)} 与柱实例无关；Starlight 血缘把光照存在
     * {@code chunk.getSkyNibbles()/getBlockNibbles()}（{@code ExtendedChunk}）**柱实例自己的数组**上，
     * 而 reader 用 {@code getAnyChunkNow(x,z)} 反查**关卡里那一份柱**。
     * 影子端算光走的是 {@code createNativeLightChunk} 造的一次性 ProtoChunk，
     * 于是「算出的光」落在 ProtoChunk 上、reader 与交付包看的是注入柱 → 全 NULL → 整包省略 → 客户端读 15。
     * 把数组引用搬回交付柱即恢复原版语义（数组是 SWMR 对象，共享引用即可，无需深拷）。
     */
    public static boolean copyLightNibbles(net.minecraft.world.level.chunk.ChunkAccess from,
                                           net.minecraft.world.level.chunk.ChunkAccess to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        boolean any = false;
        for (String layer : new String[]{"SkyNibbles", "BlockNibbles"}) {
            try {
                java.lang.reflect.Method get = from.getClass().getMethod("get" + layer);
                Object value = get.invoke(from);
                if (value == null) {
                    continue;
                }
                java.lang.reflect.Method set = to.getClass().getMethod("set" + layer, get.getReturnType());
                set.invoke(to, value);
                any = true;
            } catch (Throwable ignored) {
                // 该版本/该引擎没有这个访问器 → 跳过
            }
        }
        return any;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, ForeignLightEngine.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
