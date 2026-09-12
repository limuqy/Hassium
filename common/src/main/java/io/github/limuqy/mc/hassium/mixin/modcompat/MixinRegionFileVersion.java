package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.compat.mods.C2meChunkIoCompat;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.OutputStream;

/**
 * 外部区块 IO 压缩入口接管（C2ME {@code ioSystem.replaceImpl}）。
 * <p>
 * {@code RegionFileVersion.wrap(OutputStream)} 是区块载荷压缩的**唯一**入口：
 * vanilla 的 {@code RegionFile.getChunkDataOutputStream()} 与 C2ME 自实现的
 * {@code C2MEStorageThread} 都经它。在本兼容层的门控下，vanilla 路径已被
 * {@code MixinRegionFile} 的 HEAD 取消，故此处只可能是外部 IO 调用。
 * <p>
 * 1.20.1 / 1.21.1 / 1.21.11 该方法签名一致
 * （{@code (Ljava/io/OutputStream;)Ljava/io/OutputStream;}，已逐版本核对），故零 {@code #if}。
 */
@Mixin(RegionFileVersion.class)
public class MixinRegionFileVersion {

    @Inject(
            method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;",
            at = @At("HEAD"),
            cancellable = true)
    private void hassium$takeOverChunkPayload(OutputStream out, CallbackInfoReturnable<OutputStream> cir) {
        OutputStream replaced = C2meChunkIoCompat.wrap(out);
        if (replaced != null) {
            cir.setReturnValue(replaced);
        }
    }
}
