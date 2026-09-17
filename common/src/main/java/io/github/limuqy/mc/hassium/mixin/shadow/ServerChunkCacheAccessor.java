package io.github.limuqy.mc.hassium.mixin.shadow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ServerChunkCache} 访问器：1.21.5+ 的 {@code ticketStorage}（ticket API
 * 从 {@code ServerChunkCache.addRegionTicket}/{@code DistanceManager} 收敛至
 * {@link net.minecraft.world.level.TicketStorage}）。1.21.5 以下无此字段/类，方法
 * 不编译（空接口，mixins.json 常驻注册）。
 */
@Mixin(net.minecraft.server.level.ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

#if MC_VER >= MC_1_21_5
    @Accessor("ticketStorage")
    net.minecraft.world.level.TicketStorage hassium$getTicketStorage();
#endif
}
