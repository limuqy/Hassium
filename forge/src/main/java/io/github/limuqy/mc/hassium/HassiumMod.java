package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.ForgeConfigBackend;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer;
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
        context.registerConfig(ModConfig.Type.CLIENT, CONFIG.clientSpec(), Constants.CONFIG_CLIENT_FILE);
        context.registerConfig(ModConfig.Type.COMMON, CONFIG.serverSpec(), Constants.CONFIG_SERVER_FILE);
        CommonClass.init();

        // review-fix: T10-M1：数据面 BULK 路由对齐 Fabric——先查 UDP 数据面可用（未启用/未绑定/无会话时
        // DataPlaneServer.tryRouteBulk 自检返回 false），命中则走 BULK 通道；否则回退帧通道 Primary
        ChunkSender.setInstance((player, compressed) -> {
            byte[] payload = compressed.encode();
            if (DataPlaneServer.tryRouteBulk(
                    player.getUUID(),
                    DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK,
                    payload)) {
                return; // 已走 Data 通道
            }
            // 未走 Data 通道 → 走 Primary（帧通道），记分流统计（口径 = encode() 总长度，与 Data 侧对齐）
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
