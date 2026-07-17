package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PermissionCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
#if MC_VER < MC_1_20_2
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
#elif MC_VER < MC_1_20_5
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
#else
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
#endif

/**
 * NeoForge 服务端命令注册（/hassium）。
 * <p>
 * 客户端命令见 {@link NeoForgeHassiumClientCommand}，避免专用服务端加载 client event 类。
 */
#if MC_VER < MC_1_20_5
@Mod.EventBusSubscriber(modid = Constants.MOD_ID)
#else
@EventBusSubscriber(modid = Constants.MOD_ID)
#endif
public class NeoForgeHassiumCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("hassium")
                        .requires(source -> PermissionCompat.hasCommandPermission(source, 2))
                        .then(Commands.literal("stats")
                                .requires(source -> HassiumCommandHandler.isMetricsEnabled())
                                .executes(NeoForgeHassiumCommand::showServerStats)
                                .then(Commands.literal("reset")
                                        .executes(NeoForgeHassiumCommand::resetStats))
                                .then(Commands.literal("toggle")
                                        .executes(NeoForgeHassiumCommand::toggleStats))
                        )
                        .then(Commands.literal("metrics")
                                .then(Commands.literal("on")
                                        .executes(ctx -> toggleMetrics(ctx, true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> toggleMetrics(ctx, false)))
                        )
        );
    }

    private static int showServerStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getServerStatsMessage();
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
