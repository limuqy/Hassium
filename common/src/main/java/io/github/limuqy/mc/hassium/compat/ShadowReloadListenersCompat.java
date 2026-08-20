package io.github.limuqy.mc.hassium.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.tags.TagManager;

/**
 * 影子端 datapack reload 剪枝：仅保留 {@link TagManager}（方块/流体/物品 tag），
 * 跳过 recipe / advancement / function / loot。
 * <p>
 * 1.21.5+ 段 TagManager 已不在 {@code ReloadableServerResources.listeners()}（改走
 * {@code ReloadableServerRegistries}）——此时返回空表（标签已在别处加载）。
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
        List<PreparableReloadListener> kept = new ArrayList<>(1);
        for (PreparableReloadListener listener : original) {
            if (listener instanceof TagManager) {
                kept.add(listener);
            }
        }
        return List.copyOf(kept);
    }
}
