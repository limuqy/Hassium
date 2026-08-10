package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * ServerPlayer 获取世界 / 服务器 / 连接 API 兼容层。
 * <p>
 * 1.21.5-: {@code player.serverLevel()} / {@code player.server} / {@code player.getServer()}
 * 1.21.6–1.21.8: {@code player.level()}；{@code getServer()} 仍可用
 * 1.21.9+: {@code getServer()} 移除，经 {@code level().getServer()}
 * <p>
 * Connection：1.20.2+ 字段从 {@code ServerGamePacketListenerImpl} 上移到
 * {@code ServerCommonPacketListenerImpl}，需沿继承链反射。
 */
public final class PlayerCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PlayerCompat");
    private static volatile Field connectionField;

    private PlayerCompat() {}

    /**
     * 获取玩家所在的服务端世界。
     */
    public static ServerLevel getServerLevel(ServerPlayer player) {
#if MC_VER < MC_1_21_6
        return player.serverLevel();
#else
        return player.level();
#endif
    }

    /**
     * 获取玩家所属的 MinecraftServer（可能为 null）。
     */
    public static MinecraftServer getMinecraftServer(ServerPlayer player) {
#if MC_VER < MC_1_21_9
        return player.getServer();
#else
        // review-fix: T8-M2: level() 可为 null（玩家脱离世界/断连窗口），判空返回 null，
        // 与旧分支 getServer() 可空语义一致
        ServerLevel level = player.level();
        return level != null ? level.getServer() : null;
#endif
    }

    /**
     * 获取当前服务器视距（区块）。
     */
    public static int getViewDistance(ServerPlayer player) {
#if MC_VER < MC_1_21_6
        return player.server.getPlayerList().getViewDistance();
#else
        // review-fix: T8-M2: level()/getServer() 判空，null 返回 0（调用方按“未知视距”保守处理）
        ServerLevel level = player.level();
        MinecraftServer server = level != null ? level.getServer() : null;
        return server != null ? server.getPlayerList().getViewDistance() : 0;
#endif
    }

    /**
     * 获取玩家底层 {@link Connection}（握手 / ZSTD 切换等用）。
     * <p>
     * 1.20.2+ {@code connection} 在父类声明，不能只用叶子类 {@code getDeclaredField}。
     */
    public static Connection getConnection(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return null;
        }
        try {
            Field field = connectionField;
            if (field == null) {
                field = findConnectionField(player.connection.getClass());
                // 1.20.1 段 Connection 字段为 private final，必须 setAccessible
                // （1.21.1+ 在父类为 public，无需；对 public 字段调用无害）
                field.setAccessible(true);
                connectionField = field;
            }
            return (Connection) field.get(player.connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get connection from player", e);
            return null;
        }
    }

    private static Field findConnectionField(Class<?> clazz) throws NoSuchFieldException {
        // 按类型匹配而非字段名：Forge（SRG）/ Fabric（intermediary）生产运行时的字段名
        // 不是 mojmap 名 "connection"，名字反射在 1.20.1 段全线失败。
        return ReflectionCompat.findFieldByType(clazz, Connection.class, true);
    }

    /**
     * GameProfile UUID 访问器跨版本兼容：1.21.9+ 的 authlib 7.0.61 record 化，
     * {@code getId()} 改 {@code id()}。
     */
    public static java.util.UUID getProfileId(com.mojang.authlib.GameProfile profile) {
        if (profile == null) {
            return null;
        }
#if MC_VER < MC_1_21_9
        return profile.getId();
#else
        return profile.id();
#endif
    }

    /**
     * GameProfile name 访问器跨版本兼容：1.21.9+ record 化后 {@code getName()} 改 {@code name()}。
     */
    public static String getProfileName(com.mojang.authlib.GameProfile profile) {
        if (profile == null) {
            return null;
        }
#if MC_VER < MC_1_21_9
        return profile.getName();
#else
        return profile.name();
#endif
    }
}
