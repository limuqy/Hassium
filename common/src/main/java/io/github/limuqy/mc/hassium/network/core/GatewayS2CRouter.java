package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * S2C 入站注入器（T5）：网关 outbound 解码出的原版 {@link Packet} → handler 层直调。
 * 注册到 {@link NetworkCore#registerS2CInjector}，主控切换只换 outbound，本路由不动。
 *
 * <p>路由分类：
 * <ul>
 *   <li><b>区块包</b>（{@link ClientboundLevelChunkWithLightPacket}）：经官方
 *       {@code ClientPacketListener.handleLevelChunkWithLight} 注入（第三方 handler 注入
 *       mod 全可见）。T6 后无客户端预算注入（MixinVanillaChunkApplyBudget 已退役）：
 *       区块 apply 直接走 vanilla 主线程路径（T0 事实表：ensureRunningOnSameThread
 *       免费保证线程语义），无死循环/风暴。</li>
 *   <li><b>实体包</b>（7 类）：复用 {@link ClientMetadataHandler#forwardEntityPacket}
 *       转发调用面——注入触发影子端转发，放行原版（不调 vanilla handler）。</li>
 *   <li><b>其他原版包</b>（含登录响应 S2C）：{@code packet.handle(listener)} 官方分发
 *       到对应 handler（1.20.1 handleLogin 的 ensure 非首句 → 仅主线程执行本路径，
 *       HEAD 注入先例同款 isSameThread 语义）。</li>
 * </ul>
 *
 * <p>线程：主线程直调；非主线程到达经 {@link MainThreadDispatcher} 排队
 * （区块包带 chunk 锚点 OP_CHUNK_APPLY，KeyedPriorityQueue REPLACE 语义）。
 */
public final class GatewayS2CRouter implements Consumer<Packet<?>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayS2CRouter");

    public static final GatewayS2CRouter INSTANCE = new GatewayS2CRouter();

    private GatewayS2CRouter() {
    }

    @Override
    public void accept(Packet<?> packet) {
        try {
            route(packet);
        } catch (Throwable t) {
            // 注入器不得因单个坏包打断 NetworkCore.dispatchS2C 扇出
            LOGGER.error("Hassium: S2C route failed for {}", packet.getClass().getSimpleName(), t);
        }
    }

    private void route(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            routeChunk(chunk);
            return;
        }
        if (isEntityPacket(packet)) {
            forwardEntity(packet);
            return;
        }
        dispatchToListener(packet);
    }

    // ==================== 区块包：官方 handleLevelChunkWithLight ====================

    private void routeChunk(ClientboundLevelChunkWithLightPacket packet) {
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            // 无客户端（单测）/ 主线程：直调（预算/重入由现有 mixin 机制处理）
            invokeChunkHandler(packet);
            return;
        }
        // 非主线程（outbound event loop）：距离优先级预算队列，同位置 REPLACE
        MainThreadDispatcher.execute(() -> invokeChunkHandler(packet), pos);
    }

    private void invokeChunkHandler(ClientboundLevelChunkWithLightPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) {
            LOGGER.debug("Hassium: chunk inject skipped (no client connection)");
            return;
        }
        // Minecraft.getConnection() 直接返回 ClientPacketListener（1.20.1~1.21.11 mojmap 一致）
        ClientPacketListener listener = mc.getConnection();
        // 官方区块加载通道：ensureRunningOnSameThread 首句（双版本）免费保证线程语义；
        // T6 后无客户端预算/影子算光注入（MixinLightRecompute / MixinVanillaChunkApplyBudget
        // 已退役），区块 apply 走 vanilla 主线程路径，影子端由 SectionDelta 段级投递喂光
        listener.handleLevelChunkWithLight(packet);
    }

    // ==================== 实体包 7 类：注入触发 + 放行原版 ====================

    /** 与 MixinClientPacketListener 7 处 HEAD 注入完全同集（mojmap 全段一致）。 */
    private boolean isEntityPacket(Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket
                || packet instanceof ClientboundSetEntityDataPacket
                || packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundRemoveEntitiesPacket;
    }

    private void forwardEntity(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            ClientMetadataHandler.forwardEntityPacket(packet);
            return;
        }
        MainThreadDispatcher.execute(() -> ClientMetadataHandler.forwardEntityPacket(packet));
    }

    // ==================== 其他原版包：官方 handler 分发 ====================

    private void dispatchToListener(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            LOGGER.debug("Hassium: packet {} inject skipped (no client)", packet.getClass().getSimpleName());
            return;
        }
        // Minecraft.getConnection() = ClientPacketListener（play 期非 null）。
        // 登录早期为 null（登录响应注入骨架边界，T11 接管后补 ConnectScreen 缝）；
        // 配置阶段亦为 null——T10 回退到 vanilla Connection 的配置监听器
        // （CONFIG_S2C 帧在配置期分发，避免重复注册表处理时静默丢弃）。
        PacketListener listener = mc.getConnection();
        if (listener == null) {
            listener = configStageListener();
        }
        if (listener == null) {
            LOGGER.debug("Hassium: packet {} inject skipped (no client connection)",
                    packet.getClass().getSimpleName());
            return;
        }
        if (mc.isSameThread()) {
            handleOnListener(packet, listener);
        } else {
            final PacketListener target = listener;
            final Packet<?> p = packet;
            MainThreadDispatcher.execute(() -> handleOnListener(p, target));
        }
    }

    /**
     * T10：配置阶段监听器回退——{@code Minecraft.connection} 为 null 时取 vanilla
     * Connection（NetworkCore 暂存）的当前监听器；仅当其为配置监听器时才注入
     * （登录期 vanilla 连接监听器是登录监听器——刻意不注入，登录 S2C 由骨架边界
     * 丢弃，防止主控登录桥与 vanilla 登录双物化）。
     */
#if MC_VER >= MC_1_20_2
    private static PacketListener configStageListener() {
        net.minecraft.network.Connection conn =
                io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().vanillaConnection();
        if (conn == null) {
            return null;
        }
        PacketListener listener = conn.getPacketListener();
        if (listener instanceof net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl) {
            return listener;
        }
        return null;
    }
#else
    private static PacketListener configStageListener() {
        return null;
    }
#endif

    /** 官方分发：{@code Packet.handle(listener)} → listener.handleXxx(packet)。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleOnListener(Packet<?> packet, PacketListener listener) {
        ((Packet) packet).handle(listener);
    }
}
