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

    /** 跨维度传送（/tp 语义：changeDimension + 玩家簿记；S2C 进 dummy 管道）。 */
    public static void teleportVirtualPlayer(ServerPlayer player, ServerLevel level,
                                             double x, double y, double z, float yRot, float xRot) {
#if MC_VER < MC_1_21_2
        player.teleportTo(level, x, y, z, yRot, xRot);
#else
        // 1.21.2+：6 参 teleportTo(ServerLevel,...) 移除，改为绝对坐标 + 无 relative 标志
        player.teleportTo(level, x, y, z, java.util.Set.of(), yRot, xRot, true);
#endif
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
}
