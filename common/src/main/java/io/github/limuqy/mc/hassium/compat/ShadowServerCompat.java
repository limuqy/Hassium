package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor;
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
import com.mojang.authlib.yggdrasil.ServicesKeySet;
#if MC_VER >= MC_1_21_9
import net.minecraft.world.level.chunk.PalettedContainerFactory;
#endif
#if MC_VER < MC_1_21_1
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
#if MC_VER >= MC_1_21_1
import net.minecraft.ReportType;
#endif
#if MC_VER >= MC_1_21_1 && MC_VER < MC_1_21_2
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
#endif
#if MC_VER < MC_1_21_2
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
#else
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
#endif
#if MC_VER >= MC_1_21_9
import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
#endif

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
#if MC_VER < MC_1_21_9
        return new PlayerList(server, server.registries(), storage, 1) {};
#else
        return new PlayerList(server, server.registries(), storage, new EmptyNotificationService()) {};
#endif
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
#if MC_VER < MC_1_21_1
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                cache.getChunkFuture(pos.x, pos.z, status, true);
#else
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                cache.getChunkFuture(pos.x, pos.z, status, true);
#endif
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
#if MC_VER < MC_1_21_1
        return future.join().left().orElse(null);
#else
        return future.join().orElse(null);
#endif
    }

    /** 获取注入柱的原版 LIGHT future；邻柱由 LightEngine 的 getter 读取，不单独请求 holder。 */
    public static CompletableFuture<ChunkAccess> requestLightChunk(ServerChunkCache cache, ChunkPos pos) {
        return requestSingleLight(cache, pos.x, pos.z);
    }

    private static CompletableFuture<ChunkAccess> requestSingleLight(ServerChunkCache cache, int x, int z) {
#if MC_VER < MC_1_21_1
        return cache.getChunkFuture(x, z, ChunkStatus.LIGHT, false)
                .thenApply(result -> result.left().orElse(null));
#else
        return cache.getChunkFuture(x, z, ChunkStatus.LIGHT, false)
                .thenApply(result -> result.orElse(null));
#endif
    }


    /**
     * 注入柱 {@code LevelChunk.setBlockState}。
     * {@code < 1.21.5}：第三参 {@code boolean}（{@code false} = 不算邻接更新）；
     * {@code ≥ 1.21.5}：第三参改为 {@code int} flags，用 {@code 0}（UPDATE_NONE）
     * 对齐旧版 {@code false}，禁止邻接更新 / scheduleTick。
     */
    public static BlockState setBlockState(LevelChunk chunk, BlockPos pos, BlockState state) {
#if MC_VER < MC_1_21_5
        return chunk.setBlockState(pos, state, false);
#else
        return chunk.setBlockState(pos, state, 0);
#endif
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
#if MC_VER < MC_1_21_2
        CompletableFuture<java.util.Optional<CompoundTag>> future = acc.hassium$readChunk(pos);
        if (!awaitDone(future, deadline)) {
            Constants.LOG.warn("Hassium: Shadow loadFromDisk timeout ({}, {})", pos.x, pos.z);
            return null;
        }
        CompoundTag tag = future.join().orElse(null);
        if (tag == null) {
            return null;
        }
        return parseChunkNbt(level, levelId, pos, tag);
#else
        CompletableFuture<?> future = acc.hassium$scheduleChunkLoad(pos);
        if (!awaitDone(future, deadline)) {
            return null;
        }
        return (ChunkAccess) future.join();
#endif
    }

    /**
     * 从 NBT 解析 {@link ChunkAccess}。
     * {@code < 1.21.1}：{@code ChunkSerializer.read(level, poi, pos, tag)}；
     * {@code 1.21.1}：增加 {@code RegionStorageInfo}；
     * {@code ≥ 1.21.2}：尚未接线（调用方回落 vanilla {@code scheduleChunkLoad}）。
     */
    public static ChunkAccess parseChunkNbt(
            ServerLevel level, String levelId, ChunkPos pos, CompoundTag tag) {
#if MC_VER < MC_1_21_1
        // 读盘解码同样查该 BiMap（byNameCodec 解码方向）；撞上重建窗口会把未知
        // 调色板项静默替换成 air（promotePartial 只记日志）。ShadowRegistryGate 读锁
        // 保证解码全程不落在 revertToFrozen 重建窗口内（同 serializeChunk）。
        return ShadowRegistryGate.withReadAccess(() -> ChunkSerializer.read(
                level, level.getPoiManager(), pos, tag));
#elif MC_VER < MC_1_21_2
        return ChunkSerializer.read(
                level, level.getPoiManager(),
                new RegionStorageInfo(levelId, level.dimension(), "chunk"),
                pos, tag);
#else
        Constants.LOG.debug("Hassium: parseNbtBytes 1.21.2+ not wired; falling back to vanilla load");
        return null;
#endif
    }

    /**
     * 原版区块序列化入口。
     * {@code < 1.21.2}：{@code ChunkSerializer.write}；
     * {@code ≥ 1.21.2}：{@code SerializableChunkData.copyOf(...).write()}。
     */
    public static CompoundTag serializeChunk(ServerLevel level, LevelChunk chunk) {
#if MC_VER < MC_1_21_1
        // handleClientLevelClosing 会同步执行 GameData.revertToFrozen，清空重灌
        // ForgeRegistry 的 ids/names/keys BiMap（NamespacedWrapper.getResourceKey 直接
        // 委托该 BiMap）。ShadowRegistryGate 以读写门保证 write 全程不落在重建窗口内
        // （MixinMinecraft 在 clearLevel HEAD→TAIL 持写锁；结构性互斥，非概率探测）。
        return ShadowRegistryGate.withReadAccess(() -> ChunkSerializer.write(level, chunk));
#elif MC_VER < MC_1_21_2
        return ChunkSerializer.write(level, chunk);
#else
        return SerializableChunkData.copyOf(level, chunk).write();
#endif
    }

    /**
     * 客户端 Stopping! 后 vanilla 会关停进程级共享 ioPool。
     * 此时再向 IOWorker mailbox 提交会挂起或刷 {@code Cound not schedule mailbox}。
     */
    public static boolean isSharedIoPoolShutdown() {
#if MC_VER >= MC_1_21_11
        return net.minecraft.util.Util.ioPool().service().isShutdown();
#elif MC_VER >= MC_1_21_2
        return net.minecraft.Util.ioPool().service().isShutdown();
#else
        return net.minecraft.Util.ioPool().isShutdown();
#endif
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
#if MC_VER < MC_1_21_11
        level.getChunkSource().chunkMap.flushWorker();
#else
        level.getChunkSource().chunkMap.synchronize(true).join();
#endif
    }

    /**
     * 崩溃报告可读文本。
     * {@code < 1.21.1}：{@code getFriendlyReport()}；
     * {@code ≥ 1.21.1}：{@code getFriendlyReport(ReportType.CRASH)}。
     */
    public static Object friendlyReport(CrashReport crashReport) {
#if MC_VER < MC_1_21_1
        return crashReport.getFriendlyReport();
#else
        return crashReport.getFriendlyReport(ReportType.CRASH);
#endif
    }

    /**
     * 影子端最小化 {@link Services}。
     * {@code < 1.21.9}：四参（session/servicesKeySet/userApi/profileCache 均可 null）；
     * {@code ≥ 1.21.9}：五参，须补非 null 的 {@code UserNameToIdResolver}/{@code ProfileResolver}
     * （PlayerList 构造期可能查询）。
     */
    public static Services noServices() {
#if MC_VER < MC_1_21_9
        return new Services(null, ServicesKeySet.EMPTY, null, null);
#else
        return new Services(null, ServicesKeySet.EMPTY, null,
                new MockUserNameToIdResolver(), new MockProfileResolver());
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
#if MC_VER < MC_1_21_1
        io.netty.buffer.ByteBuf tempBuf = io.netty.buffer.Unpooled.buffer();
        try {
            net.minecraft.network.FriendlyByteBuf friendlyBuf =
                    new net.minecraft.network.FriendlyByteBuf(tempBuf);
            chunkPacket.write(friendlyBuf);
            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.getBytes(0, data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen failed to encode chunk packet", e);
            return null;
        } finally {
            tempBuf.release();
        }
#else
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
#endif
    }

#if MC_VER < MC_1_21_1
    /**
     * 将已装载的区块 section 作为原版 pre-light ProtoChunk 视图使用。
     * <p>
     * 影子存储仍由 ShadowStorageManager 管理；此处只建立 native ChunkStatus
     * 光照入口需要的 ProtoChunk，不接管 ChunkMap/IOWorker 存储生命周期。
     */
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
                        level.registryAccess().registryOrThrow(
                                net.minecraft.core.registries.Registries.BIOME),
                        source.getBlendingData());
        proto.setStatus(lightCorrect
                ? ChunkStatus.LIGHT
                : ChunkStatus.INITIALIZE_LIGHT);
        proto.setLightCorrect(lightCorrect);
        return proto;
    }

    /** 使用原版 ChunkStatus.INITIALIZE_LIGHT loading task，而不是直接调用引擎方法。 */
    public static CompletableFuture<ChunkAccess> initializeNativeLight(
            ServerLevel level, ChunkAccess chunk) {
        return ChunkStatus.INITIALIZE_LIGHT.load(
                        level,
                        level.getStructureManager(),
                        (net.minecraft.server.level.ThreadedLevelLightEngine)
                                level.getChunkSource().getLightEngine(),
                        value -> CompletableFuture.completedFuture(Either.left(value)),
                        chunk)
                .thenApply(result -> result.left().orElseThrow(
                        () -> new IllegalStateException("native INITIALIZE_LIGHT failed")));
    }

    /** 使用原版 ChunkStatus.LIGHT loading task，而不是直接调用引擎方法。 */
    public static CompletableFuture<ChunkAccess> completeNativeLight(
            ServerLevel level, ChunkAccess chunk) {
        if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto
                && !proto.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            proto.setStatus(ChunkStatus.LIGHT);
        }
        return ChunkStatus.LIGHT.load(
                        level,
                        level.getStructureManager(),
                        (net.minecraft.server.level.ThreadedLevelLightEngine)
                                level.getChunkSource().getLightEngine(),
                        value -> CompletableFuture.completedFuture(Either.left(value)),
                        chunk)
                .thenApply(result -> result.left().orElseThrow(
                        () -> new IllegalStateException("native LIGHT failed")));
    }
#else
    /** Modern versions expose native light stages directly on ThreadedLevelLightEngine. */
    public static net.minecraft.world.level.chunk.ProtoChunk createNativeLightChunk(
            ServerLevel level, LevelChunk source, boolean lightCorrect) {
#if MC_VER < MC_1_21_9
        net.minecraft.world.level.chunk.ProtoChunk proto =
                new net.minecraft.world.level.chunk.ProtoChunk(
                        source.getPos(),
                        net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                        source.getSections(),
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        level,
#if MC_VER < MC_1_21_2
                        level.registryAccess().registryOrThrow(
                                net.minecraft.core.registries.Registries.BIOME),
#else
                        (net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome>)
                                level.registryAccess().lookupOrThrow(
                                        net.minecraft.core.registries.Registries.BIOME),
#endif
                        source.getBlendingData());
#else
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
#endif
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
#endif



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

#if MC_VER >= MC_1_21_9
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
#endif
}
