package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ShadowStructureDataCompat;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStructureDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
#if MC_VER < MC_1_21_2
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
#else
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkSerializer Mixin
 * <p>
 * 拦截区块序列化操作，用于统计序列化/反序列化操作。
 */
@Mixin(
#if MC_VER < MC_1_21_2
    ChunkSerializer.class
#else
    SerializableChunkData.class
#endif
)
public class MixinChunkSerializer implements ShadowStructureDataAccess {

    @Unique
    private static final AtomicLong hassium$totalWriteOperations = new AtomicLong(0);

    @Unique
    private static final AtomicLong hassium$totalReadOperations = new AtomicLong(0);

    @Unique private Tag hassium$rawStructures;

    @Override public Tag hassium$rawStructures() { return hassium$rawStructures; }
    @Override public void hassium$rawStructures(Tag tag) { hassium$rawStructures = tag; }

#if MC_VER >= MC_1_21_2
    // copyOf freezes a snapshot before the asynchronous write; retain opaque data at that boundary.
    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void hassium$retainStructureSnapshot(ServerLevel level, ChunkAccess chunk,
            CallbackInfoReturnable<SerializableChunkData> cir) {
        if (ShadowStructureDataCompat.isCacheOnly(level)) {
            ((ShadowStructureDataAccess) (Object) cir.getReturnValue()).hassium$rawStructures(
                    ShadowStructureDataCompat.snapshot(chunk));
        }
    }
#endif

    /**
     * 拦截区块写入后的操作
     * <p>
     * 在区块被序列化为 NBT 后，记录统计信息。
     */
    @Inject(method = "write", at = @At("RETURN"))
    private
#if MC_VER < MC_1_21_2
    static
#endif
    void hassium$onWrite(
#if MC_VER < MC_1_21_2
            ServerLevel level, ChunkAccess chunk,
#endif
            CallbackInfoReturnable<CompoundTag> cir) {
#if MC_VER < MC_1_21_2
        if (ShadowStructureDataCompat.isCacheOnly(level)) {
            ShadowStructureDataCompat.writeBack(cir.getReturnValue(), ShadowStructureDataCompat.snapshot(chunk));
        }
#else
        ShadowStructureDataCompat.writeBack(cir.getReturnValue(), hassium$rawStructures);
#endif
        hassium$totalWriteOperations.incrementAndGet();
        // 只记计数：size 需整树 toString()（每块保存一次 O(N) 字符串化），仅服务 debug 日志，已移除
        Constants.LOG.debug("Chunk serialized (total writes: {})", hassium$totalWriteOperations.get());
    }

    /**
     * 拦截区块读取操作
     * <p>
     * 在区块从 NBT 反序列化前，记录统计信息。
     */
    @Inject(method = "read", at = @At("HEAD"))
    private
#if MC_VER < MC_1_21_2
    static
#endif
    void hassium$onRead(CallbackInfoReturnable<?> cir) {
        hassium$totalReadOperations.incrementAndGet();
        Constants.LOG.debug("Chunk deserialization started (total reads: {})",
                hassium$totalReadOperations.get());
    }
}
