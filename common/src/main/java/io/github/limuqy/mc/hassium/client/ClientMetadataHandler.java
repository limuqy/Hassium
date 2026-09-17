package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import net.minecraft.client.Minecraft;

/**
 * 客户端区块元数据处理器
 * <p>
 * 方块更新转发到影子端，使影子缓存 content hash 与服务端权威一致。
 * M2: 缓存存储初始化由 MixinClientPacketListener 在 handleLogin 时异步完成。
 */
public class ClientMetadataHandler {

    private ClientMetadataHandler() {
    }

    /** 诊断接口保留兼容格式；区块 admission 不再由客户端维护请求状态。 */
    public static String stallSnapshot() {
        return "fullReq=0";
    }

    /**
     * 方块更新包转发到影子端（MixinClientPacketListener 三个方块包 handler HEAD 注入调用：
     * handleBlockUpdate / handleChunkBlocksUpdate / handleBlockEntityData）。
     * <p>
     * 纯转发：不解析包内容、不 cancel vanilla——影子端
     * {@code ShadowSeedServer.applyBlockUpdate} 内部按 instanceof 分发应用
     * （setBlock / runUpdates / loadFromTag），使影子端缓存内容 hash 与服务端权威一致
     * （方块变动不再导致进服立即 miss 全量重拉）。
     * <p>
     * gate：未进服 / 配置关（{@code isClientFeatureGateOpen}）→ 静默丢弃。
     * 方块包可能先于种子握手完成到达（登录后首批方块更新），首次到达即创建影子端，
     * 保证影子端与客户端世界同源；未握手/创建失败返回 null → 静默跳过（断连/未装配）。
     * 转发调用包 try-catch：异常包不得打断 vanilla 处理。
     */
    public static void forwardBlockUpdate(net.minecraft.network.protocol.Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientFeatureGateOpen()) {
            return;
        }
        io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server =
                io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            return; // 未握手/创建失败（断连或降级）：静默跳过，hash 比对 miss 兜底
        }
        try {
            server.applyBlockUpdate(currentDimension(mc), packet);
        } catch (Throwable ignored) {
            // 纯转发：转发异常不得影响 vanilla 包处理（防恶意包）
        }
    }

    /** 客户端当前所在维度 id（{@code namespace:path}；LevelCompat 封装）。 */
    private static String currentDimension(Minecraft mc) {
        return mc == null || mc.level == null ? null : LevelCompat.getDimensionId(mc.level);
    }
}
