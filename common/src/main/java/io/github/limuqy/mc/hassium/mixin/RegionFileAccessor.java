package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

@Mixin(RegionFile.class)
public interface RegionFileAccessor {

    @Invoker("write")
    void invokeWrite(ChunkPos pos, ByteBuffer buffer) throws IOException;

    @Invoker("getOffset")
    int invokeGetOffset(ChunkPos pos);

    @Accessor("file")
    FileChannel getFileChannel();

    @Accessor("offsets")
    IntBuffer getOffsets();

    /**
     * region 目录（{@code RegionFileStorage} 构造时传入的 folder，全七段同名字段）。
     * RegionFile 不携带维度，影子上下文靠它把原版写入路由到对应维度的存储映像。
     */
    @Accessor("externalFileDir")
    Path hassium$getExternalFileDir();
}
