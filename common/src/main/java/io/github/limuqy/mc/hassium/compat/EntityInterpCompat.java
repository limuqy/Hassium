package io.github.limuqy.mc.hassium.compat;

import net.minecraft.world.entity.Entity;

/**
 * 实体插值窗口跨版本收口（实体降帧配套，见 MixinClientPacketListener）。
 * <p>
 * 26.1.2–26.2：客户端 {@code InterpolationHandler.setInterpolationLength(steps)}
 * 直接写入自适应步数，原版 moveOrInterpolateTo 按该步数插值。
 * 26.3：步数不再由客户端控制——插值窗口随服务端 {@code PositionPath.Stepped}
 * 在包内编码（{@code SteppedInterpolationTracker}），此处无事可做；
 * 节奏表观测仍在调用方保留（服务端实体包构建可复用）。
 */
public final class EntityInterpCompat {

    private EntityInterpCompat() {
    }

    /** 把自适应步数写入实体插值窗口；null（无插值处理器）走原版直接 setPos 路径。 */
    public static void applyAdaptiveSteps(Entity entity, int steps) {
#if MC_VER < MC_26_3
        net.minecraft.world.entity.InterpolationHandler interpolation = entity.getInterpolation();
        if (interpolation != null) {
            interpolation.setInterpolationLength(steps);
        }
#endif
    }
}
