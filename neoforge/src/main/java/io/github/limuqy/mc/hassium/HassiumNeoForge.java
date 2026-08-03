package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.platform.NeoForgeConfigBackend;
import io.github.limuqy.mc.hassium.platform.NeoForgeConfigRegistration;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer;

#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
#elif MC_VER < MC_1_21_1
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;
#else
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 入口类
 * <p>
 * 1.20.1: NeoForge 仍使用 net.minecraftforge 包名 + SimpleChannel
 * 1.20.2–1.20.3: net.neoforged 包名 + SimpleChannel
 * 1.20.4: RegisterPayloadHandlerEvent + CustomPacketPayload.write/id（NeoForge 20.4 移除 SimpleChannel）
 * 1.20.5+: net.neoforged 包名 + Payload/StreamCodec
 */
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {


    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForge");
    private static final NeoForgeConfigBackend CONFIG = (NeoForgeConfigBackend) io.github.limuqy.mc.hassium.platform.Services.CONFIG;

#if MC_VER < MC_1_20_2
    // 1.20.1 的 FML（javafmllanguage 47.x）只反射无参构造器（clazz.getDeclaredConstructor()），
    // 带 IEventBus/ModContainer 注入是 NeoForge 1.20.2+ FancyModLoader 才支持；
    // 1.20.1 的 mod 事件总线从 FMLJavaModLoadingContext 获取。
    public HassiumNeoForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        NeoForgeConfigRegistration.register(CONFIG, Constants.CONFIG_CLIENT_FILE, Constants.CONFIG_SERVER_FILE);
        hassium$init(modEventBus);
    }
#else
    public HassiumNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForgeConfigRegistration.register(modContainer, CONFIG, Constants.CONFIG_CLIENT_FILE, Constants.CONFIG_SERVER_FILE);
        hassium$init(modEventBus);
    }
#endif
    private void hassium$init(IEventBus modEventBus) {
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        CommonClass.init();

        ChunkSender.setInstance((player, compressed) -> {
            // 路由器在数据面未启用、未绑定或无可用会话时返回 false，保持 Primary 回退。
            byte[] payload = compressed.encode();
            if (DataPlaneServer.tryRouteBulk(
                    player.getUUID(),
                    DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK,
                    payload)) {
                return; // 已走 Data 通道
            }
            // 未走 Data 通道 → 走 Primary，记分流统计（口径 = encode() 总长度，与 Data 侧对齐）
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentPrimary(payload.length);
            NeoForgeNetworkManager.sendCompressedChunk(player, compressed);
        });
        LOGGER.info("Hassium: ChunkSender registered for NeoForge");

#if MC_VER < MC_1_20_4
        modEventBus.addListener(this::commonSetup);
#else
        modEventBus.addListener(NeoForgeNetworkManager::registerPayloads);
        LOGGER.info("Hassium: Registered NeoForge payload handlers");
#endif
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
            HassiumConfigService.getInstance().syncFromSpec();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
            HassiumConfigService.getInstance().syncFromSpec();
        }
    }

#if MC_VER < MC_1_20_4
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Hassium: Initializing NeoForge SimpleChannel network");
        NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();
        networkManager.registerChannels();
    }
#endif
}
