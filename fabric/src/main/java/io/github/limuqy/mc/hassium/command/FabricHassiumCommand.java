package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

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
                        .requires(source -> source.hasPermission(2))
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
        );
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
