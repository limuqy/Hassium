package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
#if MC_VER > MC_1_21_5
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.SubscribeEvent;
#endif
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/**
 * Forge 客户端命令注册（/hassiumc）。
 * <p>
 * 仅在 Dist.CLIENT 加载，避免专用服务端解析 RegisterClientCommandsEvent。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class ForgeHassiumClientCommand {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        registerClientCommands(event.getDispatcher());
    }

    private static void registerClientCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> hassiumc = Commands.literal("hassiumc")
                .then(Commands.literal("stats")
                        .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                        .executes(ForgeHassiumClientCommand::showClientStats))
                .then(Commands.literal("export")
                        .executes(ForgeHassiumClientCommand::exportCurrentWorld)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(ForgeHassiumClientCommand::suggestCachedServers)
                                .executes(ForgeHassiumClientCommand::exportWithArgs)
                        )
                );
        // migrate 仅开发环境：正式包不暴露演练入口；runClient / 冒烟仍可用
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            hassiumc.then(migrateSubtree());
            dispatcher.register(
                    Commands.literal("hassium")
                            .then(migrateSubtree())
            );
        }
        dispatcher.register(hassiumc);
    }

    /**
     * migrate 子树（/hassiumc migrate 与 /hassium migrate 共用；仅开发环境注册）。
     * <p>
     * 单一 greedyString 参数分发 list/status/endpoint：字面量子命令（list/status）与
     * 字符串参数（endpoint）注册为兄弟节点时 brigadier 必然报参数歧义告警
     * （"Ambiguity between arguments ..."），合并后零歧义；tab 补全经
     * {@link #suggestMigrate} 给出 list/status/缓存服务器列表。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> migrateSubtree() {
        return Commands.literal("migrate")
                .executes(ForgeHassiumClientCommand::migrateUsage)
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(ForgeHassiumClientCommand::suggestMigrate)
                        .executes(ForgeHassiumClientCommand::migrateDispatch));
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
