package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * IOWorker Mixin
 * <p>
 * 拦截 IOWorker 的异步存储操作，用于统计存储操作。
 */
@Mixin(IOWorker.class)
public class MixinIOWorker {

    @Unique
    private static final AtomicLong hassium$totalStoreOperations = new AtomicLong(0);

    /**
     * 拦截存储任务提交
     * review-fix: T13-FixT7Mixin-3：补全描述符精确匹配 delegate 版 store(ChunkPos,CompoundTag)，
     * 1.21.2+ 另有一个 store(ChunkPos,Supplier) overload（delegate 内部调用），无描述符会双计数
     */
    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"))
    private void hassium$onStore(CallbackInfoReturnable<?> cir) {
        hassium$totalStoreOperations.incrementAndGet();
        Constants.LOG.debug("IOWorker.store() called (total: {})", hassium$totalStoreOperations.get());
    }

    /**
     * 拦截关闭操作
     */
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void hassium$onClose(CallbackInfo ci) {
        Constants.LOG.info("IOWorker.close() called, total store operations: {}",
                hassium$totalStoreOperations.get());
    }
}
