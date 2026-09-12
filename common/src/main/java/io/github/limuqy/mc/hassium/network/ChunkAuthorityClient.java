package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.storage.ShadowStorageHashes;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * 权威边沿消费端（客户端）：服务端声明的 enter 在这里解析成本地动作。
 * <p>
 * 三分支（与 {@code docs/client-chunk-flow-handover.md} 的统一 Compare+Pull 语义一致）：
 * <ol>
 *   <li><b>hash 已知且与本地基线相同</b>：服务端已断言权威内容 == 本地内容 →
 *       <b>不发任何请求</b>，本地 {@code publishCachedChunk} 交付，并按「区块缓存全命中」记账
 *       （见 {@link ShadowLightCompute#markAuthorityHashConfirmed}）。</li>
 *   <li>本地已有基线但 hash 未知/不等 → {@code requestFull}（带基线比较），
 *       服务端裁决 UNCHANGED / DELTA / FULL。</li>
 *   <li>本地无基线 → {@code requestAuthoritativeFull}（空基线，服务端必答 FULL）；
 *       SeedGen 门控开时由既有本地生成路径接管。</li>
 * </ol>
 * 客户端已经持有（{@code hasClientApplyEpoch}）且 hash 相同的柱不做任何动作：
 * 同一次交付不得既计「新增」又计「命中」（R1 假命中红线）。
 */
public final class ChunkAuthorityClient {

    /** 权威包断流看门狗：超过该时长未收到 enter/快照即回退影子端 tracking 驱动 pull。 */
    private static final long AUTHORITY_WATCHDOG_MS = 10_000L;

    /** 协商位在位且已收到权威包 → 权威集合由服务端声明（影子端让位，不再自绘选柱拉取）。 */
    private static volatile boolean authorityDeclared;
    private static volatile long lastAuthorityPacketMs;

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
    }

    /** 收到权威边沿载荷：仅处理当前客户端维度。 */
    public static void handle(ChunkAuthorityS2CPacket packet) {
        if (packet == null || !ShadowLightCompute.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
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
        List<ChunkAuthorityS2CPacket.Entry> entries = packet.entries();
        for (int i = 0; i < entries.size(); i++) {
            ChunkAuthorityS2CPacket.Entry entry = entries.get(i);
            try {
                resolve(packet.dimension(), new ChunkPos(entry.chunkX(), entry.chunkZ()), entry.hash());
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: authority resolve failed ({}, {})",
                        entry.chunkX(), entry.chunkZ(), t);
            }
        }
    }

    private static void resolve(String dimension, ChunkPos pos, long authoritativeHash) {
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        boolean injected = shadow != null && shadow.injectedChunk(dimension, pos.x, pos.z) != null;
        Long localHash = ShadowStorageHashes.get(dimension, pos);
        boolean hasBaseline = injected || localHash != null;
        if (!hasBaseline) {
            // 全新柱：空基线请求（SeedGen 门控开时既有路径会在选柱时接管本地生成）
            ShadowPullClient.requestAuthoritativeFull(dimension, List.of(pos));
            return;
        }
        boolean clientHolds = ShadowLightCompute.hasClientApplyEpoch(dimension, pos);
        if (authoritativeHash != 0L && localHash != null && localHash == authoritativeHash) {
            if (clientHolds) {
                // 服务端确认无变更且客户端已持有：零动作（不得记命中，避免与首轮「新增」双计）
                return;
            }
            // 服务端断言权威内容 == 本地内容：零请求本地交付 + 计全命中
            ShadowLightCompute.markAuthorityHashConfirmed(dimension, pos);
            if (ShadowLightCompute.publishCachedChunk(dimension, pos)) {
                io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                        io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                        "[AUTHORITY] hash-hit zero-request ({}, {}) hash={}", pos.x, pos.z, authoritativeHash);
                return;
            }
            // 基线条目存在但无法物化（内存/磁盘都取不到）：退回带基线比较
            ShadowPullClient.requestFull(dimension, List.of(pos));
            return;
        }
        // hash 未知或不等：带基线比较，由服务端裁决 UNCHANGED / DELTA / FULL
        ShadowPullClient.requestFull(dimension, List.of(pos));
    }
}
