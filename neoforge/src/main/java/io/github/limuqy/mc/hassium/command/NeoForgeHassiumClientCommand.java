package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * NeoForge 客户端命令注册（/hassiumc）。
 * <p>
 * 仅在 Dist.CLIENT 加载，避免专用服务端解析 RegisterClientCommandsEvent。
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class NeoForgeHassiumClientCommand {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        registerClientCommands(event.getDispatcher());
    }

    private static void registerClientCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> hassiumc = Commands.literal("hassiumc")
                .then(Commands.literal("stats")
                        .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                        .executes(NeoForgeHassiumClientCommand::showClientStats))
                .then(Commands.literal("export")
                        .executes(NeoForgeHassiumClientCommand::exportCurrentWorld)
                        .then(Commands.argument("serverIp", StringArgumentType.word())
                                .suggests(NeoForgeHassiumClientCommand::suggestCachedServers)
                                .executes(NeoForgeHassiumClientCommand::exportWithArgs)
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(NeoForgeHassiumClientCommand::exportWithArgs)
                                )
                        )
                );
        dispatcher.register(hassiumc);
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

    /** 无参数：导出当前世界（单人世界会提示错误） */
    private static int exportCurrentWorld(CommandContext<CommandSourceStack> context) {
        Component msg = HassiumCommandHandler.startCacheExport(null, null);
        context.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    /** 解析参数：serverIp [seed]（brigadier 正式参数，自带类型校验与补全） */
    private static int exportWithArgs(CommandContext<CommandSourceStack> context) {
        String serverIp = StringArgumentType.getString(context, "serverIp");
        Long seed = null;
        try {
            seed = LongArgumentType.getLong(context, "seed");
        } catch (IllegalArgumentException ignored) {
            // 未提供 seed
        }

        Component msg = HassiumCommandHandler.startCacheExport(serverIp, seed);
        context.getSource().sendSuccess(() -> msg, false);
        return 1;
    }
}
