package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
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
import org.spongepowered.asm.mixin.Unique;
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

        // 分离 Hassium 和非 Hassium 玩家（共用踢出检查）
        List<ServerPlayer> hassiumPlayers = hassium$separatePlayers(players, packet);

        // 没有 Hassium 玩家时直接取消原版 broadcast
        if (hassiumPlayers == null) {
            ci.cancel();
            return;
        }

        // 异步计算 hash 并发送元数据到 pushPool 工作线程
        ChunkPos chunkPos = new ChunkPos(chunkPacket.getX(), chunkPacket.getZ());
        String dimension = LevelCompat.getDimensionId(hassiumPlayers.get(0).level());
        ServerChunkPushManager.getInstance().submitMetadataTask(
                hassiumPlayers, chunkPos, chunkPacket, dimension);

        ci.cancel();
    }

    /**
     * 分离 Hassium 与非 Hassium 玩家：Hassium 玩家收集返回；非 Hassium 玩家主线程直发原版包；
     * 强制 Mod 且握手超时的玩家踢出。chunk / light 两分支共用，行为保持一致。
     */
    // review-fix: T7-62: light 分支缺 isRequireClientMod && isHandshakeTimeout 踢出检查，
    // 提取为共用私有方法消除两分支行为不一致
    @Unique
    private List<ServerPlayer> hassium$separatePlayers(List<ServerPlayer> players, Packet<?> vanillaPacket) {
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
                player.connection.send(vanillaPacket);
            }
        }
        return hassiumPlayers;
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

        // 分离 Hassium 和非 Hassium 玩家（与 chunk 分支共用踢出检查）
        List<ServerPlayer> hassiumPlayers = hassium$separatePlayers(players, lightPacket);

        if (hassiumPlayers == null) {
            ci.cancel();
            return;
        }

        int chunkX = lightPacket.getX();
        int chunkZ = lightPacket.getZ();

        // 尝试通过反射提取 section 位掩码（masks 位于 ClientboundLightUpdatePacketData 内，
        // 经 packet.lightData 导航；Field 首次解析后缓存，热路径零反射开销）。
        // 必须同时提取 empty 掩码：变为全空的 section 只出现在 emptySky/BlockYMask 中，
        // 影子端若不知道这些 section 就无法清掉旧光（光变黑不收敛的场景之一）。
        java.util.BitSet skyMask = new java.util.BitSet();
        java.util.BitSet blockMask = new java.util.BitSet();
        java.util.BitSet emptySkyMask = new java.util.BitSet();
        java.util.BitSet emptyBlockMask = new java.util.BitSet();
        try {
            skyMask = hassium$getMask(lightPacket, true, false);
            blockMask = hassium$getMask(lightPacket, false, false);
            emptySkyMask = hassium$getMask(lightPacket, true, true);
            emptyBlockMask = hassium$getMask(lightPacket, false, true);
        } catch (Exception e) {
            // 反射失败时使用空 BitSet，客户端会重算所有 section
            LOGGER.debug("Hassium: Could not extract light masks via reflection, using empty masks");
        }

        // 构建光照增量包（仅坐标和位掩码，无光照数据）
        LightDeltaS2CPacket.Entry entry = new LightDeltaS2CPacket.Entry(
                chunkX, chunkZ, skyMask, blockMask, emptySkyMask, emptyBlockMask);
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
     * ClientboundLightUpdatePacket.lightData（masks 位于 ClientboundLightUpdatePacketData，
     * 1.20.1+ 均非 packet 直接字段）。首次解析成功后缓存 Field，热路径不再 getDeclaredField；
     * 字段名候选覆盖 mojmap（Forge 运行时）与 intermediary（Fabric 运行时，MCP 核实
     * lightData=field_34872 / skyYMask=field_34873 / blockYMask=field_34874，1.20.1~1.21.11 稳定）。
     */
    // review-fix: T7-61: 热路径反射未缓存 + 字段名重复且层级错误（masks 在 data 对象内）
    @Unique
    private static volatile java.lang.reflect.Field hassium$lightDataField;

    @Unique
    private static volatile java.lang.reflect.Field hassium$skyYMaskField;

    @Unique
    private static volatile java.lang.reflect.Field hassium$blockYMaskField;

    @Unique
    private static volatile java.lang.reflect.Field hassium$emptySkyYMaskField;

    @Unique
    private static volatile java.lang.reflect.Field hassium$emptyBlockYMaskField;

    /**
     * 解析 packet.lightData 对象（masks 容器）；解析失败返回 null。
     */
    @Unique
    private static Object hassium$getLightData(ClientboundLightUpdatePacket lightPacket) throws IllegalAccessException {
        java.lang.reflect.Field field = hassium$lightDataField;
        if (field == null) {
            field = hassium$findAccessibleField(lightPacket.getClass(), "lightData", "field_34872");
            hassium$lightDataField = field;
        }
        return field != null ? field.get(lightPacket) : null;
    }

    /**
     * 解析 skyYMask / blockYMask / emptySkyYMask / emptyBlockYMask（位于 lightData 对象上）；
     * 失败返回空 BitSet。
     */
    @Unique
    private static java.util.BitSet hassium$getMask(ClientboundLightUpdatePacket lightPacket, boolean sky, boolean empty)
            throws IllegalAccessException {
        Object data = hassium$getLightData(lightPacket);
        if (data == null) {
            return new java.util.BitSet();
        }
        java.lang.reflect.Field field = hassium$maskField(sky, empty);
        if (field == null) {
            field = hassium$findAccessibleField(data.getClass(),
                    sky ? (empty ? "emptySkyYMask" : "skyYMask") : (empty ? "emptyBlockYMask" : "blockYMask"),
                    sky ? (empty ? "field_34875" : "field_34873") : (empty ? "field_34876" : "field_34874"));
            hassium$setMaskField(sky, empty, field);
        }
        if (field == null) {
            return new java.util.BitSet();
        }
        Object value = field.get(data);
        return value instanceof java.util.BitSet bs ? bs : new java.util.BitSet();
    }

    @Unique
    private static java.lang.reflect.Field hassium$maskField(boolean sky, boolean empty) {
        if (sky) {
            return empty ? hassium$emptySkyYMaskField : hassium$skyYMaskField;
        }
        return empty ? hassium$emptyBlockYMaskField : hassium$blockYMaskField;
    }

    @Unique
    private static void hassium$setMaskField(boolean sky, boolean empty, java.lang.reflect.Field field) {
        if (sky) {
            if (empty) {
                hassium$emptySkyYMaskField = field;
            } else {
                hassium$skyYMaskField = field;
            }
        } else if (empty) {
            hassium$emptyBlockYMaskField = field;
        } else {
            hassium$blockYMaskField = field;
        }
    }

    /**
     * 按候选字段名查找并放行访问（mojmap / intermediary）；全部未命中返回 null。
     */
    @Unique
    private static java.lang.reflect.Field hassium$findAccessibleField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
