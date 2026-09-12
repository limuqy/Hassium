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

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, ForeignLightEngine.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
