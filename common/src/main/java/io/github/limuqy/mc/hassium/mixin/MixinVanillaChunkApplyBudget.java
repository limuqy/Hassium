package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 原版 chunk packet（{@code ClientboundLevelChunkWithLightPacket}）apply 预算化。
 * <p>
 * 官方 chunk batch 平滑（{@code PlayerChunkSender} + ack 测速）自 1.20.2 才引入，
 * 且客户端 apply 在所有版本都是「收到即 apply」、无每帧预算；1.20.1 服务端更是
 * FULL 即发无节流（进服首波预生成区密集直灌 → 客户端主线程卡顿尖峰）。
 * <p>
 * 本 Mixin 把原版 chunk apply 移入 {@link MainThreadDispatcher} 距离优先级预算队列，
 * 与压缩通道 / 缓存 apply 共享 {@code ClientMainThreadBudget} 每帧时间预算，
 * 由近到远平滑落地（与 1.21.11 官方 batch 观感一致）。
 * 1.21.11 起官方 batch + 客户端测速已足够平滑，不注入。
 * <p>
 * 延迟执行安全性：packet 的 chunkData 在解码时已整体拷贝为 {@code byte[]}
 * （1.20.1~1.21.10 均如此，无引用计数 buffer），捕获闭包安全；
 * 断连时 {@link MainThreadDispatcher#clearClient()} 会丢弃排队任务。
 * 区块在排队期间移出视距时，原版 {@code replaceWithPacketData} 的 inRange 检查静默丢弃。
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinVanillaChunkApplyBudget {

    @Shadow
    private ClientLevel level;

    /** 预算任务内调用原方法时的重入标志（防 HEAD 注入再次入队形成死循环） */
    private static final ThreadLocal<Boolean> BUDGETED_APPLY = ThreadLocal.withInitial(() -> Boolean.FALSE);

#if MC_VER < MC_1_21_11
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"), cancellable = true)
    private void hassium$budgetVanillaChunkApply(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (BUDGETED_APPLY.get()) {
            return; // 预算任务内调用原方法：放行
        }
        if (io.github.limuqy.mc.hassium.network.ClientChunkHandler.isHassiumApplyInProgress()) {
            return; // Hassium 预算内 apply（缓存读回/OVD/压缩通道）：放行，避免入队后 hasChunk 假失败
        }
        if (!hassium$shouldBudgetVanillaApply()) {
            return;
        }
        MainThreadDispatcher.execute(() -> {
            if (level == null) {
                return; // 会话已结束（队列残留）
            }
            BUDGETED_APPLY.set(Boolean.TRUE);
            try {
                hassium$invokeHandleLevelChunkWithLight(packet);
            } finally {
                BUDGETED_APPLY.set(Boolean.FALSE);
            }
        }, new ChunkPos(packet.getX(), packet.getZ()));
        ci.cancel();
    }

    @Invoker("handleLevelChunkWithLight")
    abstract void hassium$invokeHandleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet);

    private static boolean hassium$shouldBudgetVanillaApply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSingleplayerServer() != null) {
            return false; // 单人 / 局域网保持原版即时 apply（存储逻辑本就保持原版格式）
        }
        return HassiumConfigService.getInstance().isClientCacheEnabled();
    }
#endif
}
