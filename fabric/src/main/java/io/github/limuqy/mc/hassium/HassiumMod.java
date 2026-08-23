package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");

    @Override
    public void onInitialize() {
        io.github.limuqy.mc.hassium.config.FabricHassiumConfig.register();
        CommonClass.init();

        // 注册网络通道
        LOGGER.info("Hassium: Initializing Fabric network channels");
        FabricNetworkManager networkManager = new FabricNetworkManager();
        networkManager.registerChannels();

        // 设置区块发送器
        ChunkSender.setInstance((player, compressed) -> {
            // 路由器在数据面未启用、未绑定或无可用会话时返回 false，保持 Primary 回退。
            byte[] payload = compressed.encode();
            if (io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.tryRouteBulk(
                    player.getUUID(),
                    DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK,
                    payload)) {
                return; // 已走 Data 通道
            }
            // 未走 Data 通道 → 走 Primary，记分流统计（口径 = encode() 总长度，与 Data 侧对齐）
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentPrimary(payload.length);
            // review-fix: T11-19 传已编码 payload，避免 sendCompressedChunk 内部二次 encode()（重复分配+拷贝）
            FabricNetworkManager.sendCompressedChunk(player, payload);
        });

        // 注册命令
        FabricHassiumCommand.register();
        LOGGER.info("Hassium: Commands registered");
    }
}
