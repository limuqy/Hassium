package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：拦截 {@code PlayerChunkSender.sendChunk}，对 Hassium 客户端发送元数据替代原版区块包。
 * <p>
 * 1.20.2 移除了 {@code ServerPlayer.trackChunk}，初始区块发送改走
 * {@code PlayerChunkSender.sendChunk}（private static）。此 Mixin 在 1.20.2+ 替代
 * {@link MixinServerPlayer} 的 trackChunk 注入。
 * <p>
 * 区块更新广播仍由 {@link MixinChunkHolder} 拦截。
 * <p>
 * 1.20.1 无 {@code PlayerChunkSender}，挂空壳到 {@code MinecraftServer}
 * 以满足 mixins.json 注册（同 {@link MixinClientCommonPacketListenerImpl} 模式）。
 */
#if MC_VER >= MC_1_21_1
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinPlayerChunkSender {

#if MC_VER >= MC_1_21_1
    @org.spongepowered.asm.mixin.Shadow
    private boolean memoryConnection;
    @org.spongepowered.asm.mixin.Shadow
    private float desiredChunksPerTick;
    @org.spongepowered.asm.mixin.Shadow
    private float batchQuota;
    @org.spongepowered.asm.mixin.Shadow
    private it.unimi.dsi.fastutil.longs.LongSet pendingChunks;
    @org.spongepowered.asm.mixin.Shadow
    private int unacknowledgedBatches;
    @org.spongepowered.asm.mixin.Shadow
    private int maxUnacknowledgedBatches;

    @org.spongepowered.asm.mixin.Unique
    private boolean hassium$forceQuota;

    // [BATCH-SRV] 诊断探针：vanilla 批协议供给环（批次发出 / 客户端 ACK / 周期状态）。
    // 定位 neoforge ≥1.21.1 推送坍缩——runnability 依赖 unacknowledgedBatches < maxUnacknowledgedBatches，
    // 该闸由客户端 ServerboundChunkBatchReceived ACK 驱动。探针临时，闭环后移除。
    @org.spongepowered.asm.mixin.Unique
    private static int hassium$batchSentCount;
    @org.spongepowered.asm.mixin.Unique
    private static int hassium$ackCount;
    @org.spongepowered.asm.mixin.Unique
    private static int hassium$dropCount;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$probeTick;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$preUnacked;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$probeVd = -1;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$probeReqVd = -1;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$probeLoaded = -1;

    @org.spongepowered.asm.mixin.Unique
    private void hassium$batchProbe(String event) {
        io.github.limuqy.mc.hassium.Constants.LOG.info(
                "[BATCH-SRV] {} unacked={}/{} desired={} quota={} pending={} vd={}/req={} loaded={}",
                event, this.unacknowledgedBatches, this.maxUnacknowledgedBatches,
                this.desiredChunksPerTick, this.batchQuota, this.pendingChunks.size(),
                this.hassium$probeVd, this.hassium$probeReqVd, this.hassium$probeLoaded);
    }

    /**
     * [BATCH-LIGHT] 探针：环带内 holder 的 sendSync 完成度 + 光照任务队列长度。
     * 定位「mark 停滞」：vanilla 在 protoChunkToFullChunk 时对邻域 addSendDependency(
     * lightEngine.waitForPendingTasks)，光照任务不排空 → sendSync 永不完成 → 永不 mark。
     */
    @org.spongepowered.asm.mixin.Unique
    private static void hassium$ringProbe(ServerPlayer player) {
        try {
            net.minecraft.server.level.ServerLevel level =
                    io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
            if (level == null) {
                return;
            }
            net.minecraft.server.level.ChunkMap chunkMap = level.getChunkSource().chunkMap;
            io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor accessor =
                    (io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor) chunkMap;
            int lightQueue = -1;
            if (level.getLightEngine() instanceof net.minecraft.server.level.ThreadedLevelLightEngine threaded) {
                lightQueue = ((io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor) (Object) threaded)
                        .hassium$getLightTasks().size();
            }
            net.minecraft.world.level.ChunkPos center = player.chunkPosition();
            int vd = io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player) + 2;
            int finished = 0, unfinished = 0, missing = 0;
            int tracked = 0;
            net.minecraft.server.level.ChunkTrackingView trackingView = player.getChunkTrackingView();
            for (int dx = -vd; dx <= vd; dx++) {
                for (int dz = -vd; dz <= vd; dz++) {
                    if (dx * dx + dz * dz > vd * vd) {
                        continue;
                    }
                    boolean inTrackingView = trackingView.contains(center.x + dx, center.z + dz);
                    if (inTrackingView) {
                        tracked++;
                    }
                    net.minecraft.server.level.ChunkHolder holder = accessor.hassium$getVisibleChunkIfPresent(
                            net.minecraft.world.level.ChunkPos.asLong(center.x + dx, center.z + dz));
                    if (holder == null) {
                        missing++;
                    } else if (holder.getSendSyncFuture().isDone()) {
                        finished++;
                    } else {
                        unfinished++;
                    }
                }
            }
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[BATCH-LIGHT] ring r={} finished={} unfinished={} missing={} lightQueue={} playerTracked={} trackingView={}",
                    vd, finished, unfinished, missing, lightQueue, tracked,
                    trackingView.getClass().getSimpleName());
        } catch (Throwable t) {
            // 探针兜底：首次失败打印原因，之后静默
            if (!hassium$ringProbeFailed) {
                hassium$ringProbeFailed = true;
                io.github.limuqy.mc.hassium.Constants.LOG.warn(
                        "[BATCH-LIGHT] ring probe failed once", t);
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean hassium$ringProbeFailed;

    /**
     * [BATCH-CHAN] 探针：网关会话通道可写性（netty 出站水位）。
     * full 推送最后一道闸 = GatewayChannel.isWritable；若高水位后永不回落，
     * 推送/整柱链路全部在此阻塞。每 100 tick 与 ringProbe 同步采样。
     */
    @org.spongepowered.asm.mixin.Unique
    private static void hassium$channelProbe(ServerPlayer player) {
        try {
            io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession session =
                    io.github.limuqy.mc.hassium.network.gateway.GatewayServer.getInstance()
                            .registry().get(player.getUUID());
            if (session == null) {
                io.github.limuqy.mc.hassium.Constants.LOG.info("[BATCH-CHAN] session=null");
                return;
            }
            io.github.limuqy.mc.hassium.network.gateway.GatewayChannel gc = session.channel();
            io.netty.channel.Channel nch = gc.channel();
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[BATCH-CHAN] writable={} nettyWritable={} bytesBeforeUnwritable={} isActive={} state={}",
                    gc.isWritable(), nch.isWritable(), nch.bytesBeforeUnwritable(), nch.isActive(), gc.state());
        } catch (Throwable t) {
            if (!hassium$ringProbeFailed) {
                hassium$ringProbeFailed = true;
                io.github.limuqy.mc.hassium.Constants.LOG.warn("[BATCH-CHAN] probe failed once", t);
            }
        }
    }

    /**
     * 源头定额：把原版 {@code sendNextChunks} 的 batch 钳到 {@code maxChunksPerTick}。
     * 与压缩/网关会话无关；{@link #hassium$onChunkPacketSend} 仍按会话决定是否改走元数据。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void hassium$capSourceRate(ServerPlayer player, CallbackInfo ci) {
        hassium$preUnacked = this.unacknowledgedBatches;
        hassium$forceQuota = io.github.limuqy.mc.hassium.server.RuntimeServerContext.isDedicatedServerContext();
        try {
            hassium$probeReqVd = player.requestedViewDistance();
            hassium$probeVd = io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player);
            hassium$probeLoaded = io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player)
                    .getChunkSource().getLoadedChunksCount();
        } catch (Throwable ignored) {
            // 探针兜底：视距读取失败不影响主流程
        }
        if (++hassium$probeTick % 100 == 0) {
            hassium$batchProbe("tick100#" + hassium$probeTick);
            hassium$ringProbe(player);
            hassium$channelProbe(player);
        }
        if (!hassium$forceQuota) {
            return;
        }
        int max = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        if (max <= 0) {
            max = 4;
        }
        if (desiredChunksPerTick > max) {
            desiredChunksPerTick = max;
        }
        if (batchQuota > max) {
            batchQuota = max;
        }
    }

    /** [BATCH-SRV] 批次实际发出（unacked 增量）→ 节流记录。 */
    @Inject(method = "sendNextChunks", at = @At("TAIL"))
    private void hassium$afterSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        if (this.unacknowledgedBatches > hassium$preUnacked) {
            int n = ++hassium$batchSentCount;
            if (n <= 10 || n % 50 == 0) {
                hassium$batchProbe("batch-sent#" + n);
            }
        }
    }

    /** [BATCH-SRV] 客户端 ACK 到达 vanilla 处理器 → 节流记录。 */
    @Inject(method = "onChunkBatchReceivedByClient", at = @At("TAIL"))
    private void hassium$afterChunkBatchAck(float f, CallbackInfo ci) {
        int n = ++hassium$ackCount;
        if (n <= 10 || n % 50 == 0) {
            hassium$batchProbe("client-ack#" + n + " f=" + f);
        }
    }

    /**
     * 本机连接（integrated）会无视 quota 一次吐完全部 pending。Hassium 路径强制走定额 nearest-N。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "collectChunksToSend",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;memoryConnection:Z"))
    private boolean hassium$quotaLimitedCollect(net.minecraft.server.network.PlayerChunkSender self) {
        return !hassium$forceQuota && memoryConnection;
    }

    /**
     * 截获原版已构造的首个 level-chunk packet。Hassium 客户端复用此快照计算 hash/编码，
     * 只原地替换 light payload；不再从 {@link net.minecraft.world.level.chunk.LevelChunk} 重建。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "sendChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0))
    private static void hassium$onChunkPacketSend(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            listener.send(packet);
            return;
        }
        ServerPlayer player = listener.getPlayer();
        // 网关会话在物化后即标识 Hassium 客户端；无会话时严格保留原版发送。
        if (GatewayServer.getInstance().registry().get(player.getUUID()) == null) {
            listener.send(packet);
            return;
        }

        ChunkPos pos = new ChunkPos(chunkPacket.getX(), chunkPacket.getZ());
        String dimension = LevelCompat.getDimensionId(player.level());
        ServerChunkPushManager.getInstance().submitMetadataTask(player, pos, chunkPacket, dimension);
        // 不调用 listener.send：自有 hash/full 路径取代这份原版区块包。
    }

    /** PlayerChunkSender.dropChunk 是 1.20.2+ 的精确 tracking-view 移除回调。 */
    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void hassium$onDropChunk(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        // [BATCH-SRV] 探针：untrack 事件计数（pending 只增于 mark、减于 send/drop，
        // pending 枯竭而送达数不足时 drop 是唯一出口——节流记录）
        int n = ++hassium$dropCount;
        if (n <= 10 || n % 50 == 0) {
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[BATCH-SRV] drop#{} pos={}", n, pos);
        }
        if (GatewayServer.getInstance().registry().get(player.getUUID()) == null) {
            return;
        }
        String dimension = LevelCompat.getDimensionId(player.level());
        ServerChunkPushManager.getInstance().discardUntrackedChunk(player.getUUID(), dimension, pos);
    }
#endif
}
