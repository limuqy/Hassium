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
}
