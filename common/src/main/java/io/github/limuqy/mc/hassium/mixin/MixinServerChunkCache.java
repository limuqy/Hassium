package io.github.limuqy.mc.hassium.mixin;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端区块缓存：影子端光照更新桥梁（T2）+ 区块取数桥。
 * <p>
 * 影子服务端（客户端进程内的 world 后端）引擎每完成一个 section 的光照计算，
 * 写数据层前会调用 {@code ServerChunkCache.onLightUpdate(LightLayer, SectionPos)}——
 * HEAD 拦截转发到 {@link ShadowLightCompute#collectLightUpdate}（light 线程入口，
 * ConcurrentHashMap + synchronized(mask) 线程安全收集，绝对 sectionY）；
 * 客户端主线程帧尾 {@code drainLightMasks} 攒批打包入回传队列。
 * <p>
 * 门控：仅影子服务端上下文生效（{@link RuntimeServerContext#isShadowServerContext()}）；
 * 专用服务器 / 普通集成服务器不受影响——onLightUpdate 路径零额外开销。
 * <p>
 * 1.20.1 / 1.21.11 方法签名一致（已双版本验证），零 #if。
 */
@Mixin(net.minecraft.server.level.ServerChunkCache.class)
public class MixinServerChunkCache {

    /**
     * 影子服务端：光照 section 计算完成 → 收集（绝对 sectionY）。
     */
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void hassium$onLightUpdate(LightLayer layer, SectionPos sectionPos, CallbackInfo ci) {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        ShadowLightCompute.collectLightUpdate(layer, sectionPos);
    }

    /**
     * 影子服务端：光照传播取数桥（T2）。
     * <p>
     * 原版 {@code getChunkForLighting} 只查 ChunkMap 的 ChunkHolder——注入区块
     * （{@link ShadowSeedServer#injectedChunk} 表）不经 ChunkMap 正规加载、无
     * ChunkHolder，传播（{@code LightEngine.getState} / {@code propagateLightSources}）
     * 取数恒 null → 方块按 BEDROCK、发光源不推，光照算不出（全黑/错光）。
     * HEAD 拦截：影子端上下文优先返回注入表区块，其余路径不动。
     * <p>
     * 1.20.1 / 1.21.11 方法签名一致（{@code LightChunk getChunkForLighting(int, int)}），
     * 零 #if。
     */
    @Inject(method = "getChunkForLighting", at = @At("HEAD"), cancellable = true)
    private void hassium$shadowChunkForLighting(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        LevelChunk chunk = server.injectedChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    /**
     * 影子服务端：区块取数桥（防后台线程 getChunk join 死锁）。
     * <p>
     * 原版 {@code ServerChunkCache.getChunk(int, int, ChunkStatus, boolean)} 查 ChunkMap
     * 的 ChunkHolder——注入区块（{@link ShadowSeedServer#injectedChunk} 表）不经 ChunkMap
     * 正规加载、无 ChunkHolder，非主线程调用会触发异步加载 future 并 {@code join()}
     * 永久阻塞（影子端不跑 ChunkMap tick，future 永不完成）——consumeLoop 应用
     * SectionDelta 时 BE load（如 SpawnerBlockEntity.load → Level.getBlockState）即死锁，
     * 注入路径整体瘫痪（2026-08-14 定位）。HEAD 拦截：影子端上下文优先返回注入表
     * 区块（恒 FULL，满足一切 ≤FULL 请求），其余路径不动。
     * <p>
     * 1.20.1 / 1.21.11 方法签名一致（{@code ChunkAccess getChunk(int, int, ChunkStatus, boolean)}），
     * 零 #if。
     */
    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$shadowGetChunk(int x, int z, ChunkStatus status, boolean load,
                                        CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> cir) {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        LevelChunk chunk = server.injectedChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }
}
