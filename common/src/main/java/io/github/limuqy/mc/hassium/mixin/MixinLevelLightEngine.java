package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.PromethiumLightBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版局部光照更新重定向（并行引擎模式）。
 * <p>
 * {@code LevelChunk.setBlockState → lightEngine.checkBlock} 是客户端全部方块变化
 * （放置/破坏/流体/服务端方块包）触发局部光照更新的唯一入口，此前在主线程任意时刻
 * 内联写官方引擎（blockNodesToCheck 标记，真实重推导在 runLightUpdates 读当前方块状态）。
 * <p>
 * 引擎开启时改道 {@link PromethiumLightBridge#submitLocalUpdate} 入引擎统一队列，
 * 由单一消费者（drainCompletions）与重算结果按序应用：
 * <ul>
 *   <li>消除「局部更新先传播、旧快照重算后落地覆盖修正」的陈旧光竞态（同柱更新
 *       挂到在飞任务上，落地后立即重推导）；</li>
 *   <li>官方引擎写入收敛到单一消费点——主线程不再内联写引擎，是引擎写入异步化的前提。</li>
 * </ul>
 * 豁免（直通原版）：
 * <ul>
 *   <li>引擎缺席 / 配置关闭 / 引擎旧版无 submitLocalUpdate：零行为差；</li>
 *   <li>引擎消费窗口内（drain 内引擎自身原语调用，经 bridge consuming 门）；</li>
 *   <li>非客户端引擎：单机集成服务端与客户端同 JVM，{@code ThreadedLevelLightEngine.checkBlock}
 *       经 {@code super.checkBlock} 也命中本注入——服务端世界生成/方块更新的光照检查必须
 *       留在服务端线程原样处理（重定向会吞掉服务端引擎的检查 → 服务端光照永不更新），
 *       以引擎对象身份与 {@code Minecraft.level.getLightEngine()} 比对排除。</li>
 * </ul>
 * 延迟应用语义安全：checkBlock 仅标记，runLightUpdates 按应用时刻的当前方块状态重推导。
 */
@Mixin(LevelLightEngine.class)
public class MixinLevelLightEngine {

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void hassium$deferLocalLightUpdate(BlockPos pos, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || (Object) this != level.getLightEngine()) {
            // 客户端关卡未就绪 / 非客户端引擎（单机集成服务端）：直通原版
            return;
        }
        if (PromethiumLightBridge.deferLocalLightUpdate()) {
            PromethiumLightBridge.submitLocalUpdate(pos);
            ci.cancel();
        }
    }
}
