package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.limuqy.mc.hassium.compat.PermissionCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
#if MC_VER > MC_1_21_5
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.SubscribeEvent;
#endif
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/**
 * Forge 命令注册
 */
@Mod.EventBusSubscriber(modid = io.github.limuqy.mc.hassium.Constants.MOD_ID)
public class ForgeHassiumCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        registerClientCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("hassium")
                        .requires(source -> PermissionCompat.hasCommandPermission(source, 2))
                        .then(Commands.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(ForgeHassiumCommand::showServerStats)
                                .then(Commands.literal("reset")
                                        .executes(ForgeHassiumCommand::resetStats))
                                .then(Commands.literal("toggle")
                                        .executes(ForgeHassiumCommand::toggleStats))
                        )
                        .then(Commands.literal("metrics")
                                .then(Commands.literal("on")
                                        .executes(ctx -> toggleMetrics(ctx, true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> toggleMetrics(ctx, false)))
                        )
        );
    }

    private static void registerClientCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("hassiumc")
                        .then(Commands.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(ForgeHassiumCommand::showClientStats))
                        .then(Commands.literal("export")
                                .executes(ForgeHassiumCommand::exportCurrentWorld)
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests(ForgeHassiumCommand::suggestCachedServers)
                                        .executes(ForgeHassiumCommand::exportWithArgs)
                                )
                        )
        );
    }

    private static CompletableFuture<Suggestions> suggestCachedServers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        HassiumCommandHandler.getCachedServerIds().forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** 无参数：导出当前世界（单人世界会提示错误） */
    private static int exportCurrentWorld(CommandContext<CommandSourceStack> context) {
        String msg = HassiumCommandHandler.startCacheExport(null, null);
        context.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** 解析参数：serverIp [seed] */
    private static int exportWithArgs(CommandContext<CommandSourceStack> context) {
        String args = StringArgumentType.getString(context, "args");
        String serverIp;
        Long seed = null;

        int lastSpace = args.lastIndexOf(' ');
        if (lastSpace > 0) {
            String lastPart = args.substring(lastSpace + 1);
            try {
                seed = Long.parseLong(lastPart);
                serverIp = args.substring(0, lastSpace);
            } catch (NumberFormatException e) {
                serverIp = args;
            }
        } else {
            serverIp = args;
        }

        String msg = HassiumCommandHandler.startCacheExport(serverIp, seed);
        context.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int showServerStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getServerStatsMessage();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int showClientStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getClientStatsMessage();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
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
