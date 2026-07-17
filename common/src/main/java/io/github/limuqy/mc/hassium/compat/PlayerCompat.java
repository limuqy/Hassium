package io.github.limuqy.mc.hassium.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * ServerPlayer 获取世界 API 兼容层。
 * <p>
 * 1.21.5-: {@code player.serverLevel()} / {@code player.server}
 * 1.21.6+: {@code player.level()} / 经 level 取 server
 */
public final class PlayerCompat {
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
     * 获取当前服务器视距（区块）。
     */
    public static int getViewDistance(ServerPlayer player) {
#if MC_VER < MC_1_21_6
        return player.server.getPlayerList().getViewDistance();
#else
        return player.level().getServer().getPlayerList().getViewDistance();
#endif
    }
}
