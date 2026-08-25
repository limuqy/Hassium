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

    /**
     * 获取注入柱原版 3×3 FULL 屏障。
     * 原版 ChunkMap 在 playerLoadedChunk 前显式等待中心及一圈邻柱的 FULL future；
     * 单柱 LIGHT/FULL future 都不足以代表这个首包时机。
     */
    public static CompletableFuture<ChunkAccess> requestFullChunk(ServerChunkCache cache, ChunkPos pos) {
        @SuppressWarnings("unchecked")
        CompletableFuture<ChunkAccess>[] futures = new CompletableFuture[9];
        int index = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                futures[index++] = requestSingleFull(cache, pos.x + dx, pos.z + dz);
            }
        }
        CompletableFuture<ChunkAccess> center = requestSingleFull(cache, pos.x, pos.z);
        return CompletableFuture.allOf(futures).thenCombine(center, (ignored, result) -> result);
    }

    private static CompletableFuture<ChunkAccess> requestSingleFull(ServerChunkCache cache, int x, int z) {
#if MC_VER < MC_1_21_1
        return cache.getChunkFuture(x, z, ChunkStatus.FULL, false)
                .thenApply(result -> result.left().orElse(null));
#else
        return cache.getChunkFuture(x, z, ChunkStatus.FULL, false)
                .thenApply(result -> result.orElse(null));
#endif
    }

    /**
     * 注入柱 {@code LevelChunk.setBlockState}。
     * {@code < 1.21.5}：第三参 {@code boolean}（{@code false} = 不算邻接更新）；
     * {@code ≥ 1.21.5}：第三参改为 {@code int} flags（与 {@code level.setBlock(..., 3)} 同值）。
     */
    public static BlockState setBlockState(LevelChunk chunk, BlockPos pos, BlockState state) {
#if MC_VER < MC_1_21_5
        return chunk.setBlockState(pos, state, false);
#else
        return chunk.setBlockState(pos, state, 3);
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
     * 同步等待 ChunkMap IO 落盘。
     * {@code < 1.21.11}：{@code chunkMap.flushWorker()}；
     * {@code ≥ 1.21.11}：{@code chunkMap.synchronize(true).join()}。
     */
    public static void flushChunkWorker(ServerLevel level) {
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
