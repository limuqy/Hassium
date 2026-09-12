package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.compat.mods.ModCompatFlags;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 外部 Mod 兼容 mixin 的 gating 插件。
 * <p>
 * 与业务 mixin 物理隔离：本 config 为 {@code required: false}，且只有检测到 C2ME 自实现
 * 区块 IO 时才应用。任一步失败一律保守返回 false —— 兼容层失效只会退化为"type 126 不产生"，
 * 绝不影响 Hassium 主链路或客户端的启动。
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
            return ModCompatFlags.c2meChunkIoReplaced();
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
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}
