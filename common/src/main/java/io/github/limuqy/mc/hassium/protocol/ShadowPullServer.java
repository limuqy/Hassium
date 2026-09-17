package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.server.ServerChunkPushManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * ShadowPull 服务端统一入口（Fabric/Forge/NeoForge 三端逐字重复的
 * {@code ShadowPullHandler.handle} 参数组装收敛点）。
 * <p>
 * 必须在服务端主线程（{@code server.execute(...)}）内调用；loader 侧只保留
 * 「解包 → 本类 → 编码 → 各自通道回发」的收发外框。
 */
public final class ShadowPullServer {

    private ShadowPullServer() {
    }

    /**
     * 组装请求上下文（维度 / 权威视距 margin / resolver）并执行统一 Compare+Pull 裁决。
     * 返回编码前的响应包；调用方负责 encode 后经各自通道回发。
     * <p>
     * {@code handler} 由调用方传入以保留各加载器既有 ledger 语义
     * （三端均为进程级 {@code SHADOW_PULL_HANDLER} 单例，review §2.2）。
     */
    public static ShadowPullResponseS2CPacket handleRequest(ShadowPullHandler handler, ServerPlayer player,
                                                            ShadowPullRequestC2SPacket request) {
        String dimension = LevelCompat.getDimensionId(player.level());
        return handler.handle(player.getUUID(), request, dimension, request.epoch(), player.chunkPosition().x,
                player.chunkPosition().z,
                PlayerCompat.getViewDistance(player) + ShadowPullRadii.AUTHORITY_MARGIN,
                true, player.isAlive() && !player.hasDisconnected(),
                (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
    }
}
