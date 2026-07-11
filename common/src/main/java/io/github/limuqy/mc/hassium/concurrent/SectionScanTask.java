package io.github.limuqy.mc.hassium.concurrent;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Section 扫描任务数据载体
 * <p>
 * 独立顶级类，避免作为 Mixin 内部类被 Mixin 转换器处理
 * （record 的 compact constructor / invokedynamic 字节码与 Mixin 0.8.7 不兼容）。
 */
public record SectionScanTask(ChunkPos chunkPos, int sectionY, LevelChunkSection section) {}
