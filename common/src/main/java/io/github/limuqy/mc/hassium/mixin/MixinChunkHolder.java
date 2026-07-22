package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截 ChunkHolder.broadcast：对 Hassium 客户端发送 contentHash 元数据。
 * <p>
 * 1.21.1+：{@code pos} 上移到 {@code GenerationChunkHolder}，此处改从 packet 取坐标，
 * 避免跨版本 @Shadow 父类字段。
 */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ChunkHolder");

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void hassium$onBroadcast(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundLightUpdatePacket lightPacket) {
            hassium$onLightUpdate(players, lightPacket, ci);
            return;
        }
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            return;
        }

        // 分离 Hassium 和非 Hassium 玩家
        List<ServerPlayer> hassiumPlayers = null;
        for (ServerPlayer player : players) {
            if (PlayerCompressionTracker.isCompressionEnabled(player)) {
                if (hassiumPlayers == null) {
                    hassiumPlayers = new ArrayList<>();
                }
                hassiumPlayers.add(player);
            } else {
                // 检查是否强制要求客户端 Mod
                if (HassiumConfigService.getInstance().isRequireClientMod()
                        && PlayerCompressionTracker.isHandshakeTimeout(player)) {
                    player.connection.disconnect(Component.literal(
                            "This server requires the Hassium mod. Please install it to join."));
                    continue;
                }
                // 非 Hassium 玩家在主线程上发送原版 packet
                player.connection.send(packet);
            }
        }

        // 没有 Hassium 玩家时直接取消原版 broadcast
        if (hassiumPlayers == null) {
            ci.cancel();
            return;
        }

        // 异步计算 hash 并发送元数据到 pushPool 工作线程
        ChunkPos chunkPos = new ChunkPos(chunkPacket.getX(), chunkPacket.getZ());
        String dimension = hassiumPlayers.get(0).level().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        ServerChunkPushManager.getInstance().submitMetadataTask(
                hassiumPlayers, chunkPos, chunkPacket, dimension);

        ci.cancel();
    }

    /**
     * 拦截 ClientboundLightUpdatePacket，对 Hassium 客户端发送轻量光照增量通知。
     * <p>
     * 剥离光照数据，仅发送区块坐标和 section 位掩码。客户端本地重算光照。
     */
    private void hassium$onLightUpdate(List<ServerPlayer> players, ClientboundLightUpdatePacket lightPacket,
                                       CallbackInfo ci) {
        if (!HassiumConfigService.getInstance().isLightDeltaStrip()) {
            return;
        }

        // 分离 Hassium 和非 Hassium 玩家
        List<ServerPlayer> hassiumPlayers = null;
        for (ServerPlayer player : players) {
            if (PlayerCompressionTracker.isCompressionEnabled(player)) {
                if (hassiumPlayers == null) {
                    hassiumPlayers = new ArrayList<>();
                }
                hassiumPlayers.add(player);
            } else {
                // 非 Hassium 玩家发送原版包
                player.connection.send(lightPacket);
            }
        }

        if (hassiumPlayers == null) {
            ci.cancel();
            return;
        }

        int chunkX = lightPacket.getX();
        int chunkZ = lightPacket.getZ();

        // 尝试通过反射提取 section 位掩码
        java.util.BitSet skyMask = new java.util.BitSet();
        java.util.BitSet blockMask = new java.util.BitSet();
        try {
            Class<?> clazz = lightPacket.getClass();
            // 尝试不同的字段名（yarn / mojmap / intermediary）
            skyMask = tryGetBitSetField(clazz, lightPacket, "skyYMask", "f_132411_", "skyYMask");
            blockMask = tryGetBitSetField(clazz, lightPacket, "blockYMask", "f_132412_", "blockYMask");
        } catch (Exception e) {
            // 反射失败时使用空 BitSet，客户端会重算所有 section
            LOGGER.debug("Hassium: Could not extract light masks via reflection, using empty masks");
        }

        // 构建光照增量包（仅坐标和位掩码，无光照数据）
        LightDeltaS2CPacket.Entry entry = new LightDeltaS2CPacket.Entry(chunkX, chunkZ, skyMask, blockMask);
        LightDeltaS2CPacket deltaPacket = new LightDeltaS2CPacket(List.of(entry));

        // 发送给所有 Hassium 玩家
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
        deltaPacket.encode(buf);

        for (ServerPlayer player : hassiumPlayers) {
            net.minecraft.network.FriendlyByteBuf copy = new net.minecraft.network.FriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer());
            copy.writeBytes(buf);
            copy.readerIndex(0);
            Services.NETWORK_MANAGER.sendLightDeltaPacket(player, copy);
        }
        buf.release();

        ci.cancel();
    }

    /**
     * 尝试通过反射获取 BitSet 字段
     */
    private static java.util.BitSet tryGetBitSetField(Class<?> clazz, Object obj, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value instanceof java.util.BitSet bs) {
                    return bs;
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
        }
        return new java.util.BitSet();
    }
}
