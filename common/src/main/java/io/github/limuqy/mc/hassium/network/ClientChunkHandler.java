package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.ChunkOutOfViewException;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;


import java.util.List;



/**
 * 客户端区块处理器门面（Phase 0 隔离重构后）。
 * <p>
 * 全部可变状态（storage、pending hash 表、apply 重入标志）已迁入
 * {@link ClientChunkPipeline} 实例字段；本类保留既有 public static 签名
 * 供调用方零改动转发，处理逻辑经 {@link ClientChunkPipeline#getInstance()} 访问状态。
 * Phase 4 完成后删除本门面，调用方直指 pipeline 实例。
 */
public class ClientChunkHandler {
    /** 仅诊断链路使用的区块数据来源；绝不编码进网络包或存档。 */
    public enum TraceOrigin {
        SERVER_PUSH("server_push"),
        SHADOW_MEMORY_CACHE("shadow_memory_cache"),
        SHADOW_DISK_CACHE("shadow_disk_cache"),
        LOCAL_GENERATION("local_generation");

        private final String logValue;

        TraceOrigin(String logValue) {
            this.logValue = logValue;
        }
    }

    /** 仅在区块应用日志开启时让内部队列携带来源元数据。 */
    public static TraceOrigin traceOriginIfLoggingEnabled(TraceOrigin origin) {
        return DebugLogger.isEnabled(LogType.CHUNK_APPLY) ? origin : null;
    }

    /** 影子端官方通道落地的诊断事件；来源为 null 表示开启日志前已入队。 */
    public static void logShadowChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, TraceOrigin origin) {
        logChunkApplyEvent(phase, pos, renderOnly, Minecraft.getInstance(), origin);
    }


    /**
     * 重置客户端缓存存储（断开连接时调用，转发 pipeline）
     */
    public static void resetStorage() {
        ClientChunkPipeline.getInstance().resetStorage();
    }

    /**
     * 最终落地回调：成功消费带正 {@code deliveryId} 的权威投递即进入 ACK 聚合器。
     * OVD / {@code redirect_render_only} 仍会落地该包（{@code renderOnly=true}），必须 ACK 以释放服务端 in-flight。
     * 接收、解码、提交失败不得记为成功。
     */
    public static void recordAuthoritativeApply(long deliveryId, boolean renderOnly, boolean applied) {
        if (shouldRecordAuthoritativeApply(deliveryId, renderOnly, applied)) {
            ClientChunkPipeline.getInstance().recordAuthoritativeApply(deliveryId);
        }
    }

    static boolean shouldRecordAuthoritativeApply(long deliveryId, boolean renderOnly, boolean applied) {
        return applied && deliveryId > 0L;
    }

    /** 断线时清除本会话尚未发送的 delivery ACK。 */
    public static void clearChunkApplyAcks() {
        ClientChunkPipeline.getInstance().clearChunkApplyAcks();
    }

    /** 客户端 tick 尾冲刷本连接已完成的 authoritative delivery。 */
    public static void flushChunkApplyAcks() {
        ClientChunkPipeline.getInstance().flushChunkApplyAcks();
    }

    /**
     * 暂存 contentHash，供后续收到区块数据时使用（转发 pipeline）
     */
    public static void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        ClientChunkPipeline.getInstance().storePendingContentHash(chunkX, chunkZ, contentHash);
    }

    /**
     * 处理接收到的压缩区块数据
     * <p>
     * 解码在调用者线程（主线程），ZSTD 解压提交到后台线程池，
     * 解压完成后通过 MainThreadDispatcher 回到主线程应用区块数据。
     * <p>
     * 任务标记为 SAFE_TO_CANCEL，登出时如果解压尚未完成可安全取消。
     */
    public static void handleCompressedChunk(byte[] compressedData) {
        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Received compressed chunk data ({} bytes)",
                compressedData == null ? -1 : compressedData.length);
        if (compressedData == null || compressedData.length == 0) {
            Constants.LOG.error("[HANDLE_COMPRESSED] Empty compressed payload, ignoring");
            return;
        }

        // 解码（轻量操作，无 I/O）
        ChunkCompressionHandler.CompressedChunkData compressed =
            ChunkCompressionHandler.CompressedChunkData.decode(compressedData);

        if (compressed == null) {
            Constants.LOG.error("[HANDLE_COMPRESSED] Failed to decode compressed chunk data");
            DebugLogger.error("[HANDLE_COMPRESSED] Failed to decode compressed chunk data");
            return;
        }

        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decoded chunk [{}, {}] ({} -> {} bytes, algo={})",
                compressed.chunkX, compressed.chunkZ, compressed.compressedData.length,
                compressed.originalSize, compressed.algorithm);

        // 清除全量请求超时登记（数据已到达，服务端丢弃/积压兜底结束）
        ClientMetadataHandler.onChunkDataReceived(compressed.chunkX, compressed.chunkZ);

        // 记录收到压缩区块数据
        NetworkStats.recordChunkReceived(VanillaZlibEstimator.estimate(compressed.originalSize));
        NetworkStats.recordWireBytesReceived(compressed.compressedData.length);

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null) {
            DebugLogger.warn(LogType.COMPRESSION, "[HANDLE_COMPRESSED] HassiumTaskExecutor not initialized, using sync fallback");
            // 执行器未初始化：回退到主线程同步解压
            decompressAndApply(compressed);
            return;
        }

        final int chunkX = compressed.chunkX;
        final int chunkZ = compressed.chunkZ;
        final byte[] compData = compressed.compressedData;
        final String algorithm = compressed.algorithm;

        // 提交 ZSTD 解压到后台线程池
        executor.submit(() -> {
            try {
                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressing chunk [{}, {}] in background", chunkX, chunkZ);
                // 解压区块数据（后台线程，不阻塞主线程）
                byte[] decompressed = ChunkCompressionHandler.decompressChunkDataFromRaw(chunkX, chunkZ, compData, algorithm);
                if (decompressed == null) {
                    DebugLogger.error("[HANDLE_COMPRESSED] Failed to decompress chunk data for [{}, {}]", chunkX, chunkZ);
                    return;
                }
                if (decompressed.length != compressed.originalSize) {
                    DebugLogger.error("[HANDLE_COMPRESSED] Decompressed size mismatch for [{}, {}] got={} orig={}",
                            chunkX, chunkZ, decompressed.length, compressed.originalSize);
                }

                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressed chunk [{}, {}] ({} -> {} bytes)",
                    chunkX, chunkZ, compData.length, decompressed.length);

                // 影子链路（服务端已装 MOD + 引擎开启）：还原官方包 → 投递影子端
                // （注入 → 原版算光 → 打包官方包 → 官方通道落地）；缓存读写由影子端
                // 世界存档承担，客户端不再 apply/入库。
                if (io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
                    net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                            decodeChunkPacket(decompressed);
                    if (packet != null) {
                        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submit(
                                new ChunkPos(chunkX, chunkZ), packet, compressed.deliveryId);
                        return;
                    }
                    // decode 失败：回退直接 apply（数据仍需落地；方案 A 无客户端入库）
                    DebugLogger.warn(LogType.COMPRESSION,
                            "[HANDLE_COMPRESSED] Packet decode failed for [{}, {}], fallback direct apply",
                            chunkX, chunkZ);
                }

                // 回主线程应用区块（距离优先级依赖 updatePlayerPosition）
                MainThreadDispatcher.execute(() -> {
                    DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Applying chunk [{}, {}] to world", chunkX, chunkZ);
                    if (applyChunkData(chunkX, chunkZ, decompressed, false, compressed.deliveryId)) {
                        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Successfully applied chunk [{}, {}] from server", chunkX, chunkZ);
                    } else {
                        DebugLogger.warn(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Failed to apply chunk [{}, {}] from server", chunkX, chunkZ);
                    }
                }, new ChunkPos(chunkX, chunkZ), TaskCategory.SAFE_TO_CANCEL);

            } catch (Exception e) {
                DebugLogger.error("[HANDLE_COMPRESSED] Error in background decompress for chunk [{}, {}]", e, chunkX, chunkZ);
            }
        }, TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 同步解压并应用（回退路径，当 HassiumTaskExecutor 未初始化时使用）
     */
    private static void decompressAndApply(ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            byte[] decompressed = ChunkCompressionHandler.decompressChunkData(compressed);
            if (decompressed == null) {
                Constants.LOG.error("Hassium: Failed to decompress chunk data for [{}, {}]",
                    compressed.chunkX, compressed.chunkZ);
                return;
            }

            Constants.LOG.debug("Hassium: Decompressed chunk [{}, {}] on main thread (fallback), size: {} -> {} bytes",
                compressed.chunkX, compressed.chunkZ, compressed.compressedData.length, decompressed.length);

            // 影子链路分流（同异步路径）
            if (io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                        decodeChunkPacket(decompressed);
                if (packet != null) {
                    io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submit(
                            new ChunkPos(compressed.chunkX, compressed.chunkZ), packet, compressed.deliveryId);
                    return;
                }
            }

            // 应用区块
            boolean applied = applyChunkData(compressed.chunkX, compressed.chunkZ, decompressed, false,
                    compressed.deliveryId);
            if (applied) {
                Constants.LOG.debug("Hassium: Applied chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            } else {
                Constants.LOG.warn("Hassium: Failed to apply chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Error in fallback decompress for chunk [{}, {}]",
                compressed.chunkX, compressed.chunkZ, e);
        }
    }

    /**
     * 还原官方区块包（线格式字节 → {@code ClientboundLevelChunkWithLightPacket}；
     * 影子链路投递用）。decode 失败返回 null（调用方回退旧链）。
     */
    private static net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
            decodeChunkPacket(byte[] packetBytes) {
        try {
#if MC_VER < MC_1_20_5
            io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.wrappedBuffer(packetBytes);
            try {
                net.minecraft.network.FriendlyByteBuf friendlyBuf =
                        new net.minecraft.network.FriendlyByteBuf(nettyBuf);
                return new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(friendlyBuf);
            } finally {
                nettyBuf.release();
            }
#else
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(packetBytes), mc.level.registryAccess());
            try {
                return net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
                        .STREAM_CODEC.decode(buf);
            } finally {
                buf.release();
            }
#endif
        } catch (Exception e) {
            DebugLogger.error("[DECODE_CHUNK] Failed to decode chunk packet", e);
            return null;
        }
    }

    /**
     * 将解压后的区块数据应用到客户端世界
     * <p>
     * 方案 A：{@code chunkData} 为官方 packet 线格式字节（服务端/影子端推送），
     * 直接经平台 applier 落地。
     *
     * @param chunkX     区块X坐标
     * @param chunkZ     区块Z坐标
     * @param chunkData  packet 字节
     * @param renderOnly true=仅渲染不参与逻辑tick
     */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly) {
        return applyChunkData(chunkX, chunkZ, chunkData, renderOnly, 0L);
    }

    /** 应用 full delivery；仅成功的非 render-only 路径会确认正 deliveryId。 */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly,
                                         long deliveryId) {
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, false, deliveryId);
    }

    private static boolean applyChunkDataInternal(int chunkX, int chunkZ, byte[] chunkData,
                                                  boolean renderOnly, boolean hasCachedLight, long deliveryId) {
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[APPLY_CHUNK] Applying chunk [{}, {}] (dataSize={}, renderOnly={}, hasCachedLight={})",
                chunkX, chunkZ, chunkData.length, renderOnly, hasCachedLight);
        long applyStartNs = System.nanoTime();

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        logChunkApplyEvent("attempt", pos, renderOnly, mc);

        if (level == null) {
            logChunkApplyEvent("level_unavailable", pos, renderOnly, mc);
            DebugLogger.error("[APPLY_CHUNK] Cannot apply chunk [{}, {}], client level is null", chunkX, chunkZ);
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
            }
            return false;
        }

        try {
            // 权威数据到货时玩家已移出服务端视距（apply 决策点主动分流）：
            // 数据是最新服务器数据，不丢弃——若仍在 OVD 环带则原地转 renderOnly 渲染，
            // 省掉「skip → OVD 重扫 → 磁盘 reload → renderOnly apply」的绕路与虚空窗口；
            // OVD 未开或已出环带（>clientVD）则走原路径：平台 applier 的 inRange 判定
            // 丢弃（数据已推送即入库，等于直接入客户端缓存）。判定与 apply 之间仍有
            // 竞态窗口，由下方 ChunkOutOfViewException 兜底。
            if (!renderOnly && ViewDistanceExtensionService.getInstance().shouldKeepAsRenderOnly(pos)) {
                logChunkApplyEvent("redirect_render_only", pos, false, mc);
                return applyChunkDataInternal(chunkX, chunkZ, chunkData, true, hasCachedLight, deliveryId);
            }

            // 超视渲染 / 缓存 apply 前先保证 Storage 半径 ≥ clientVD（防 server 缩半径窗口）
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().ensureExpandedRadius();
            }

            // chunkData 是官方 packet 线格式字节（FriendlyByteBuf 格式），
            // 通过 Minecraft 的数据包处理器来应用
            io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.wrappedBuffer(chunkData);
            nettyBuf.readerIndex(0);  // 确保从头开始读取
            net.minecraft.network.FriendlyByteBuf friendlyBuf = new net.minecraft.network.FriendlyByteBuf(nettyBuf);

            // 通过平台抽象注入区块（需要传入 FriendlyByteBuf）
            // hassiumApplyInProgress：本调用在 Hassium 主线程预算内，置重入标志防止与
            // vanilla 区块加载路径互相拦截（入队 dispatcher 后 hasChunk 校验立即失败 →
            // 假失败/重请求风暴）。
            ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
            pipeline.setApplyInProgress(true);
            try {
                Services.getClientChunkApplier().applyToLevelFromByteBuf(level, pos, friendlyBuf, renderOnly);
            } finally {
                pipeline.setApplyInProgress(false);
            }

            DebugLogger.info(LogType.CHUNK_APPLY, "[APPLY_CHUNK] Successfully applied chunk [{}, {}] to client world in {} ms",
                    chunkX, chunkZ, String.format("%.2f", (System.nanoTime() - applyStartNs) / 1_000_000.0));
            logChunkApplyEvent("applied", pos, renderOnly, mc);

            // 加载活跃：续期 JoinBoost 窗口（含 hasLight 无重算块，重算块在 applyLightEngineNow 续期）。
            // 仅权威块续期：renderOnly（OVD）不续期，避免超视渲染灌队把 JoinBoost 窗口永久续期
            // （高预算被 OVD 吃满、VDES 的 JoinBoost 门控失效）。
            if (!renderOnly) {
                io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.noteChunkApplyActivity();
                // 缓存命中率分母「客户端应用区块」：权威区块成功落地（按坐标去重）；
                // renderOnly/OVD 不计入（用户口径：OVD 环带不参与命中率评估）。
                NetworkStats.recordChunkApplied(chunkX, chunkZ);
            }

            // 区块就绪：发送延后的 BE 请求 + 冲刷暂存 BE
            // renderOnly（超视渲染）不向服务器请求 BE，避免视距外流量
            // 影子端光照由 SectionDelta 段级投递（ShadowLightCompute.submitDelta）落地，
            // 此处不重复触发客户端光照重算
            // 光照缓存记账口径（P2 对齐）：直连口径（recordLightCacheHit）仅在 hasCachedLight=true
            // 时触发；剥光协商（lightComputeSupported=true）后服务端包不带光 → hasCachedLight 恒
            // false，此处直连口径不触发属设计态。剥光模式下光照复用由影子链路记账：
            // ShadowLightCompute 内存/磁盘缓存命中 → NetworkStats.recordLightReuseShadow
            // （key light.reuse.shadow.*），此处不重复计数。
            if (!renderOnly) {
                if (hasCachedLight) {
                    NetworkStats.recordLightCacheHit(getLightBytesPerChunk(level));
                }
                ClientMetadataHandler.onChunkApplied(pos);
            } else if (hasCachedLight) {
                // 缓存已含光照：packet 已写入真实 LightData，Mixin 跳过重算
                NetworkStats.recordLightCacheHit(getLightBytesPerChunk(level));
                ViewDistanceExtensionService.getInstance().onRenderOnlyApplied(pos);
            } else {
                // OVD 包（影子端官方算光带光）；仅登记 renderOnly 落地
                ViewDistanceExtensionService.getInstance().onRenderOnlyApplied(pos);
            }
            // 诊断探针（debug.chunkApplyLogging 开启时输出）：apply#/光照/方块采样
            probeChunkState(pos, level, renderOnly ? "ovd" : "apply");
            recordAuthoritativeApply(deliveryId, renderOnly, true);

            return true;

        } catch (ChunkOutOfViewException e) {
            // 预期竞态：异步解压/主线程预算/视距缩窗导致 apply 时已 out of range
            DebugLogger.debug(LogType.CHUNK_APPLY,
                    "[APPLY_CHUNK] Out of view range, skipped [{}, {}]", chunkX, chunkZ);
            logChunkApplyEvent("out_of_view", pos, renderOnly, mc);
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(new ChunkPos(chunkX, chunkZ));
            }
            return false;
        } catch (Exception e) {
            DebugLogger.error("[APPLY_CHUNK] Failed to apply chunk data for [{}, {}]", e, chunkX, chunkZ);
            logChunkApplyEvent("failed", pos, renderOnly, mc);
            // renderOnly：登记 miss 退避重试
            if (renderOnly) {
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(new ChunkPos(chunkX, chunkZ));
            }
            return false;
        }
    }
    /**
     * 诊断日志：同一条区块应用生命周期事件记录毫秒时间、来源、视图角色、目标区块与当前玩家位置。
     * 仅在 debug.chunkApplyLogging 开启时读取时钟和玩家坐标，避免正常热路径额外工作。
     */
    private static void logChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, Minecraft mc) {
        logChunkApplyEvent(phase, pos, renderOnly, mc, TraceOrigin.SERVER_PUSH);
    }

    private static void logChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, Minecraft mc,
                                           TraceOrigin origin) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        long eventMs = System.currentTimeMillis();
        String originValue = origin == null ? "unknown" : origin.logValue;
        String view = renderOnly ? "ovd" : "authoritative";
        if (mc.player == null) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_APPLY] eventMs={} phase={} origin={} view={} target=({},{}) renderOnly={} player=unavailable",
                    eventMs, phase, originValue, view, pos.x, pos.z, renderOnly);
            return;
        }
        int playerX = (int) Math.floor(mc.player.getX());
        int playerY = (int) Math.floor(mc.player.getY());
        int playerZ = (int) Math.floor(mc.player.getZ());
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_APPLY] eventMs={} phase={} origin={} view={} target=({},{}) renderOnly={} playerBlock=({},{},{}) playerChunk=({},{})",
                eventMs, phase, originValue, view, pos.x, pos.z, renderOnly,
                playerX, playerY, playerZ, playerX >> 4, playerZ >> 4);
    }


    /**
     * 光照缓存等价值字节估算（与 {@code ClientMetadataHandler.ESTIMATED_CHUNK_BYTES} 同口径，16KB/chunk）。
     * 见 {@link NetworkStats#ESTIMATED_LIGHT_BYTES} 注释。
     */
    /** 探针：per-pos 客户端 apply 计数（重复 apply 检测；仅诊断用）。 */
    private static final java.util.Map<Long, Integer> APPLY_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 客户端区块应用探针（debug.chunkApplyLogging 开启时输出；关闭时零开销短路）：
     * per-pos apply 计数 + 区块光照采样（地表 sky / 高空 sky / 地下 block）+
     * 地表方块采样。同 pos 多次 apply（apply# > 1）或 skyTop=0 即「突变 / 黑块」嫌疑，
     * 用于诊断区块错位、已加载区块突然变、黑块问题。诊断专用，不参与任何逻辑。
     */
    public static void probeChunkState(ChunkPos pos, ClientLevel level, String source) {
        probeChunkState(pos, level, source, true, null, false, -1L, -1L, -1L, false, true);
    }

    /**
     * 光照包落地后的复采样：保留最近一次全量区块应用上下文，但不增加 apply 计数。
     */
    public static void probeShadowLightState(ChunkPos pos, ClientLevel level, TraceOrigin fullOrigin,
                                             boolean fullRenderOnly, long fullApplySequence, long fullApplyAgeMs,
                                             long lightQueueDelayMs, boolean fullAppliedAfterLightQueued,
                                             boolean chunkPresent) {
        probeChunkState(pos, level, "light", false, fullOrigin, fullRenderOnly, fullApplySequence,
                fullApplyAgeMs, lightQueueDelayMs, fullAppliedAfterLightQueued, chunkPresent);
    }

    private static void probeChunkState(ChunkPos pos, ClientLevel level, String source, boolean countAsApply,
                                        TraceOrigin fullOrigin, boolean fullRenderOnly, long fullApplySequence,
                                        long fullApplyAgeMs, long lightQueueDelayMs,
                                        boolean fullAppliedAfterLightQueued, boolean chunkPresent) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        if (level == null || pos == null) {
            return;
        }
        String origin = fullOrigin == null ? "unknown" : fullOrigin.logValue;
        String fullView = fullApplySequence < 0L ? "unknown" : fullRenderOnly ? "ovd" : "authoritative";
        if (!countAsApply && !chunkPresent) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_PROBE] source=light pos=({},{}) fullOrigin={} fullView={} fullApplySeq={} fullApplyAgeMs={} lightQueueDelayMs={} fullAppliedAfterLightQueued={} chunkPresent=false",
                    pos.x, pos.z, origin, fullView, fullApplySequence, fullApplyAgeMs, lightQueueDelayMs,
                    fullAppliedAfterLightQueued);
            return;
        }
        int count = countAsApply
                ? APPLY_COUNT.merge(pos.toLong(), 1, Integer::sum)
                : APPLY_COUNT.getOrDefault(pos.toLong(), 0);
        int bx = (pos.x << 4) + 8;
        int bz = (pos.z << 4) + 8;
        int minY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(level);
        int maxY = minY + level.getHeight();
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        int sampleY = Math.max(topY, minY);
        int skyTop = level.getBrightness(LightLayer.SKY,
                new BlockPos(bx, Math.max(topY + 1, minY), bz));
        int skyAir = level.getBrightness(LightLayer.SKY,
                new BlockPos(bx, maxY - 2, bz));
        int blockLow = level.getBrightness(LightLayer.BLOCK,
                new BlockPos(bx, minY + 1, bz));
        String topBlock = level.getBlockState(new BlockPos(bx, sampleY, bz))
                .getBlock().getDescriptionId();
        int fixedY = Math.max(minY, Math.min(maxY - 1, 62));
        String fixedBlock = level.getBlockState(new BlockPos(bx, fixedY, bz))
                .getBlock().getDescriptionId();
        if (!countAsApply) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_PROBE] source=light pos=({},{}) apply#={} fullOrigin={} fullView={} fullApplySeq={} fullApplyAgeMs={} lightQueueDelayMs={} fullAppliedAfterLightQueued={} chunkPresent=true topY={} skyTop={} skyAir={} blockLow={} topBlock={} fixedY={} fixedBlock={}",
                    pos.x, pos.z, count, origin, fullView, fullApplySequence, fullApplyAgeMs, lightQueueDelayMs,
                    fullAppliedAfterLightQueued, topY, skyTop, skyAir, blockLow, topBlock, fixedY, fixedBlock);
            return;
        }
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_PROBE] source={} pos=({},{}) apply#={} topY={} skyTop={} skyAir={} blockLow={} topBlock={} fixedY={} fixedBlock={}",
                source, pos.x, pos.z, count, topY, skyTop, skyAir, blockLow, topBlock, fixedY, fixedBlock);
    }
    private static long getLightBytesPerChunk(ClientLevel level) {
        // level 参数保留以便未来按 sectionsCount 动态估算；当前与区块口径一致用常量
        return NetworkStats.ESTIMATED_LIGHT_BYTES;
    }
}