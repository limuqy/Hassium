package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.compat.mods.C2meChunkIoCompat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * region 槽头补丁：按坐标回填 type 126 与 chunkHash。
 * <p>
 * {@code RegionFile.write(ChunkPos, ByteBuffer)} 是所有写入者（vanilla IOWorker /
 * C2ME {@code invokeWriteChunk} / Hassium 自身 {@code invokeWrite}）的**唯一汇合点**，
 * 且只有这里有 {@code ChunkPos}。载荷本体已由
 * {@link MixinRegionFileVersion} 在压缩入口替换为 ZSTD+字典，本处只改 2 个头字段，
 * **不做任何重新压缩**。
 * <p>
 * 对非本兼容层产出的槽是 no-op（签名检测，见
 * {@link C2meChunkIoCompat#patchSector}）。1.20.1 / 1.21.1 / 1.21.11 签名一致，零 {@code #if}。
 */
@Mixin(RegionFile.class)
public class MixinRegionFileWrite {

    @Inject(
            method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",
            at = @At("HEAD"))
    private void hassium$patchSectorHeader(ChunkPos pos, ByteBuffer buffer, CallbackInfo ci) {
        C2meChunkIoCompat.patchSector(pos, buffer);
    }
}
