package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_2
import java.util.ArrayList;
import net.minecraft.tags.TagManager;
#endif
import java.util.List;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * 影子端 datapack reload 剪枝：仅保留 {@link TagManager}（方块/流体/物品 tag），
 * 跳过 recipe / advancement / function / loot。
 * <p>
 * 1.21.2 起 {@code TagManager} 类移除、tag 改走 {@code Registry.PendingTags.apply()}，
 * {@code listeners()} 返回值本就不含 tag —— 此时原样透传。
 * 跨版本差异收口于此，业务 Mixin 禁止散落 {@code #if}。
 */
public final class ShadowReloadListenersCompat {

    private ShadowReloadListenersCompat() {}

    /**
     * @param original {@code ReloadableServerResources.listeners()} 原返回值
     * @return 影子上下文下的精简列表（可为空）
     */
    public static List<PreparableReloadListener> filterForShadow(List<PreparableReloadListener> original) {
        if (original == null || original.isEmpty()) {
            return List.of();
        }
#if MC_VER < MC_1_21_2
        List<PreparableReloadListener> kept = new ArrayList<>(1);
        for (PreparableReloadListener listener : original) {
            if (listener instanceof TagManager) {
                kept.add(listener);
            }
        }
        return List.copyOf(kept);
#else
        // ≥1.21.2：listeners() 只含 recipes/functions/advancements，无 tag 可剪
        return List.copyOf(original);
#endif
    }
}
