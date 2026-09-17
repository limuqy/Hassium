package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.network.entity.EntityUpdatePacing;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 实体降帧/错峰注入点（逐级降帧 / 热点降帧 / 帧率压力 / 错峰推送的落点）。
 * <p>
 * 只替换 {@code sendChanges()} 门条件里的字段读取，不改方法结构与调用时机：
 * <ul>
 *   <li>门条件里的 {@code this.tickCount}（{@code ordinal = 0}，在 updateInterval 读取之前）→
 *       {@code tickCount + phase}，实现 UUID 相位错峰（总量不变、摊平齐发尖峰）；</li>
 *   <li>{@code this.updateInterval}（门条件里的除数）→ 引擎计算的间隔，永不小于原版；</li>
 *   <li>门条件里的 {@code entity.hasImpulse}（1.21.11 起 {@code needsSync}）→ 受控实体置 false，
 *       使其发包节拍完全由间隔决定。{@code ordinal = 0} 即门条件那一处，方法内后一处读取
 *       （决定是否补发速度包）保持原版语义。</li>
 * </ul>
 * 不用 {@code @Inject(HEAD, cancel)} 的原因见 {@link EntityUpdatePacing} 类注释
 * （会冻结 {@code tickCount} 并跳过尾部 {@code hurtMarked} 分支）。
 * <p>
 * 用 {@code @Redirect} 而非 MixinExtras 的 {@code @ModifyExpressionValue}：后者不属于核心 Mixin
 * （compileOnly 只挂了 {@code org.spongepowered:mixin:0.8.5}），引入 MixinExtras 会新增全加载器依赖。
 */
@Mixin(ServerEntity.class)
public abstract class MixinServerEntity {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private int updateInterval;

    @Shadow
    private int tickCount;

    /**
     * 门条件 {@code this.tickCount % this.updateInterval == 0} 中的 tickCount（方法内第一处 GETFIELD）。
     * 后面的 {@code tickCount % 60} 与 {@code tickCount > 0} 保持原版，故 ordinal 固定为 0。
     */
    @Redirect(method = "sendChanges", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerEntity;tickCount:I", opcode = Opcodes.GETFIELD, ordinal = 0))
    private int hassium$staggeredTickCount(ServerEntity owner) {
        return EntityUpdatePacing.staggeredTickCount(entity, this.tickCount, updateInterval);
    }

    @Redirect(method = "sendChanges", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerEntity;updateInterval:I", opcode = Opcodes.GETFIELD))
    private int hassium$effectiveUpdateInterval(ServerEntity owner) {
        return EntityUpdatePacing.effectiveInterval(entity, updateInterval);
    }

#if MC_VER < MC_1_21_11
    @Redirect(method = "sendChanges", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;hasImpulse:Z", opcode = Opcodes.GETFIELD, ordinal = 0))
    private boolean hassium$gateImpulse(Entity owner) {
        return EntityUpdatePacing.gateImpulse(owner, owner.hasImpulse);
    }
#else
    @Redirect(method = "sendChanges", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;needsSync:Z", opcode = Opcodes.GETFIELD, ordinal = 0))
    private boolean hassium$gateImpulse(Entity owner) {
        return EntityUpdatePacing.gateImpulse(owner, owner.needsSync);
    }
#endif
}
