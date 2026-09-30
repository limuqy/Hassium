package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.shadow.ChunkMapAccessor;
import io.github.limuqy.mc.hassium.shadow.server.ShadowWorldgenExecutor;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.concurrent.CompletableFuture;
import net.minecraft.CrashReport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.PlayerDataStorage;
// authlib：26.3 ServicesKeySet 迁入 com.mojang.authlib.services
#if MC_VER >= MC_26_3
import com.mojang.authlib.services.ServicesKeySet;
#else
import com.mojang.authlib.yggdrasil.ServicesKeySet;
#endif
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.ReportType;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;

/**
 * 影子服务端运行时 API 兼容层：构造/读盘/生成/落盘/编码的版本差异收口于此，
 * {@code ShadowSeedServer} 调用点单行化。
 */
public final class ShadowServerCompat {

    private ShadowServerCompat() {}

    /**
     * 影子 {@link PlayerList}。
     * {@code < 1.21.9}：末参 maxPlayers={@code 1}；
     * {@code ≥ 1.21.9}：末参改为 {@code EmptyNotificationService}（人数改由 {@code getMaxPlayers} 覆写）。
     */
    public static PlayerList createPlayerList(MinecraftServer server, PlayerDataStorage storage) {
        return new PlayerList(server, server.registries(), storage, new EmptyNotificationService()) {};
    }

    /**
     * 等待 {@code ServerChunkCache.getChunkFuture} 完成并取出 {@link ChunkAccess}。
     * {@code < 1.21.1}：返回 {@code Either<ChunkAccess, ChunkLoadingFailure>}，取 left；
     * {@code ≥ 1.21.1}：返回 {@code ChunkResult<ChunkAccess>}，{@code orElse(null)}。
     * {@code ChunkStatus} 包路径在 1.21.1 从 {@code world.level.chunk} 迁到 {@code world.level.chunk.status}。
     *
     * @param biomesOnly true = 生成到 {@code BIOMES}（邻块）；false = {@code FULL}（目标块）
     */
    public static ChunkAccess awaitGeneratedChunk(
            ServerChunkCache cache, ChunkPos pos, boolean biomesOnly, long deadlineNanos) {
        ChunkStatus status = biomesOnly ? ChunkStatus.BIOMES : ChunkStatus.FULL;
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                cache.getChunkFuture(pos.x(), pos.z(), status, true);
        while (!future.isDone()) {
            if (System.nanoTime() > deadlineNanos) {
                return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        try {
            return future.join().orElse(null);
        } catch (Throwable t) {
            // 例如 "No chunk holder after ticket has been added"（票未钉稳时）：
            // 以失败回调，不得把 CompletionException 抛回 local-gen 池/主循环 pollTask。
            Constants.LOG.warn("Hassium: awaitGeneratedChunk join failed ({}, {})", pos.x(), pos.z(), t);
            return null;
        }
    }

    /** 获取注入柱的原版 LIGHT future；邻柱由 LightEngine 的 getter 读取，不单独请求 holder。 */
    public static CompletableFuture<ChunkAccess> requestLightChunk(ServerChunkCache cache, ChunkPos pos) {
        return requestSingleLight(cache, pos.x(), pos.z());
    }

    private static CompletableFuture<ChunkAccess> requestSingleLight(ServerChunkCache cache, int x, int z) {
        return cache.getChunkFuture(x, z, ChunkStatus.LIGHT, false)
                .thenApply(result -> result.orElse(null));
    }


    /**
     * 注入柱 {@code LevelChunk.setBlockState}。
     * {@code < 1.21.5}：第三参 {@code boolean}（{@code false} = 不算邻接更新）；
     * {@code ≥ 1.21.5}：第三参改为 {@code int} flags，用 {@code 0}（UPDATE_NONE）
     * 对齐旧版 {@code false}，禁止邻接更新 / scheduleTick。
     */
    public static BlockState setBlockState(LevelChunk chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state, 0);
    }

    /**
     * 从原版 {@code ChunkMap} 读盘得到 {@link ChunkAccess}（不含 Proto→FULL 转换）。
     * {@code < 1.21.2}：{@code readChunk} 取 NBT 再 {@code ChunkSerializer.read}
     * （1.21.1 起多 {@code RegionStorageInfo} 参）；
     * {@code ≥ 1.21.2}：{@code scheduleChunkLoad} 官方链（{@code SerializableChunkData}）。
     *
     * @return 超时/中断/无柱返回 {@code null}
     */
    public static ChunkAccess loadFromVanillaChunkMap(
            ChunkMapAccessor acc,
            ServerLevel level,
            String levelId,
            ChunkPos pos,
            long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        CompletableFuture<?> future = acc.hassium$scheduleChunkLoad(pos);
        if (!awaitDone(future, deadline)) {
            return null;
        }
        return (ChunkAccess) future.join();
    }

    /**
     * 从 NBT 解析 {@link ChunkAccess}。
     * {@code < 1.21.1}：{@code ChunkSerializer.read(level, poi, pos, tag)}；
     * {@code 1.21.1}：增加 {@code RegionStorageInfo}；
     * {@code ≥ 1.21.2}：{@code ChunkSerializer} 退役，官方读链
     * {@code SerializableChunkData.parse(...).read(...)}（ChunkMap.scheduleChunkLoad 同构）。
     */
    public static ChunkAccess parseChunkNbt(
            ServerLevel level, String levelId, ChunkPos pos, CompoundTag tag) {
        // 【2026-09-21】解码全程持 POI 互斥闸：ChunkSerializer.read 会经
        // PoiManager.checkConsistencyWithBlocks 写 SectionStorage 的非并发结构
        // （裸 Long2ObjectOpenHashMap / LongLinkedOpenHashSet / Long2ByteOpenHashMap）。
        // 原版靠「单线程 mainThreadExecutor 解码」串行，影子端解码在池线程上
        // ⇒ 必须把这份串行化补回来。见 ShadowPoiGate。
        return ShadowPoiGate.callExclusive(() -> parseChunkNbtUnlocked(level, levelId, pos, tag));
    }

    private static ChunkAccess parseChunkNbtUnlocked(
            ServerLevel level, String levelId, ChunkPos pos, CompoundTag tag) {
        // 官方读链两步：parse(NBT→record) → read(record→ChunkAccess；FULL 柱 =
        // ImposterProtoChunk 包 LevelChunk，与 <1.21.2 ChunkSerializer.read 同语义，
        // ShadowSeedServer.toLevelChunk 两条分支都吃得下）。
        // 1.21.9 起 parse 的 RegistryAccess 参换成 PalettedContainerFactory（H 段锚点；
        // 官方调用 ChunkMap 用 level.palettedContainerFactory()）。
        // 2026-09-20 修复：此前此分支是「not wired」stub 恒 return null，客户端影子
        // 缓存读盘（ShadowSeedServer.parseNbtBytes → loadFromDisk）随之全 null
        // （1.21.3 classic R2 实测 schedLoadDiskNull=10074、diskBaselineOk=0）→
        // 本地缓存基线/回放全废 → R2 整柱重拉（fullReq=549、cacheRate 6.2%）。
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
        return data == null ? null
                : data.read(level, level.getPoiManager(),
                        new RegionStorageInfo(levelId, level.dimension(), "chunk"), pos);
    }

    /**
     * 原版区块序列化入口。
     * {@code < 1.21.2}：{@code ChunkSerializer.write}；
     * {@code ≥ 1.21.2}：{@code SerializableChunkData.copyOf(...).write()}。
     */
    public static CompoundTag serializeChunk(ServerLevel level, LevelChunk chunk) {
        // 【2026-09-21】同 parseChunkNbt：序列化侧也持同一把闸（影子 flush / saveAll 与解码
        // 并发时会经同一份 POI 状态）。
        return ShadowPoiGate.callExclusive(() -> serializeChunkUnlocked(level, chunk));
    }

    private static CompoundTag serializeChunkUnlocked(ServerLevel level, LevelChunk chunk) {
        return SerializableChunkData.copyOf(level, chunk).write();
    }

    /**
     * 关闭 1.20.1 forge/neoforge 的「影子注册表重建窗口」（{@code clearLevel} TAIL 后调用）。
     * <p>
     * 版本中立面：{@code >= 1.21.1} 无该窗口（注册表不重建），空实现——调用方
     * （断连保存路径）无需散落 {@code #if}。
     */
    public static void closeRegistryWindow() {
    }

    /**
     * 客户端 Stopping! 后 vanilla 会关停进程级共享 ioPool。
     * 此时再向 IOWorker mailbox 提交会挂起或刷 {@code Cound not schedule mailbox}。
     */
    public static boolean isSharedIoPoolShutdown() {
        return net.minecraft.util.Util.ioPool().service().isShutdown();
    }

    /**
     * {@code MinecraftServer} 构造里的 worldgen executor：影子端用独立 FJP，
     * 其余仍返回原版 {@code Util.backgroundExecutor()}（1.21.2+ 为 {@code TracingExecutor}）。
     */
    public static java.util.concurrent.Executor shadowWorldgenExecutor() {
        if (ShadowWorldgenExecutor.shouldIsolate(RuntimeServerContext.isShadowServerContext())) {
            return new net.minecraft.TracingExecutor(ShadowWorldgenExecutor.service());
        }
        return net.minecraft.util.Util.backgroundExecutor();
    }

    /**
     * 同步等待 ChunkMap IO 落盘。
     * {@code < 1.21.11}：{@code chunkMap.flushWorker()}；
     * {@code ≥ 1.21.11}：{@code chunkMap.synchronize(true).join()}。
     * 共享 ioPool 已关停时直接返回，避免退出窗口无限等待。
     */
    public static void flushChunkWorker(ServerLevel level) {
        if (isSharedIoPoolShutdown()) {
            return;
        }
        level.getChunkSource().chunkMap.synchronize(true).join();
    }

    /**
     * 崩溃报告可读文本。
     * {@code < 1.21.1}：{@code getFriendlyReport()}；
     * {@code ≥ 1.21.1}：{@code getFriendlyReport(ReportType.CRASH)}。
     */
    public static Object friendlyReport(CrashReport crashReport) {
        return crashReport.getFriendlyReport(ReportType.CRASH);
    }

    /**
     * 影子端最小化 {@link Services}。
     * {@code < 1.21.9}：四参（session/servicesKeySet/userApi/profileCache 均可 null）；
     * {@code ≥ 1.21.9}：五参，须补非 null 的 {@code UserNameToIdResolver}/{@code ProfileResolver}
     * （PlayerList 构造期可能查询）。
     */
    public static Services noServices() {
        return new Services(null, ServicesKeySet.EMPTY, null,
                new MockUserNameToIdResolver(), new MockProfileResolver());
    }

    /**
     * WorldLoader.DataLoadContext 的 worldgen 注册表 lookup。
     * 26.3 记录组件改名 {@code datapackWorldgen}→{@code datapackWorldRegistries}。
     */
    public static net.minecraft.core.HolderLookup.Provider datapackWorldGen(
            net.minecraft.server.WorldLoader.DataLoadContext dataLoadContext) {
#if MC_VER >= MC_26_3
        return dataLoadContext.datapackWorldRegistries();
#else
        return dataLoadContext.datapackWorldgen();
#endif
    }

    /**
     * 区块包线格式编码（body，不含协议包 ID）。
     * {@code < 1.21.1}：{@code packet.write(FriendlyByteBuf)}；
     * {@code ≥ 1.21.1}：{@code ClientboundLevelChunkWithLightPacket.STREAM_CODEC}
     * 写入 {@code RegistryFriendlyByteBuf}。
     *
     * @return 失败返回 {@code null}
     */
    @SuppressWarnings("deprecation") // NeoForge 1.21.11+: RegistryFriendlyByteBuf(2-param) deprecated
    public static byte[] encodeLevelChunkPacket(
            ClientboundLevelChunkWithLightPacket chunkPacket, RegistryAccess registryAccess) {
        net.minecraft.network.RegistryFriendlyByteBuf buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), registryAccess);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, chunkPacket);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to encode chunk packet", e);
            return null;
        } finally {
            buf.release();
        }
    }

    /** Modern versions expose native light stages directly on ThreadedLevelLightEngine. */
    public static net.minecraft.world.level.chunk.ProtoChunk createNativeLightChunk(
            ServerLevel level, LevelChunk source, boolean lightCorrect) {
        net.minecraft.world.level.chunk.ProtoChunk proto =
                new net.minecraft.world.level.chunk.ProtoChunk(
                        source.getPos(),
                        net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                        source.getSections(),
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        level,
                        PalettedContainerFactory.create(level.registryAccess()),
                        source.getBlendingData());
        proto.setLightCorrect(lightCorrect);
        return proto;
    }

    public static CompletableFuture<ChunkAccess> initializeNativeLight(
            ServerLevel level, ChunkAccess chunk) {
        net.minecraft.server.level.ThreadedLevelLightEngine engine =
                (net.minecraft.server.level.ThreadedLevelLightEngine)
                        level.getChunkSource().getLightEngine();
        return engine.initializeLight(chunk, chunk.isLightCorrect());
    }

    public static CompletableFuture<ChunkAccess> completeNativeLight(
            ServerLevel level, ChunkAccess chunk) {
        net.minecraft.server.level.ThreadedLevelLightEngine engine =
                (net.minecraft.server.level.ThreadedLevelLightEngine)
                        level.getChunkSource().getLightEngine();
        return engine.lightChunk(chunk, chunk.isLightCorrect());
    }



    private static boolean awaitDone(CompletableFuture<?> future, long deadlineNanos) {
        while (!future.isDone()) {
            if (System.nanoTime() > deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static final class MockProfileResolver implements ProfileResolver {
        @Override
        public Optional<GameProfile> fetchByName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<GameProfile> fetchById(UUID uuid) {
            return Optional.empty();
        }
    }

    private static final class MockUserNameToIdResolver implements UserNameToIdResolver {
        private final Set<NameAndId> savedIds = new HashSet<>();

        @Override
        public void add(NameAndId nameAndId) {
            this.savedIds.add(nameAndId);
        }

        @Override
        public Optional<NameAndId> get(String name) {
            return this.savedIds.stream()
                    .filter(id -> id.name().equals(name))
                    .findFirst()
                    .or(() -> Optional.of(NameAndId.createOffline(name)));
        }

        @Override
        public Optional<NameAndId> get(UUID uuid) {
            return this.savedIds.stream().filter(id -> id.id().equals(uuid)).findFirst();
        }

        @Override
        public void resolveOfflineUsers(boolean offlineMode) {
        }

        @Override
        public void save() {
        }
    }
}
