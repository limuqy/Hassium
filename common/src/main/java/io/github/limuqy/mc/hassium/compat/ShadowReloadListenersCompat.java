package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_2
import java.util.ArrayList;
import net.minecraft.tags.TagManager;
#endif
#if MC_VER >= MC_1_21_1 && MC_VER < MC_1_21_2
import net.minecraft.world.item.crafting.RecipeManager;
#endif
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
#if MC_VER < MC_1_21_1
        // <1.21.1（含 1.20.1）：fabric-resource-loader 0.11.x 的 sort 不需要 RecipeManager，
        // 仅保留 TagManager（方块/流体/物品 tag），跳过 recipe/advancement/function。
        List<PreparableReloadListener> kept = new ArrayList<>(1);
        for (PreparableReloadListener listener : original) {
            if (listener instanceof TagManager) {
                kept.add(listener);
            }
        }
        return List.copyOf(kept);
#elif MC_VER < MC_1_21_2
        // 1.21.1–1.21.8：fabric-resource-loader ≥1.x 的 ResourceManagerHelperImpl.sort
        // （Mixin 进 SimpleReloadInstance.create）要求 listeners 里存在 RecipeManager，
        // 以其 registries 构建 wrapper HolderLookup.Provider，缺失即抛
        // IllegalStateException("No RecipeManager found in listeners!")。因此除 TagManager
        // 外额外保留 RecipeManager（reload 会真实执行，影子端不消费配方，仅少量耗时）；
        // 非 fabric 加载器无此要求，维持仅 TagManager 剪枝，行为不变。
        boolean keepRecipes = Services.PLATFORM.isModLoaded("fabric-resource-loader-v0");
        List<PreparableReloadListener> kept = new ArrayList<>(2);
        for (PreparableReloadListener listener : original) {
            if (listener instanceof TagManager || (keepRecipes && listener instanceof RecipeManager)) {
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
