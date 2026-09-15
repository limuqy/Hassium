package io.github.limuqy.mc.hassium.compat;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.mixin.MixinShadowConnectionAccessor;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 影子虚拟玩家版本差异收口：连接桩 / ServerPlayer 构造 / placeNewPlayer /
 * chunk 移动驱动 / 原版 chunk 系统簿记泵。业务面 {@code ShadowTrackingSession} 零 {@code #if}。
 * <p>
 * 虚拟玩家配方 = 原版 {@code GameTestHelper.makeMockServerPlayerInLevel()}：
 * {@code new ServerPlayer(server, level, profile)} + {@code PlayerList.placeNewPlayer(connection, player)}。
 * 连接桩 = 常开 {@code EmbeddedChannel} + dummy 管道（vanilla {@code setupCompression/
 * setEncryptionKey} 按名操作管道，splitter/decoder/prepender/encoder 四件套必须存在，
 * 不装 packet_handler 防重名）→ {@code isConnected()/hasDisconnected()} = 已连接，
 * S2C 全部进 dummy 管道丢弃。
 */
public final class ShadowPlayerCompat {

    private ShadowPlayerCompat() {}

    /** 伪造 SERVERBOUND Connection + 常开 EmbeddedChannel（S2C 丢弃、无积压）。 */
    public static Connection createConnectionStub() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel embedded = new EmbeddedChannel();
        // vanilla 按名操作管道（setEncryptionKey/setupCompression/setupInboundProtocol）：
        // 四件套必须存在；不装 packet_handler（防 setupInboundProtocol 重名）。
        embedded.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter() {});
        embedded.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter() {});
        embedded.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter() {});
        embedded.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter() {});
        MixinShadowConnectionAccessor accessor = (MixinShadowConnectionAccessor) (Object) connection;
        accessor.hassium$setShadowChannel(embedded);
        accessor.hassium$setShadowAddress(
                new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
        // NeoForge：stub 需「已协商全部 payload」，否则 placeNewPlayer 期间
        // TF sync_quests / AoA player_data_sync 等 checkPacket 抛 UOE → tracking 失败。
        try {
            io.github.limuqy.mc.hassium.platform.Services.PLATFORM
                    .prepareShadowStubConnection(connection);
        } catch (Throwable ignored) {
        }
        return connection;
    }

    /**
     * 创建影子虚拟玩家（未注册进 world；注册走 {@link #placePlayer}）。
     * isSpectator=false 必须覆写：{@code ChunkMap.skipPlayer} 会把 spectator 排除出
     * player tracking。isCreative=true 避免存活/饥饿分支。
     */
    public static ServerPlayer createVirtualPlayer(MinecraftServer server, ServerLevel level) {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("HassiumShadowVirtualPlayer".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "HassiumShadow");
#if MC_VER < MC_1_21_1
        return new ServerPlayer(server, level, profile) {
#else
        return new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault()) {
#endif
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
#if MC_VER >= MC_1_21_1
            /**
             * 1.21.1+ {@code ServerPlayer} 构造/落位会同步 {@code adjustSpawnLocation →
             * Level.getChunk}，而影子端生成任务由 ChunkMap worker 池驱动
             * （非 mainThreadProcessor），主循环线程在此等待 chunk future 会自锁
             * （fabric 1.21.1 冒烟实证：影子主循环卡死、pull 全零）。直接返回玩家当前
             * 落点，跳过探测。
             * <p>
             * 必须返回「当前位置」而非传入的共享出生点：1.21.6 起该调用从构造器移到
             * {@code PlayerList.placeNewPlayer}，且紧跟 {@code snapTo} —— 若返回出生点，
             * 会把 {@code ensureVirtualPlayer} 预先 {@code setPosRaw} 的客户端坐标重置到
             * 世界出生点（tracking 轴心错位）。构造期当前位置是 (0,0,0)，位置仍随后由
             * {@code setPosRaw}/{@code moveVirtualPlayer} 定稿。
             */
            @Override
            public net.minecraft.core.BlockPos adjustSpawnLocation(
                    net.minecraft.server.level.ServerLevel $$0, net.minecraft.core.BlockPos $$1) {
                return this.blockPosition();
            }
#endif
        };
    }

    /** 注册虚拟玩家（vanilla placeNewPlayer：连接接线、玩家表、level 实体注册、区块簿记）。 */
    public static void placePlayer(MinecraftServer server, Connection connection, ServerPlayer player) {
#if MC_VER < MC_1_21_1
        server.getPlayerList().placeNewPlayer(connection, player);
#else
        server.getPlayerList().placeNewPlayer(connection, player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(
                        player.getGameProfile(), false));
#endif
    }

    /** 移出虚拟玩家（vanilla 登出路径：playerdata 保存、实体/票务/玩家表/广播簿记全清）。 */
    public static void removeVirtualPlayer(MinecraftServer server, ServerPlayer player) {
        server.getPlayerList().remove(player);
    }

    /** 同维度位置更新后驱动原版 player tracking（票更新 + 在途区块玩家通知）。 */
    public static void moveVirtualPlayer(ServerPlayer player) {
#if MC_VER < MC_1_21_6
        player.serverLevel().getChunkSource().move(player);
#else
        // 1.21.6+：ServerPlayer.serverLevel() 移除（收敛至 Entity.level()）
        ((net.minecraft.server.level.ServerLevel) player.level()).getChunkSource().move(player);
#endif
    }

    /**
     * 影子虚拟玩家跨维度：不走 {@code ServerPlayer.teleportTo}。
     * vanilla 切维会往 dummy 连接塞 Respawn/能力/level-info 包，编码失败或管道异常
     * 会让 {@code ShadowTrackingSession} 卡在旧维度，真客户端切维后世界全空。
     */
    public static void teleportVirtualPlayer(ServerPlayer player, ServerLevel level,
                                             double x, double y, double z, float yRot, float xRot) {
        ServerLevel from = PlayerCompat.getServerLevel(player);
        if (from == level) {
#if MC_VER < MC_1_21_5
            player.absMoveTo(x, y, z, yRot, xRot);
#else
            player.teleportTo(x, y, z);
            player.setYRot(yRot);
            player.setXRot(xRot);
#endif
            moveVirtualPlayer(player);
            return;
        }
        from.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION);
        ((io.github.limuqy.mc.hassium.mixin.EntityAccessor) player).hassium$unsetRemoved();
        player.setPosRaw(x, y, z);
        player.setYRot(yRot);
        player.setXRot(xRot);
        player.setServerLevel(level);
#if MC_VER < MC_1_21_1
        level.addDuringCommandTeleport(player);
#else
        level.addDuringTeleport(player);
#endif
        moveVirtualPlayer(player);
    }

    /**
     * 驱动影子 chunk 系统簿记（票→holder→玩家通知/卸载；不含 random tick/spawn）。
     * 等价 {@code ServerChunkCache.tick(haveTime, false)} 的非 worldgen 部分；
     * {@code haveTime} 限定卸载/清理预算，避免 processUnloads 一次性长突发。
     */
    public static void tickChunkSystem(ServerLevel level, long deadlineNanos) {
        BooleanSupplier haveTime = () -> System.nanoTime() < deadlineNanos;
        ((ServerChunkCache) level.getChunkSource()).tick(haveTime, false);
    }

    /** 影子 chunk 系统视距（玩家 tracking 半径）；未显式设置时 vanilla 默认 10。 */
    public static void setChunkViewDistance(ServerLevel level, int viewDistance) {
        ((ServerChunkCache) level.getChunkSource()).setViewDistance(viewDistance);
    }

    /**
     * 1.21+：把虚拟玩家 {@code PlayerChunkSender} 待发队列泵出去。
     * <p>
     * 原版泵在 {@code MinecraftServer.tickServer} 的 {@code send chunks} 阶段；
     * 影子 {@code runMainLoop} 只 {@code pollTask}，不跑完整 tick，不泵则 pending
     * 只增不减。组包本身由 {@code MixinPlayerChunkSender.sendChunk} 在影子上下文
     * 取消（物化桥在 {@code onChunkReadyToSend}）。1.20.1 无 PlayerChunkSender，空操作。
     */
    public static void flushVirtualPlayerChunks(ServerPlayer player) {
#if MC_VER >= MC_1_21_1
        if (player == null || player.connection == null) {
            return;
        }
        try {
            player.connection.chunkSender.sendNextChunks(player);
        } catch (Throwable t) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_TRACK] flush virtual player chunks failed", t);
        }
#endif
    }
}
