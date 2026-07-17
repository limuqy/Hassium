package io.github.limuqy.mc.hassium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
        );
    }

    private static int showClientStats(CommandContext<CommandSourceStack> context) {
        String message = HassiumCommandHandler.getClientStatsMessage();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
