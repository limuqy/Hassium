package io.github.limuqy.mc.hassium.network.entity;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.ServerNetworkGate;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Arrays;
import java.util.List;

/**
 * 实体更新降帧/错峰引擎（由 {@code MixinServerEntity} 调用）。
 * <p>
 * <b>vanilla 兼容，不要求客户端握手：</b>本域只改 {@code Clientbound*} 复制节拍，不走 Hassium
 * 自定义包，原版客户端完整兼容。门控只有「主服务器实例 + master 网络面总闸 + entity* 配置」，
 * <b>不要求</b>存在已协商的 Hassium 连接。压力输入同样覆盖全部游戏态连接（含原版）。
 * <p>
 * <b>落点为什么是 {@code ServerEntity.updateInterval} 而不是取消 {@code sendChanges()}：</b>
 * {@code sendChanges()} 尾部的 {@code this.tickCount++} 在门条件之外，用 {@code @Inject(HEAD, cancel)}
 * 跳过整段会冻结 {@code tickCount}（{@code 0 % interval == 0} 恒真 ⇒ 闸门自锁失效），并跳过尾部
 * 的 {@code hurtMarked} 分支；因此本引擎只在**门条件的两个取值点**上做替换：
 * <ol>
 *   <li>{@code updateInterval} → {@code max(原版间隔下限, 本引擎间隔)}（只降速，永不提速；
 *       物品流的下限取 1，见下）。</li>
 *   <li>门条件中的 {@code entity.hasImpulse}（1.21.11 起 {@code needsSync}）→ 受控时置 false。
 *       掉落物流每 tick 被 {@code ItemEntity} 置位该标志，原版因此每 tick 发一帧（≈20 包/s/个），
 *       是本域最大单一流量源，不压制则无从降速。</li>
 * </ol>
 * 方法结构（tickCount、乘客包、头部朝向包、dirty 元数据）全部保持原版。
 * <p>
 * <b>物品流单独一张档位表：</b>掉落物/经验球的 {@code EntityType.updateInterval} 是 20
 * （{@code EntityType.ITEM} / {@code EXPERIENCE_ORB}），但那是它的**空闲**节拍，位置节拍由每 tick 的
 * {@code hasImpulse} 驱动。降帧接管 {@code hasImpulse} 后位置节拍改由档位表决定，此时若仍把 20 当
 * 下限（{@link EntityUpdateTiering#effectiveInterval} 取 max），档位表会被整体压平 ⇒ 物品恒 1 包/s，
 * 而客户端插值窗口只有 3 刻（{@code lerpTo(..., 3)}；1.21.11 {@code InterpolationHandler} 同为 3），
 * 于是画面上就是瞬移/闪烁。故物品流另取一张表（{@code master.entityItemTierInterval*}）且下限取 1
 * （{@link EntityUpdateTiering#intervalFloor}）；距离分档与原版跟踪范围判定与其它实体共用。
 * <p>
 * <b>本域只改复制（replication）节拍，不碰服务端实体逻辑：</b>被改写的两个取值点只影响
 * {@code ServerEntity.sendChanges()}（唯一调用点 {@code ChunkMap.TrackedEntity.updatePlayer}）
 * 是否构造并广播 {@code Clientbound*} 包与它自己的 base/最近位置记账。实体自身的 tick
 * （{@code ServerLevel.tickNonPassenger} → {@code ItemEntity.tick} 的物理、{@code age} 生命周期）、
 * 拾取与漏斗判定（{@code HopperBlockEntity.getItemsAtAndAbove} 走
 * {@code level.getEntitiesOfClass(ItemEntity.class, ...)} 的服务端实体列表）都读不到本域状态，
 * 因此不会因降帧出现「漏斗吸不到掉落物」这类行为变化。
 * <p>
 * <b>密度与压力是两把不同的闸：</b>密度（{@link EntityDensityIndex}）是**局部**的——只数「实体自己所在
 * chunk 内本 tick 有实体包的实体数」，并逐档配置阈值与倍率（{@code master.entityDensityTierCounts} /
 * {@code ...TierFactors}），所以十几区块外的热点不影响近处实体；压力（{@link EntityPressureBook}）是
 * **每观察者独立**的——按每条连接自己的实测实体包量反压，谁超预算谁自己的视锥内实体降帧。两者相乘后
 * 受 {@code master.entityMaxThrottleFactor} 总上限收口。
 * <p>
 * 单实体只能有一个帧率（{@link ServerEntity} 是「每实体一份、广播给全部跟踪者」，见下条），所以压力取
 * **最近观察者的那一份**；共享该实体的其它观察者跟随同一帧率，这是并集语义的既有边界。
 * <p>
 * <b>为什么不做 per-observer 精确帧率：</b>{@code ServerEntity.positionCodec} 的 base 是**每实体单例**，
 * 位置包是相对 base 的 1/4096 量化增量；给部分观察者发相对包（或手工补发绝对包）会让服务端 base
 * 与这些客户端的 base 错位，误差只能等下一次绝对包自愈。因此本引擎采用**并集语义**：帧间隔取观察者中
 * 最小挡位间隔（最近观察者决定），任一观察者到期即整帧放行。跳帧本身严格安全——原版在累计位移超出
 * short 量化范围（±8 格）或 {@code teleportDelay > 400} 时自动退化为绝对包。
 * <p>
 * 主线程独占：快照每 tick 起点重建一次，热路径只做字段读取与算术。
 */
public final class EntityUpdatePacing {

    private static volatile Snapshot snapshot = Snapshot.INACTIVE;
    /** 每观察者一份的压力账本（tick 起点重建入参、热路径只读）。 */
    private static EntityPressureBook pressureBook = new EntityPressureBook(1);
    /** 该账本的倍率上限，随配置变化时整本重建（压力回到初始档，可接受）。 */
    private static int bookMaxFactor = 1;
    /** 复用的本帧实测容器：稳态零分配。 */
    private static final java.util.Map<net.minecraft.network.Connection, Integer> FRAME_PACKETS = new java.util.HashMap<>();
    /** 复用的「本帧在册观察者」集合，用于丢弃断连账本。 */
    private static final java.util.Set<Object> FRAME_OBSERVERS = new java.util.HashSet<>();
    /** 最近观察者查询的落点（避免每实体每 tick 分配）；只被主线程读写。 */
    private static final ObserverScratch SCRATCH = new ObserverScratch();
    private static boolean loggedActive;

    private EntityUpdatePacing() {
    }

    /** 最近观察者的档位与该观察者本身；{@code tier < 0} 表示不在任何观察者跟踪范围内。 */
    private static final class ObserverScratch {
        private int tier = -1;
        private ServerPlayer observer;
        /** 本 sendChanges 内已算过的生效间隔（tickCount redirect 先于 updateInterval redirect）。 */
        private Entity intervalEntity;
        private int interval;
    }

    /**
     * 服务端 tick 起点：翻密度页、重建配置快照、用上一 tick 的实测实体包数更新压力倍率。
     * 必须每次 {@code tickServer} 起点调用（早于 {@code ChunkMap.tick()}）。
     * <p>
     * 只有 {@link RuntimeServerContext#getActiveServer()} 本尊可以进入：客户端进程里影子端世界也是一个
     * {@code MinecraftServer} 并会 tick，若让它也写静态快照/翻密度页，会把主服的降帧状态整 tick 覆盖掉。
     */
    public static void onServerTickStart(MinecraftServer server) {
        if (server == null || server != RuntimeServerContext.getActiveServer()) {
            return;
        }
        EntityDensityIndex.rotate();
        Snapshot next = build(server);
        snapshot = next;
        logActivation(next);
        EntityPacketCounters.drainGameInto(FRAME_PACKETS);
        if (!next.active || next.budgetPerPlayer <= 0) {
            pressureBook.reset();
            return;
        }
        if (bookMaxFactor != next.maxFactor) {
            bookMaxFactor = next.maxFactor;
            pressureBook = new EntityPressureBook(next.maxFactor);
        }
        FRAME_OBSERVERS.clear();
        // 反压按观察者独立：每个连接只用自己的实测包量喂自己的那一份，
        // 一个玩家身边的热点不会再把别人的视锥一起降帧。
        for (java.util.Map.Entry<net.minecraft.network.Connection, Integer> entry : FRAME_PACKETS.entrySet()) {
            ServerPlayer player = playerOf(entry.getKey());
            if (player == null) {
                continue;
            }
            FRAME_OBSERVERS.add(player);
            pressureBook.sample(player, entry.getValue(), next.budgetPerPlayer);
        }
        pressureBook.retainOnly(FRAME_OBSERVERS);
    }

    /**
     * 连接所属的玩家。用于把「每连接的实体包实测值」归到具体的观察者身上（反压账本按玩家索引）。
     * <p>
     * 走 {@code Connection.getPacketListener() → ServerGamePacketListenerImpl.player}（三个大版本上都是
     * public），不引入新 mixin；握手期/状态查询期的连接拿到的是别的 listener 类型，返回 {@code null}
     * 即「与 Hassium 无关」，不计入反压。
     */
    private static ServerPlayer playerOf(net.minecraft.network.Connection connection) {
        net.minecraft.network.PacketListener listener = connection.getPacketListener();
        if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game) {
            return game.player;
        }
        return null;
    }

    /**
     * {@code ServerEntity.sendChanges()} 门条件中 {@code updateInterval} 的替换值。
     *
     * @param vanillaInterval 原版实体类型间隔
     * @return 施加距离挡位 × 密度倍率 × 压力倍率后的间隔；未接入/豁免时原样返回
     */
    public static int effectiveInterval(Entity entity, int vanillaInterval) {
        Snapshot current = snapshot;
        if (!eligible(entity, current) || vanillaInterval > EntityUpdateTiering.MAX_INTERVAL) {
            return vanillaInterval;
        }
        // 门条件字节码顺序是 tickCount 再 updateInterval：staggeredTickCount 已算过则直接复用，
        // 避免同一次 sendChanges 内密度索引被 observe 两次。
        if (SCRATCH.intervalEntity == entity) {
            return SCRATCH.interval;
        }
        return computeEffectiveInterval(entity, vanillaInterval, current);
    }

    /**
     * {@code ServerEntity.sendChanges()} 门条件中 {@code tickCount} 的替换值（错峰推送）。
     * <p>
     * 受控且 {@code entitySmoothPushEnabled} 时返回 {@code tickCount + phase}，使门条件变为
     * {@code (tickCount + phase) % interval == 0}：同 interval 实体按 UUID 稳定错开，
     * 任意连续 interval 个 tick 内每实体仍只发一次（总量不变），但不再齐发。
     * <p>
     * 只替换门条件那一处读取（{@code ordinal = 0}）；方法内后面的 {@code tickCount % 60}
     * 绝对包节拍与 {@code tickCount > 0} 首帧判定保持原版。
     */
    public static int staggeredTickCount(Entity entity, int tickCount, int vanillaInterval) {
        Snapshot current = snapshot;
        if (!current.active || !current.smoothPush || !eligible(entity, current)
                || vanillaInterval > EntityUpdateTiering.MAX_INTERVAL) {
            return tickCount;
        }
        int interval = computeEffectiveInterval(entity, vanillaInterval, current);
        SCRATCH.intervalEntity = entity;
        SCRATCH.interval = interval;
        if (interval <= 1) {
            return tickCount;
        }
        return tickCount + EntityUpdateTiering.phaseOffset(entity.getUUID().hashCode(), interval);
    }

    private static int computeEffectiveInterval(Entity entity, int vanillaInterval, Snapshot current) {
        ServerLevel level = (ServerLevel) entity.level();
        int chunkX = entity.getBlockX() >> 4;
        int chunkZ = entity.getBlockZ() >> 4;
        // 密度观测必须在本次调用内完成（本 tick 的观测供下一 tick 判定）
        EntityDensityIndex.observe(level, chunkX, chunkZ);

        boolean itemFlow = isItemFlow(entity);
        // 压力需要「最近观察者」身份，与距离分层是否开启无关（tiered=false 时预算压力仍生效）
        boolean needObserver = current.tiered || current.budgetPerPlayer > 0;
        int tier = needObserver ? nearestObserver(entity, level, current) : 0;
        if (current.tiered && tier < 0) {
            return EntityUpdateTiering.noObserverInterval();
        }
        int[] intervals = itemFlow ? current.itemIntervals : current.entityIntervals;
        int safeTier = tier < 0 ? 0 : Math.min(tier, EntityUpdateTiering.TIER_COUNT - 1);
        int tierInterval = current.tiered ? intervals[safeTier] : 1;

        double density = 1.0;
        if (current.densityThrottle) {
            density = EntityUpdateTiering.densityFactor(
                    EntityDensityIndex.count(level, chunkX, chunkZ),
                    safeTier, current.densityTierCounts, current.densityTierFactors);
        }
        // 压力取自「最近观察者」：单实体的帧率只能有一个值（并集语义），用离它最近的玩家的实测负载
        // 当作该值，天然把远端热点隔离在它自己的视锥里。
        int observerPressure = SCRATCH.observer != null
                ? pressureBook.pressureOf(SCRATCH.observer) : 1;
        double factor = Math.min(current.maxFactor, density * observerPressure);
        return EntityUpdateTiering.effectiveInterval(
                EntityUpdateTiering.intervalFloor(vanillaInterval, itemFlow), tierInterval, factor);
    }

    /**
     * 物品流：掉落物与经验球。原版给这两类实体设的是 20 刻的空闲节拍，实际位置节拍由每 tick 的
     * {@code hasImpulse} 驱动，因此降帧后要用物品专用档位表 + 下限 1（见类注释）。
     */
    private static boolean isItemFlow(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrb;
    }

    /**
     * {@code ServerEntity.sendChanges()} 门条件中 {@code entity.hasImpulse} 的替换值。
     * 受控实体不再享有「冲量强制发包」特权，其发包节拍完全由 {@link #effectiveInterval} 决定。
     */
    public static boolean gateImpulse(Entity entity, boolean original) {
        return original && !eligible(entity, snapshot);
    }

    /** 服务器停止时清理（避免 level / connection 引用随会话累积）。 */
    public static void clear() {
        snapshot = Snapshot.INACTIVE;
        pressureBook.reset();
        bookMaxFactor = 1;
        pressureBook = new EntityPressureBook(1);
        FRAME_PACKETS.clear();
        FRAME_OBSERVERS.clear();
        SCRATCH.tier = -1;
        SCRATCH.observer = null;
        SCRATCH.intervalEntity = null;
        SCRATCH.interval = 0;
        EntityDensityIndex.clear();
        EntityPacketCounters.clear();
    }

    /** 首次激活/失活各打一行 INFO（冒烟与排障锚点）；状态未变不重复打印。 */
    private static void logActivation(Snapshot next) {
        if (next.active == loggedActive) {
            return;
        }
        loggedActive = next.active;
        if (next.active) {
            Constants.LOG.info("Hassium: entity update pacing active (tiered={}, density={}, budgetPerPlayer={}, maxFactor={}, smoothPush={}, entityTiers={}, itemTiers={})",
                    next.tiered, next.densityThrottle, next.budgetPerPlayer, next.maxFactor, next.smoothPush,
                    Arrays.toString(next.entityIntervals), Arrays.toString(next.itemIntervals));
        } else {
            Constants.LOG.info("Hassium: entity update pacing inactive");
        }
    }

    private static boolean eligible(Entity entity, Snapshot current) {
        if (!current.active) {
            return false;
        }
        // 玩家自身位移走 ServerGamePacketListenerImpl，不归本域管；他人视角的玩家位移不可降帧
        if (entity instanceof ServerPlayer) {
            return false;
        }
        // 影子端世界（客户端进程内的服务端）不接网络，不得受影响
        return entity.level().getServer() == current.server;
    }

    private static Snapshot build(MinecraftServer server) {
        if (server == null || server != RuntimeServerContext.getActiveServer()) {
            return Snapshot.INACTIVE;
        }
        // 实体域只改 vanilla 复制节拍（ClientboundMove* 等），不走 Hassium 自定义包，
        // 原版客户端完整兼容，因此**不要求握手/已协商连接**；只跟 master 网络面总闸
        // （专用服 master.enabled / LAN enabledOnLan）+ 本族 entity* 配置。
        if (!ServerNetworkGate.isNetworkServerActive()) {
            return Snapshot.INACTIVE;
        }
        HassiumConfigService config = HassiumConfigService.getInstance();
        boolean tiered = config.isEntityTieredUpdateEnabled();
        boolean density = config.isEntityDensityThrottleEnabled();
        boolean smoothPush = config.isEntitySmoothPushEnabled();
        int budget = Math.max(0, config.getEntityFrameBudgetPerPlayer());
        if (!tiered && !density && !smoothPush && budget <= 0) {
            return Snapshot.INACTIVE;
        }
        return new Snapshot(true, server, tiered,
                EntityUpdateTiering.parseIntervals(
                        config.getEntityTierIntervals(), EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS),
                EntityUpdateTiering.parseIntervals(
                        config.getEntityItemTierIntervals(), EntityUpdateTiering.DEFAULT_ITEM_INTERVALS),
                density,
                EntityUpdateTiering.parseDensityCounts(config.getEntityDensityTierCounts()),
                EntityUpdateTiering.parseDensityFactors(config.getEntityDensityTierFactors()),
                Math.min(16, Math.max(1, config.getEntityMaxThrottleFactor())),
                budget,
                config.isEntitySmoothPushEnabled());
    }

    /**
     * 最近观察者的距离挡位，并把该观察者写进 {@link #SCRATCH}（压力账本按观察者索引）。
     * 并集语义：全体观察者按同一帧放行，取最近者的挡位保证近端不欠帧。
     * 观察者判定用原版同一套跟踪范围（自身与乘客类型的较大者 × 服务端广播范围系数），
     * 且**故意取宽**——多算观察者只会多发帧（退回原版），少算才会欠帧。
     *
     * @return 0..{@code TIER_COUNT - 1}；无观察者/不在任何观察者范围内返回 -1（{@link #SCRATCH} 同时置空）
     */
    private static int nearestObserver(Entity entity, ServerLevel level, Snapshot current) {
        SCRATCH.tier = -1;
        SCRATCH.observer = null;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return -1;
        }
        double range = trackingRangeBlocks(entity, level);
        if (!(range > 0.0)) {
            return -1;
        }
        double entityX = entity.getX();
        double entityZ = entity.getZ();
        double nearestD2 = Double.MAX_VALUE;
        ServerPlayer nearest = null;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            double dx = player.getX() - entityX;
            double dz = player.getZ() - entityZ;
            double d2 = dx * dx + dz * dz;
            if (d2 < nearestD2) {
                nearestD2 = d2;
                nearest = player;
            }
        }
        int tier = EntityUpdateTiering.tierOf(Math.sqrt(nearestD2) / range);
        SCRATCH.tier = tier;
        SCRATCH.observer = tier < 0 ? null : nearest;
        return tier;
    }

    /** 该实体在原版眼中的水平观察范围（格）：自身与乘客跟踪范围取大，再乘服务端广播范围系数。 */
    private static double trackingRangeBlocks(Entity entity, ServerLevel level) {
        int chunks = entity.getType().clientTrackingRange();
        List<Entity> passengers = entity.getPassengers();
        for (int i = 0; i < passengers.size(); i++) {
            int passengerRange = passengers.get(i).getType().clientTrackingRange();
            if (passengerRange > chunks) {
                chunks = passengerRange;
            }
        }
        if (chunks <= 0) {
            return 0.0;
        }
        int blocks = chunks * 16;
        MinecraftServer server = level.getServer();
        return server == null ? blocks : server.getScaledTrackingDistance(blocks);
    }

    private static final class Snapshot {
        private static final Snapshot INACTIVE =
                new Snapshot(false, null, false, null, null, false, null, null, 1, 0, false);

        private final boolean active;
        private final MinecraftServer server;
        private final boolean tiered;
        private final int[] entityIntervals;
        private final int[] itemIntervals;
        private final boolean densityThrottle;
        private final int[] densityTierCounts;
        private final double[] densityTierFactors;
        private final int maxFactor;
        private final int budgetPerPlayer;
        private final boolean smoothPush;

        private Snapshot(boolean active, MinecraftServer server, boolean tiered, int[] entityIntervals, int[] itemIntervals,
                         boolean densityThrottle, int[] densityTierCounts, double[] densityTierFactors,
                         int maxFactor, int budgetPerPlayer, boolean smoothPush) {
            this.active = active;
            this.server = server;
            this.tiered = tiered;
            this.entityIntervals = entityIntervals;
            this.itemIntervals = itemIntervals;
            this.densityThrottle = densityThrottle;
            this.densityTierCounts = densityTierCounts;
            this.densityTierFactors = densityTierFactors;
            this.maxFactor = maxFactor;
            this.budgetPerPlayer = budgetPerPlayer;
            this.smoothPush = smoothPush;
        }
    }
}
