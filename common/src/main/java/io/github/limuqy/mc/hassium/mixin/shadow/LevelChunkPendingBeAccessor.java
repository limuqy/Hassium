package io.github.limuqy.mc.hassium.mixin.shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 影子端落盘前清理 pending BE 用：{@code pendingBlockEntities} 定义在
 * {@code ChunkAccess}（protected final，1.20.1–1.21.11 同名），vanilla 只在
 * {@code getBlockEntity(CREATE)} / FULL 转换时 promote。影子 flush 序列化走
 * {@code ChunkSerializer.write} → {@code getBlockEntityNbtForSaving} → promote，
 * 遇「NBT 与方块状态不一致」打 vanilla ERROR（{@code Failed to create block entity}）
 * 并跳过该 BE。提前剔除 = 与 vanilla promote 失败分支同语义，落盘 NBT 完全一致。
 */
@Mixin(ChunkAccess.class)
public interface LevelChunkPendingBeAccessor {

    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> hassium$getPendingBlockEntities();
}
