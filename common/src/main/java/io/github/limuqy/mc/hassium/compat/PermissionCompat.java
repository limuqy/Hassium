package io.github.limuqy.mc.hassium.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * 跨版本命令权限 API 兼容层。
 * <p>
 * 1.21.4-: {@code CommandSourceStack.hasPermission(int)}
 * 1.21.11+: {@code source.permissions().hasPermission(Permission.HasCommandLevel(...))}
 */
public final class PermissionCompat {
    private PermissionCompat() {
    }

    public static boolean hasCommandPermission(CommandSourceStack source, int level) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }
}
