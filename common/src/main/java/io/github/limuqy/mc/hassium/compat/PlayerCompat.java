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
        return player.level().getServer();
#endif
    }

    /**
     * 获取当前服务器视距（区块）。
     */
    public static int getViewDistance(ServerPlayer player) {
#if MC_VER < MC_1_21_6
        return player.server.getPlayerList().getViewDistance();
#else
        return player.level().getServer().getPlayerList().getViewDistance();
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
                connectionField = field;
            }
            return (Connection) field.get(player.connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get connection from player", e);
            return null;
        }
    }

    private static Field findConnectionField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> cursor = clazz;
        while (cursor != null && cursor != Object.class) {
            try {
                Field field = cursor.getDeclaredField("connection");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException("connection not found on " + clazz.getName() + " hierarchy");
    }
}
