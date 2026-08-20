package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
#if MC_VER < MC_1_20_2
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
#elif MC_VER < MC_1_20_5
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
#else
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
#endif

/**
 * NeoForge 客户端命令注册（/hassiumc）。
 * <p>
 * 仅在 Dist.CLIENT 加载，避免专用服务端解析 RegisterClientCommandsEvent。
 */
#if MC_VER < MC_1_20_5
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
#else
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
#endif
public class NeoForgeHassiumClientCommand {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        registerClientCommands(event.getDispatcher());
    }

    private static void registerClientCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("hassiumc")
                        .then(Commands.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(NeoForgeHassiumClientCommand::showClientStats))
                        .then(Commands.literal("export")
                                .executes(NeoForgeHassiumClientCommand::exportCurrentWorld)
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests(NeoForgeHassiumClientCommand::suggestCachedServers)
                                        .executes(NeoForgeHassiumClientCommand::exportWithArgs)
                                )
                        )
        );
        // /hassium migrate：仅开发环境注册（正式包不暴露；runClient / 冒烟仍可用）
        // 单一 greedyString 参数分发 list/status/endpoint，避免字面量与字符串兄弟节点歧义。
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            dispatcher.register(
                    Commands.literal("hassium")
                            .then(Commands.literal("migrate")
                                    .executes(NeoForgeHassiumClientCommand::migrateUsage)
                                    .then(Commands.argument("args", StringArgumentType.greedyString())
                                            .suggests(NeoForgeHassiumClientCommand::suggestMigrate)
                                            .executes(NeoForgeHassiumClientCommand::migrateDispatch))
                            )
            );
        }
    }

    private static CompletableFuture<Suggestions> suggestMigrate(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        builder.suggest("list");
        builder.suggest("status");
        HassiumCommandHandler.getCachedServerIds().forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** migrate <list|status|host:port> 统一分发。 */
    private static int migrateDispatch(CommandContext<CommandSourceStack> context) {
        String args = StringArgumentType.getString(context, "args");
        switch (args) {
            case "list" -> migrateList(context);
            case "status" -> migrateStatus(context);
            default -> migrateToEndpoint(context);
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestCachedServers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        HassiumCommandHandler.getCachedServerIds().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int showClientStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getClientStatsMessage();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /** migrate 无参数：用法帮助 */
    private static int migrateUsage(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(HassiumCommandHandler.migrateUsage()), false);
        return 1;
    }

    private static int migrateList(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(HassiumCommandHandler.migrateList()), false);
        return 1;
    }

    /** 解析端点参数：migrate <host:port> */
    private static int migrateToEndpoint(CommandContext<CommandSourceStack> context) {
        String endpoint = StringArgumentType.getString(context, "args");
        context.getSource().sendSuccess(() -> Component.literal(HassiumCommandHandler.migrateTo(endpoint)), false);
        return 1;
    }

    private static int migrateStatus(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(HassiumCommandHandler.migrateStatus()), false);
        return 1;
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
}
