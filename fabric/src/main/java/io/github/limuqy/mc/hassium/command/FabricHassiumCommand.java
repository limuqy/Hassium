package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.limuqy.mc.hassium.compat.PermissionCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import java.util.concurrent.CompletableFuture;

/**
 * Fabric 命令注册
 */
public class FabricHassiumCommand {

    /**
     * 注册服务端命令
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerServerCommands(dispatcher);
        });
    }

    /**
     * 注册客户端命令（在客户端环境中调用）
     */
    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerClientCommandsInternal(dispatcher);
        });
    }

    private static void registerServerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("hassium")
                        .requires(source -> PermissionCompat.hasCommandPermission(source, 2))
                        .then(Commands.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(FabricHassiumCommand::showServerStats)
                                .then(Commands.literal("reset")
                                        .executes(FabricHassiumCommand::resetStats))
                                .then(Commands.literal("toggle")
                                        .executes(FabricHassiumCommand::toggleStats))
                        )
                        .then(Commands.literal("metrics")
                                .then(Commands.literal("on")
                                        .executes(ctx -> toggleMetrics(ctx, true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> toggleMetrics(ctx, false)))
                        )
        );
    }

    private static void registerClientCommandsInternal(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("hassiumc")
                        .then(ClientCommandManager.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(FabricHassiumCommand::showClientStats)
                        )
                        .then(ClientCommandManager.literal("export")
                                .executes(FabricHassiumCommand::exportCurrentWorld)
                                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                        .suggests(FabricHassiumCommand::suggestCachedServers)
                                        .executes(FabricHassiumCommand::exportWithArgs)
                                )
                        )
                        .then(migrateSubtree())
        );
        // /hassium migrate 别名（统一三端命令名；与 /hassiumc migrate 共用子树）
        dispatcher.register(
                ClientCommandManager.literal("hassium")
                        .then(migrateSubtree())
        );
    }

    /**
     * migrate 子树（/hassiumc migrate 与 /hassium migrate 共用）。
     * <p>
     * 单一 greedyString 参数分发 list/status/endpoint：字面量子命令（list/status）与
     * 字符串参数（endpoint）注册为兄弟节点时 brigadier 必然报参数歧义告警
     * （"Ambiguity between arguments ..."），合并后零歧义；tab 补全经
     * {@link #suggestMigrate} 给出 list/status/缓存服务器列表。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> migrateSubtree() {
        return ClientCommandManager.literal("migrate")
                .executes(FabricHassiumCommand::migrateUsage)
                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                        .suggests(FabricHassiumCommand::suggestMigrate)
                        .executes(FabricHassiumCommand::migrateDispatch));
    }

    private static CompletableFuture<Suggestions> suggestMigrate(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        builder.suggest("list");
        builder.suggest("status");
        HassiumCommandHandler.getCachedServerIds().forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** migrate <list|status|host:port> 统一分发。 */
    private static int migrateDispatch(CommandContext<FabricClientCommandSource> context) {
        String args = StringArgumentType.getString(context, "args");
        switch (args) {
            case "list" -> migrateList(context);
            case "status" -> migrateStatus(context);
            default -> migrateToEndpoint(context);
        }
        return 1;
    }

    /** migrate 无参数：用法帮助 */
    private static int migrateUsage(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal(HassiumCommandHandler.migrateUsage()));
        return 1;
    }

    private static int migrateList(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal(HassiumCommandHandler.migrateList()));
        return 1;
    }

    /** 解析端点参数：migrate <host:port> */
    private static int migrateToEndpoint(CommandContext<FabricClientCommandSource> context) {
        String endpoint = StringArgumentType.getString(context, "endpoint");
        context.getSource().sendFeedback(Component.literal(HassiumCommandHandler.migrateTo(endpoint)));
        return 1;
    }

    private static int migrateStatus(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal(HassiumCommandHandler.migrateStatus()));
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestCachedServers(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        HassiumCommandHandler.getCachedServerIds().forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** 无参数：导出当前世界（单人世界会提示错误） */
    private static int exportCurrentWorld(CommandContext<FabricClientCommandSource> context) {
        String msg = HassiumCommandHandler.startCacheExport(null, null);
        context.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }

    /** 解析参数：serverIp [seed] */
    private static int exportWithArgs(CommandContext<FabricClientCommandSource> context) {
        String args = StringArgumentType.getString(context, "args");
        String serverIp;
        Long seed = null;

        // 解析：最后一个空格后的部分如果能解析为 long 则是 seed
        int lastSpace = args.lastIndexOf(' ');
        if (lastSpace > 0) {
            String lastPart = args.substring(lastSpace + 1);
            try {
                seed = Long.parseLong(lastPart);
                serverIp = args.substring(0, lastSpace);
            } catch (NumberFormatException e) {
                // 不是数字，整个 args 是 serverIp
                serverIp = args;
            }
        } else {
            serverIp = args;
        }

        String msg = HassiumCommandHandler.startCacheExport(serverIp, seed);
        context.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }

    private static int showServerStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getServerStatsMessage();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int showClientStats(CommandContext<FabricClientCommandSource> context) {
        String message = HassiumCommandHandler.getClientStatsMessage();
        context.getSource().sendFeedback(Component.literal(message));
        return 1;
    }

    private static int resetStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.resetStats();
        context.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int toggleStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.toggleStats();
        context.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int toggleMetrics(CommandContext<CommandSourceStack> context, boolean enabled) {
        NetworkStats.setEnabled(enabled);
        String message = enabled
                ? "§aHassium 指标收集已启用，使用 /hassium stats 查看§r"
                : "§cHassium 指标收集已关闭§r";
        context.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }
}
