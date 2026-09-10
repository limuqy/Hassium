package io.github.limuqy.mc.hassium.metrics;

/**
 * 客户端统计四行语义真相源。
 * <p>
 * 出问题时先对照本文件判断是<b>计算逻辑错</b>（展示公式 / 聚合方式）还是
 * <b>取值锚点错</b>（埋点方法没在正确协议事件上调用，或漏调）。
 * <p>
 * <h2>诊断口诀</h2>
 * <ol>
 *   <li>看展示行百分比/计数 → 对「展示公式」</li>
 *   <li>对不上则看 probe 的 stats 原值 → 对「计数器语义」</li>
 *   <li>原值也不对 → 对「锚点表」查埋点是否在正确事件上调用</li>
 * </ol>
 *
 * <h2>1. 区块缓存（服务端少下发的区块内容）</h2>
 * <pre>
 * 展示：命中率 = (全命中字节 + 部分命中字节 − 增量分片字节) / 应用字节
 *       全命中 N/B，部分命中 N/B，增量 B，应用 B
 * </pre>
 * <ul>
 *   <li><b>全命中</b>：影子端读取区块，且服务端确认无变更（compare-pull UNCHANGED
 *       → {@code publishCachedChunk}；或服务端直推时影子内存已有 hash 一致柱）。
 *       计数器：{@code cacheHitFullChunkCount/Bytes}。</li>
 *   <li><b>部分命中/增量</b>：DELTA。本地基线柱 + 分段增量合并成功。
 *       计数器：{@code cacheDeltaCount/Bytes}；分片变更内容 {@code cacheShardBytes}
 *       从命中分子扣除。</li>
 *   <li><b>应用</b>：客户端实际收到并落地的来源事件字节和
 *       （全量请求 + 全命中 + 分段增量基线 + 服务端直推×16KB）。
 *       不含 SeedGen 本地生成。</li>
 * </ul>
 * 锚点：
 * <ul>
 *   <li>{@code recordCacheFullHit} ← {@code accountCacheFullHit} ← UNCHANGED / 内存 hash 一致</li>
 *   <li>{@code recordCacheDeltaSaved} + {@code recordCacheShard} ← {@code applySectionDelta} 成功</li>
 *   <li>应用字节 = {@code getFullChunkRequestBytes + cacheHitFullChunkBytes
 *       + cacheDeltaSavedBytes + serverPush×ESTIMATED_CHUNK_BYTES}</li>
 * </ul>
 *
 * <h2>2. 区块加载（全量拉取 / 本地生成）</h2>
 * <pre>
 * 展示：加载 N（新增 X/B，本地 Z/W，本地命中 R%）
 *       N = fullRequestCount + serverPush          ← 全部网络全量 + 本地前合计
 *       X = newFullChunkRequestCount
 *           + staleFullChunkRequestCount
 *           + serverPush                            ← 新增 = 网络全量（含 compare-pull FULL）
 *       Z = locallyGeneratedChunkCount               ← 本地 = SeedGen
 *       R = localBytes / (newRequestBytes + localBytes) ← 本地命中（以本地生成为基线）
 * </pre>
 * <p>
 * <b>不再展示「过期」</b>：内部仍保留 {@code staleFullChunkRequestCount}
 * （compare-pull FULL）供探针/诊断，但不进展示行。
 * <ul>
 *   <li><b>新增</b>：authoritative-full（SERVER_PUSH）+ compare-pull FULL（REMOTE_PULL），
 *       即一切走到客户端的网络整柱。</li>
 *   <li><b>本地</b>：SeedGen 本地生成。字节 = 整柱等价（ESTIMATED_CHUNK_BYTES），
 *       不是 DELTA。</li>
 *   <li><b>本地命中</b>：本地生成字节占（网络全量 + 本地生成）的比例，
 *       与区块缓存命中率同构，基线换成 SeedGen。</li>
 * </ul>
 * 锚点：
 * <ul>
 *   <li>{@code recordFullChunkRequests(stale=false)} ← SERVER_PUSH 落地</li>
 *   <li>{@code recordFullChunkRequests(stale=true)} ← REMOTE_PULL（compare-pull FULL）落地</li>
 *   <li>{@code recordLocallyGeneratedChunk} ← 影子 tracking 本地 worldgen 物化（门控开）</li>
 * </ul>
 * <p>
 * <b>R1 归因红线</b>：清缓存首进的网络全量必须记「新增」，
 * 不得被 {@code publishCachedChunk} 改写成「全命中」。
 * {@code enqueueInjectedForLight(SERVER_PUSH/REMOTE_PULL)} 与
 * {@code onPullInjected → publishCachedChunk} 禁止对同一柱双投递覆盖来源。
 *
 * <h2>3. 光照缓存（少算了哪些光）</h2>
 * <pre>
 * 展示：命中率 = (直连命中字节 + 影子复用字节) / (命中字节 + 重算字节)
 *       命中 N/B，重算 N/B
 * </pre>
 * <ul>
 *   <li><b>命中</b>：影子端读取已收敛光照的区块（区块缓存全命中且
 *       {@code isLightCorrect}；或 OVD/renderOnly 本地全量服务）。
 *       计数器：{@code lightReuseShadowCount/Bytes}（剥光协商下直连
 *       {@code lightCacheHitCount} 恒 0）。</li>
 *   <li><b>重算</b>：所有需要算光的场景——网络 FULL 注入、DELTA 合并后、
 *       缓存柱欠光（{@code !isLightCorrect}）续算。
 *       计数器：{@code lightCacheMissCount/Bytes}。
 *       邻柱 LIGHT_ONLY 补光不进分母。</li>
 * </ul>
 * 锚点：
 * <ul>
 *   <li>{@code recordLightReuseShadow} ← 光屏障提交且 (renderOnly || REUSE_CACHE)</li>
 *   <li>{@code recordLightCacheMiss} ← 光屏障提交且 RECOMPUTE</li>
 *   <li>REUSE_CACHE 条件：{@code GenEntry.lightReuse == true}，
 *       即提交时 {@code chunk.isLightCorrect()}</li>
 * </ul>
 *
 * <h2>4. 流量节省（有 MOD vs 无 MOD）</h2>
 * <pre>
 * 展示：节省% = (noMod − actual) / noMod
 *       （当前 actual，无MOD noMod）
 * </pre>
 * <ul>
 *   <li><b>actual</b>：{@code actualBytesReceived}——仅管线层
 *       {@code recordWireBytesReceived} 写入（chunk_payload / shadow-pull FULL /
 *       SectionDelta 线缆字节）。聚合帧 wire 不计入区块域 actual。</li>
 *   <li><b>noMod</b>：{@code getNoModReceiveBytes()} =
 *       vanillaBytesReceived
 *       + chunkWire × SeedGen 本地生成数
 *       + chunkWire × 避免的缓存全命中数（扣除 network-replaced 重叠）
 *       + lightWire × (直连命中 + 影子复用 + 重算) 总柱数</li>
 *   <li>vanillaBytesReceived 由应用层写：全量区块原版等价、SectionDelta
 *       成功应用的全量等价、聚合子包等价。</li>
 * </ul>
 * 锚点：
 * <ul>
 *   <li>{@code recordWireBytesReceived} ← ClientChunkHandler / ShadowPullClient FULL /
 *       ShadowPullClient DELTA（payload 线缆长度）</li>
 *   <li>{@code recordChunkReceived} / {@code recordVanillaBytesReceived} ← 应用层原版等价</li>
 *   <li>{@code recordSectionDeltaReceived} ← DELTA 成功应用（vanilla 等价 + 计数）</li>
 * </ul>
 *
 * <h2>已知历史坑（修复后应不再出现）</h2>
 * <ul>
 *   <li>SectionDelta 曾只记 zstd 对、漏记 {@code actualBytesReceived}
 *       → 重连轮「流量节省 100% / 当前 0 B」。</li>
 *   <li>{@code submitPreLight} 曾写死 {@code lightReuse=false}
 *       → UNCHANGED 全命中时光照仍 100% 重算。</li>
 *   <li>compare-pull FULL 曾记入「新增」而非「过期」。</li>
 * </ul>
 *
 * @see HassiumMetrics#getEffectiveCacheHitRate()
 * @see HassiumMetrics#getNoModReceiveBytes()
 * @see HassiumMetrics#getLightCacheHitRate()
 * @see HassiumCommandHandler#getClientStatsMessage()
 */
public final class MetricsSemantics {
    private MetricsSemantics() {}
}
