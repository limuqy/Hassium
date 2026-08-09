package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ServerLevel.entityManager} 访问器：影子端实体通道（applyEntityPacket）需要
 * {@code updateChunkStatus}（实体所在 chunk 可见性置 ENTITY_TICKING，使 getEntity(id)
 * 可查且 saveAll 走安全 store 路径）与 {@code saveAll}（实体随断连保存落盘
 * {@code entities/} 目录，原版 EntityStorage）。
 */
@Mixin(net.minecraft.server.level.ServerLevel.class)
public interface ServerLevelAccessor {

    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> hassium$getEntityManager();
}
