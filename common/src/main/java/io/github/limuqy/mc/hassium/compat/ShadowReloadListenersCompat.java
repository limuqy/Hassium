package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.platform.Services;
import java.util.List;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * 影子端 datapack reload 剪枝：保留 {@link TagManager}（方块/流体/物品 tag），
 * 跳过 advancement / function；recipe 视版本与加载器而定（见 {@link #filterForShadow}）。
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
        // ≥1.21.2：listeners() 只含 recipes/functions/advancements，无 tag 可剪
        return List.copyOf(original);
    }
}
