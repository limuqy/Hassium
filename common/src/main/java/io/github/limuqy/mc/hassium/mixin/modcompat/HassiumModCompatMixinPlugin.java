package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.compat.mods.ModCompatFlags;
import io.github.limuqy.mc.hassium.compat.mods.EssentialGameRulesCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 外部 Mod 兼容 mixin 的 gating 插件。
 * <p>
 * 与业务 mixin 物理隔离：本 config 为 {@code required: false}，每个兼容钩子均按对应
 * 加载器或外部 Mod 存在性门控。C2ME 存储钩子仅在替换区块 IO 时应用；客户端玩法表
 * 隔离钩子只作用于影子装配，不改变真实世界的数据重载。
 */
public final class HassiumModCompatMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // no-op：不依赖 MixinExtras，也不做早期初始化
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            if (mixinClassName.endsWith(".MixinLithostitchedModifiers")) {
                return HassiumModCompatMixinPlugin.class.getClassLoader().getResource(
                        "dev/worldgen/lithostitched/impl/worldgen/modifier/ModifierManager.class") != null;
            }
            if (mixinClassName.endsWith(".MixinShadowCacheResources")) return true;
            if (mixinClassName.endsWith(".MixinLibXInteractionMetadata")) return ModCompatFlags.libX();
            if (mixinClassName.endsWith(".MixinDeepVoidCultTemple")) return ModCompatFlags.deepVoid();
            if (mixinClassName.endsWith(".MixinMineColoniesCrafterRecipes")) return ModCompatFlags.mineColonies();
            if (mixinClassName.endsWith(".MixinPixelmonReloadListeners")) return ModCompatFlags.pixelmon();
            if (mixinClassName.endsWith(".MixinEssentialGameRules")) {
                return EssentialGameRulesCompat.supportsValueCallbacks() && ModCompatFlags.essential();
            }
            if (mixinClassName.endsWith(".MixinNeoForgePacketSplitter")) return ModCompatFlags.neoForge();
            boolean apply = ModCompatFlags.c2meChunkIoReplaced();
            if (apply) {
                // 结构信号：mixin 将被应用（与是否发生 RegionFileVersion.wrap 流量无关）。
                io.github.limuqy.mc.hassium.compat.mods.ModCompatStats.onCompatArmed();
            }
            return apply;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".MixinLibXInteractionMetadata")) {
            int count = io.github.limuqy.mc.hassium.compat.mods.LibXInteractStackCompat.repair(targetClass);
            if (count > 0) org.slf4j.LoggerFactory.getLogger("Hassium/ModCompat").info(
                    "Hassium: repaired {} LibX interaction maxStack metadata before local capture", count);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".MixinShadowCacheResources")) {
            int count = io.github.limuqy.mc.hassium.compat.ShadowCacheResourceCallbacks.guard(targetClass);
            org.slf4j.LoggerFactory.getLogger("Hassium/ModCompat").info(
                    "Hassium: scoped {} resource constructor callbacks out of cache-only assembly", count);
        }
        if (mixinClassName.endsWith(".MixinEssentialGameRules")) {
            int guarded = EssentialGameRulesCompat.guardShadowCallbacks(targetClass);
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("Hassium/ModCompat");
            if (guarded > 0) logger.info("Hassium: guarded {} Essential gamerule callback(s) for shadow servers", guarded);
            else logger.warn("Hassium: Essential gamerule callback not found; shadow compatibility guard was not applied");
        }
    }
}
