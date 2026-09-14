package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor;
import io.github.limuqy.mc.hassium.storage.ShadowStorageHashes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * 权威边沿消费端（客户端）：服务端声明的 enter 在这里解析成本地动作。
 * <p>
 * 三分支（与 {@code docs/client-chunk-flow-handover.md} 的统一 Compare+Pull 语义一致）：
 * <ol>
 *   <li><b>hash 已知且与本地基线相同</b>：服务端已断言权威内容 == 本地内容 →
 *       <b>不发任何请求</b>。客户端尚未持有则本地 {@code publishCachedChunk} 交付；
 *       已持有（典型为 OVD→权威）则 {@code accountCacheFullHit} 补记缓存全命中。
 *       两者都按「区块缓存全命中」记账（见 {@link ShadowLightCompute#markAuthorityHashConfirmed}）。</li>
 *   <li>本地已有基线但 hash 未知/不等 → {@code requestFull}（带基线比较），
 *       服务端裁决 UNCHANGED / DELTA / FULL。</li>
 *   <li>本地无基线 → 空基线请求：SeedGen 门控开时由**声明驱动的本地生成**接管
 *       （resolve → `LOCAL_GENERATE` → FORCED 票触发 vanilla worldgen，见
 *       {@code ShadowTicketDriver.registerLocalGeneration}）；门控关 → `requestAuthoritativeFull`。</li>
 * </ol>
 * 客户端已经持有（{@code hasClientApplyEpoch}）且 hash 相同的柱不做任何动作：
 * 同一次交付不得既计「新增」又计「命中」（R1 假命中红线）。
 */
public final class ChunkAuthorityClient {

    /**
     * 权威包断流看门狗：超过该时长未收到 enter/快照即回退影子端 tracking 驱动 pull。
     * <p>
     * 该值同时是**让位门的契约窗口**：影子端挂起某柱的时长不得超过它，否则等于把兜底当常态
     * （见 {@code ShadowTrackingSession#GATE_STARVE_GRACE_MS}）。
     */
    public static final long AUTHORITY_WATCHDOG_MS = 10_000L;

    /** 声明时刻表上限：超出即整体作废（声明会随下一轮快照重灌，宁可重算不积压）。 */
    private static final int MAX_DECLARED_ENTRIES = 16_384;

    /** 接收侧丢弃计数（level 未就绪）：仅诊断；>0 即「声明丢过」，靠服务端快照重推自愈。 */
    private static final java.util.concurrent.atomic.AtomicLong DROPPED_NOT_READY =
            new java.util.concurrent.atomic.AtomicLong();

    /** 协商位在位且已收到权威包 → 权威集合由服务端声明（影子端让位，不再自绘选柱拉取）。 */
    private static volatile boolean authorityDeclared;
    private static volatile long lastAuthorityPacketMs;

    /**
     * 柱最近一次被权威声明覆盖的时刻（复合键 → epoch ms）。
     * <p>
     * 影子主循环线程经 {@link #declaredAtMs} 读它，把让位门的「扣留起算点」抬到声明时刻：
     * 已被声明的柱归权威路径接管，不该被影子端记成饥饿；只有「声明覆盖后仍迟迟未交付」才是真兜底。
     * 客户端线程写、影子线程读，故用并发表；随切维作废。
     */
    private static final java.util.Map<Long, Long> DECLARED_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ChunkAuthorityClient() {
    }

    /**
     * 影子端是否应停止发出 pull 请求（唯一解析入口交给本类）。
     * <p>
     * 条件 = 协商 {@code AUTHORITY_NOTIFY} 且权威包未断流（{@link #AUTHORITY_WATCHDOG_MS}）。
     * 断流（旧服务端 / 丢包 / 未协商）自动回退影子端 tracking 驱动，保证不会因为
     * 等一个永不到来的声明而停摆。
     */
    public static boolean pullEmissionSuppressed() {
        if (!authorityDeclared) {
            return false;
        }
        return System.currentTimeMillis() - lastAuthorityPacketMs <= AUTHORITY_WATCHDOG_MS;
    }

    /** 客户端维度变更时作废在途语义（集合按维度键存，无需跨维清理数据）。 */
    public static void onClientDimensionChanged() {
        authorityDeclared = false;
        lastAuthorityPacketMs = 0L;
        DECLARED_AT.clear();
    }

    /**
     * 该柱最近一次被权威声明覆盖的时刻（ms）；从未声明返回 {@code -1}。
     * <p>
     * 供影子主循环线程调用（只读，无锁竞争）。
     */
    public static long declaredAtMs(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return -1L;
        }
        Long at = DECLARED_AT.get(
                io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, chunkX, chunkZ));
        return at == null ? -1L : at;
    }

    /** 收到权威边沿载荷：仅处理当前客户端维度。 */
    public static void handle(ChunkAuthorityS2CPacket packet) {
        if (packet == null || !ShadowLightCompute.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // 加入世界窗口期：此时无法解析声明，只能丢弃。**丢弃在这里是可自愈的**——
            // 服务端在 join/切维 settle 后会把累计声明集合整体重推一次
            // （ChunkAuthorityNotifier.maybeResendDeclared），故无需本地重试队列。
            // 但必须留痕：它是「声明在接收侧丢失」这一类缺陷的唯一现场证据。
            long dropped = DROPPED_NOT_READY.incrementAndGet();
            if (dropped == 1L || dropped % 64L == 0L) {
                io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                        io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                        "[AUTHORITY] declarations dropped before level ready (count={}) - healed by next snapshot",
                        dropped);
            }
            return;
        }
        String clientDim = LevelCompat.getDimensionId(minecraft.level);
        if (clientDim == null || !clientDim.equals(packet.dimension())) {
            Constants.LOG.debug("Hassium: drop authority edges dim={} client={}",
                    packet.dimension(), clientDim);
            return;
        }
        // 进入「服务端声明权威」模式：影子端让位（见 pullEmissionSuppressed）
        authorityDeclared = io.github.limuqy.mc.hassium.network.handshake.LoginCaps.has(
                io.github.limuqy.mc.hassium.network.handshake.ClientLoginNegotiation.current(),
                io.github.limuqy.mc.hassium.network.handshake.LoginCaps.AUTHORITY_NOTIFY);
        lastAuthorityPacketMs = System.currentTimeMillis();
        // 快照包语义：客户端侧无需枚举集合（解析动作由服务端 enter 逐柱驱动），
        // 仅记录 epoch 供诊断；重复 enter 由 markPullInFlight / applyEpoch 去重。
        // 注意：声明只做**内容裁决**（hash 命中 / 比较 / 全量），不参与出票——装载几何由
        // ShadowTicketDriver 按本地整方形（含 OVD 环）自行对账，避免两套票源错位起缝。
        if (DECLARED_AT.size() >= MAX_DECLARED_ENTRIES) {
            DECLARED_AT.clear();
        }
        long declaredAt = lastAuthorityPacketMs;
        List<ChunkAuthorityS2CPacket.Entry> entries = packet.entries();
        // 按**声明包**聚合 C2S pull：分组只是打包，判定仍是**逐柱**（见 resolve 的返回值），
        // 因此不会退化成「按包一刀切」。ShadowPullClient.request 内部再按 MAX_ENTRIES 分包。
        List<ChunkPos> comparePulls = new ArrayList<>();
        List<ChunkPos> authoritativePulls = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ChunkAuthorityS2CPacket.Entry entry = entries.get(i);
            // 先登记声明时刻，再解析：让位门据此判定该柱已归权威路径（解析失败也算已声明）。
            DECLARED_AT.put(io.github.limuqy.mc.hassium.utils.DimensionKey.key(
                    packet.dimension(), entry.chunkX(), entry.chunkZ()), declaredAt);
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            try {
                switch (resolve(packet.dimension(), pos, entry.hash())) {
                    case COMPARE_PULL -> comparePulls.add(pos);
                    case AUTHORITATIVE_PULL -> authoritativePulls.add(pos);
                    case LOCAL_GENERATE -> io.github.limuqy.mc.hassium.network.seedgen
                            .ShadowTicketDriver.registerLocalGeneration(packet.dimension(), pos);
                    case NONE -> { }
                }
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: authority resolve failed ({}, {})",
                        entry.chunkX(), entry.chunkZ(), t);
            }
        }
        if (!authoritativePulls.isEmpty()) {
            ShadowPullClient.requestAuthoritativeFull(packet.dimension(), authoritativePulls);
        }
        if (!comparePulls.isEmpty()) {
            ShadowPullClient.requestFull(packet.dimension(), comparePulls);
        }
    }

    /** {@link #resolve} 的裁决结果：该柱产出哪一路 C2S pull（无动作 / 带基线比较 / 空基线权威 FULL / 声明驱动本地生成）。 */
    private enum Pull {
        NONE,
        COMPARE_PULL,
        AUTHORITATIVE_PULL,
        LOCAL_GENERATE
    }

    private static Pull resolve(String dimension, ChunkPos pos, long authoritativeHash) {
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        boolean injected = shadow != null && shadow.injectedChunk(dimension, pos.x, pos.z) != null;
        Long localHash = ShadowStorageHashes.get(dimension, pos);
        boolean hasBaseline = injected || localHash != null;
        if (!hasBaseline) {
            // 全新柱：SeedGen 门控开时声明驱动本地生成（③：不拉网络，generateChunkAsync 显式
            // vanilla worldgen → onChunkMaterialized 计 locallyGenerated → compare-before-light 交付）；
            // 门控关（或本地生成已判失败）时空基线请求 FULL。
            if (!io.github.limuqy.mc.hassium.network.seedgen.ShadowTicketDriver
                    .isLocalGenFailed(dimension, pos)
                    && SeedGenExecutor.getInstance().isGenerationGateOpen()) {
                return Pull.LOCAL_GENERATE;
            }
            return Pull.AUTHORITATIVE_PULL;
        }
        boolean clientHolds = ShadowLightCompute.hasClientApplyEpoch(dimension, pos);
        if (authoritativeHash != 0L && localHash != null && localHash == authoritativeHash) {
            if (clientHolds) {
                // 服务端确认无变更且客户端已持有：不再 PULL。
                // OVD→权威：本地源已交付 + hash 一致 → 补记缓存全命中（accountCacheFullHit
                // 对 accountedIngress / 已记命中幂等，不会与网络全量双计）。
                if (ShadowLightCompute.accountCacheFullHit(dimension, pos)) {
                    io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                            io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                            "[AUTHORITY] hash-hit already-held ({}, {}) hash={}",
                            pos.x, pos.z, authoritativeHash);
                }
                return Pull.NONE;
            }
            // 服务端断言权威内容 == 本地内容：零请求本地交付 + 计全命中
            ShadowLightCompute.markAuthorityHashConfirmed(dimension, pos);
            if (ShadowLightCompute.publishCachedChunk(dimension, pos)) {
                io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                        io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                        "[AUTHORITY] hash-hit zero-request ({}, {}) hash={}", pos.x, pos.z, authoritativeHash);
                return Pull.NONE;
            }
            // 基线条目存在但无法物化（内存/磁盘都取不到）：退回带基线比较
            return Pull.COMPARE_PULL;
        }
        // hash 未知或不等：带基线比较，由服务端裁决 UNCHANGED / DELTA / FULL
        return Pull.COMPARE_PULL;
    }
}
