package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.ForgeConfigBackend;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
#if MC_VER < MC_1_21_6
import net.minecraftforge.eventbus.api.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#endif
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Constants.MOD_ID)
public class HassiumMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");
    private static final ForgeConfigBackend CONFIG = (ForgeConfigBackend) io.github.limuqy.mc.hassium.platform.Services.CONFIG;

    public HassiumMod(FMLJavaModLoadingContext context) {
        // 按物理端二选一注册（对齐 Fabric 单文件行为）：客户端只出 client toml，专用服只出 server toml。
        if (io.github.limuqy.mc.hassium.platform.Services.PLATFORM.isPhysicalClient()) {
            context.registerConfig(ModConfig.Type.CLIENT, CONFIG.clientSpec(), Constants.CONFIG_CLIENT_FILE);
        } else {
            context.registerConfig(ModConfig.Type.COMMON, CONFIG.serverSpec(), Constants.CONFIG_SERVER_FILE);
        }
        CommonClass.init();

        // 直连拓扑（2.0.0 裁剪）：UDP 数据面退役，压缩区块全走 hassium:main 通道 Primary 直发
        ChunkSender.setInstance((player, compressed) -> {
            byte[] payload = compressed.encode();
            NetworkStats.recordBulkSentPrimary(payload.length);
            // review-fix: T11-19 传已编码 payload，避免 sendCompressedChunk 内部二次 encode()（重复分配+拷贝）
            ForgeNetworkManager.sendCompressedChunk(player, payload);
        });
        LOGGER.info("Hassium: ChunkSender registered for Forge");
    }

    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            LOGGER.info("Hassium: Initializing Forge network channels");
            ForgeNetworkManager networkManager = new ForgeNetworkManager();
            networkManager.registerChannels();
        }

        @SubscribeEvent
        public static void onConfigLoad(ModConfigEvent.Loading event) {
            if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
                HassiumConfigService.getInstance().syncFromSpec();
            }
        }

        @SubscribeEvent
        public static void onConfigReload(ModConfigEvent.Reloading event) {
            if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
                HassiumConfigService.getInstance().syncFromSpec();
            }
        }
    }
}
