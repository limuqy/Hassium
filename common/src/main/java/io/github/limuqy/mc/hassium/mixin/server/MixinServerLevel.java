package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 放行影子端在 1.21.6+ 落位虚拟玩家时的阻塞点。
 * <p>
 * 1.21.6 起 vanilla {@code PlayerList.placeNewPlayer} 新增
 * {@code ServerLevel.waitForChunkAndEntities(pos, 1)}：先 {@code getChunk} 同步 join
 * chunk future，再 {@code managedBlock} 等实体加载。影子虚拟玩家的区块由影子主循环
 * （{@code ShadowSeedServer.runMainLoop} → {@code pollTask}）驱动，而该调用恰发生在
 * 主循环线程上 → 自锁：主循环静默停摆、会话零 tracking（1.21.6 fabric 冒烟实证
 * {@code [SHADOW_LOOP] loops=2000} 永不出现、客户端 {@code applied chunks == 0}）。
 * <p>
 * 影子连接为丢弃式 dummy，登录包无需等区块就绪；tracking 由随后的
 * {@code moveVirtualPlayer} + 主循环 pollTask 异步推进。仅影子上下文取消，
 * 真实服务器路径不变。
 * <p>
 * 仅段 G（1.21.6–1.21.8）存在该方法：1.21.9 起 {@code placeNewPlayer} 不再等待区块
 * （{@code waitForChunkAndEntities} 改名 {@code waitForEntities} 且不再 {@code getChunk}），
 * 故门控上界为 {@code < MC_1_21_9}，避免在高段注入不存在的目标而硬失败。
 *
 * @see io.github.limuqy.mc.hassium.compat.ShadowPlayerCompat
 */
@Mixin(ServerLevel.class)
public class MixinServerLevel {

#if MC_VER >= MC_1_21_6 && MC_VER < MC_1_21_9
    @Inject(method = "waitForChunkAndEntities(Lnet/minecraft/world/level/ChunkPos;I)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$skipShadowChunkWait(ChunkPos pos, int radius, CallbackInfo ci) {
        if (RuntimeServerContext.isShadowServerContext()) {
            ci.cancel();
        }
    }
#endif
}
