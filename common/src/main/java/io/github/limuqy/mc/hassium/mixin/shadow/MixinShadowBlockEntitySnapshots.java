package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.compat.BlockEntityCompat;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshotAccess;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshots;
import net.minecraft.core.BlockPos;
#if MC_VER >= MC_1_21_1
import net.minecraft.core.HolderLookup;
#endif
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only authoritative shadow columns carry snapshots. Normal/server-generated chunks remain native. */
@Mixin(LevelChunk.class)
public class MixinShadowBlockEntitySnapshots implements ShadowBlockEntitySnapshotAccess {
    @Unique private ShadowBlockEntitySnapshots hassium$beSnapshots;

    @Override public ShadowBlockEntitySnapshots hassium$blockEntitySnapshots() {
        if (hassium$beSnapshots == null) hassium$beSnapshots = new ShadowBlockEntitySnapshots();
        return hassium$beSnapshots;
    }

    @Inject(method = "getBlockEntityNbtForSaving", at = @At("HEAD"), cancellable = true)
    private void hassium$saveReceivedTag(BlockPos pos,
#if MC_VER >= MC_1_21_1
                                        HolderLookup.Provider registries,
#endif
                                        CallbackInfoReturnable<CompoundTag> cir) {
        if (hassium$beSnapshots == null || !hassium$beSnapshots.contains(pos)) return;
        CompoundTag tag = hassium$beSnapshots.get(pos);
        LevelChunk self = (LevelChunk) (Object) this;
        cir.setReturnValue(BlockEntityCompat.canPromote(tag, self.getBlockState(pos)) ? tag : null);
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void hassium$removeReceivedTag(BlockPos pos, CallbackInfo ci) {
        if (hassium$beSnapshots != null) hassium$beSnapshots.remove(pos);
    }

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void hassium$clearReceivedTags(CallbackInfo ci) {
        if (hassium$beSnapshots != null) hassium$beSnapshots.clear();
    }
}
