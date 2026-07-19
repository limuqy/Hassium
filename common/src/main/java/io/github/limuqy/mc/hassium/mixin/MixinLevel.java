package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 跳过 renderOnly（超视渲染）区块的 blockEntity tick。
 * <p>
 * 注入 {@link Level#tickBlockEntity} HEAD：若 BE 所属 chunk 在 renderOnly 集合中则 cancel。
 * 仅客户端生效（服务端 {@code instanceof ClientLevel} 为 false 直接 return）。
 * <p>
 * {@code require = 0}：若跨版本方法名变化（如 1.21.x 重命名），静默不注入而非崩溃；
 * 此时 renderOnly BE tick 会按原版进行（主要是无害的客户端动画），不影响功能。
 */
@Mixin(Level.class)
public class MixinLevel {

    @Inject(method = "tickBlockEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void hassium$skipRenderOnlyBETick(BlockEntity blockEntity, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (!(self instanceof ClientLevel)) {
            return;
        }
        IClientLevelExtension ext = (IClientLevelExtension) self;
        ChunkPos pos = new ChunkPos(blockEntity.getBlockPos());
        if (ext.hassium$isRenderOnly(pos)) {
            ci.cancel();
        }
    }
}
