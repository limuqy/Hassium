package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
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
        // 剥光包（四个掩码全空）先交给影子端重算：直接官方 apply 会让客户端在
        // 影子光回传前渲染无光区块（纯黑），随后光包到达再跳亮——水面/水底
        // 「先亮后黑再亮」跳变源之一。影子端收敛后会以带光区块包回传，等价官方
        // 时序（先有权威光再落地），原版「未收敛不发黑块」语义。
        if (isLightStripped(packet)
                && io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submit(pos, packet);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            // 无客户端（单测）/ 主线程：直调（预算/重入由现有 mixin 机制处理）
            invokeChunkHandler(packet);
            return;
        }
        // 非主线程（outbound event loop）：距离优先级预算队列，同位置 REPLACE
        MainThreadDispatcher.execute(() -> invokeChunkHandler(packet), pos);
    }

    /** 服务端剥光包 = sky/block/empty 四掩码全空（服务端只保留了方块数据）。 */
    private boolean isLightStripped(ClientboundLevelChunkWithLightPacket packet) {
        try {
            var light = packet.getLightData();
            return light.getSkyYMask().isEmpty()
                    && light.getBlockYMask().isEmpty()
                    && light.getEmptySkyYMask().isEmpty()
                    && light.getEmptyBlockYMask().isEmpty();
        } catch (Throwable t) {
            return false; // 反射/版本异常：走官方路径，不阻断区块
        }
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
        // M3 仅网关登录修复：帧在 Netty 线程批量解码时捕获的监听器可能在 handle
        // 排队期间已被换掉（登录 S2C 帧与 handleGameProfile 同批解码，query 帧/PLAY
        // login 包都捕获到旧登录监听器）→ 监听器必须在 handle 时机实时解析，
        // 而非解码时捕获。
        // T13 修复：CustomPayload（chunk_payload 全量压缩区块）处理线程安全——
        // handleCompressedChunk 内部 submit 后台解压 + MainThreadDispatcher 回主线程
        // apply，无需先切主线程排队。切主线程会让 chunk_payload（无锚点
        // PRIORITY_UNKNOWN）被权威 chunk apply 挤占积压，全量 chunk 落地延迟 → 8s
        // 超时重发「过期」。
        if (io.github.limuqy.mc.hassium.compat.PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            dispatchToLiveListener(packet);
            return;
        }
        if (mc.isSameThread()) {
            dispatchToLiveListener(packet);
        } else {
            final Packet<?> p = packet;
            MainThreadDispatcher.execute(() -> dispatchToLiveListener(p));
        }
    }

    /**
     * 主线程（handle 时机）实时分发：按当前监听器解析目标，并做「包阶段 ↔ 监听器阶段」
     * 类型安全 gate（T4/P4：R2 重连 ClassCastException 崩溃根因修复）。
     *
     * <p>语义：<b>丢弃</b>（不缓冲回放）——play 阶段包仅在当前解析出的监听器为
     * {@link ClientGamePacketListener} 实例（{@link ClientPacketListener}，即 play 监听器
     * 就绪）时投递；握手监听器（{@link ClientHandshakePacketListenerImpl}，网关登录/重连
     * 早期 {@code mc.getConnection()==null} 的回退目标）只接登录阶段包；其余错配一律
     * debug 丢弃，不抛异常、不入队。丢弃由直推/请求兜底（见下）。
     *
     * <p>三边界覆盖：
     * <ul>
     *   <li><b>{@code mc.getConnection()==null}</b>（网关登录/重连早期，play 监听器未
     *       挂载）：1.20.1 的 {@code getConnection()} = {@code player.connection}，
     *       handleLogin 创建玩家前恒为 null，故不能以 getConnection() 非空作就绪判据
     *       （ClientboundLoginPacket 恰需在此时投递给 play 监听器以创建世界/玩家）——
     *       就绪判据 = 解析出的 listener 类型。</li>
     *   <li><b>listener 未切换</b>（handleGameProfile 尚未换监听器 / T13 同批解码竞态）：
     *       play 包落在握手监听器 → 类型 gate 拦截丢弃（旧代码直接
     *       {@code packet.handle(listener)} → ClassCastException ×3 → 0xC0000409）。</li>
     *   <li><b>跨线程</b>（custom payload 在 Netty 线程直调，监听器切换在主线程）：
     *       listener 在 handle 时机实时解析（M3）+ 类型判定同刻执行——读到的是已切换的
     *       play 监听器则放行，仍是握手监听器则丢弃；单连接内监听器只会握手 → play 单调
     *       推进，不存在「判为 play 后又回退握手」的逆序。</li>
     * </ul>
     *
     * <p>丢弃兜底：数据面包（chunk_payload 等）在监听器就绪后经 hash 比对 /
     * requestFullChunks 重取（P1 直推/请求链），控制面包到达时监听器必然已就绪；
     * 缓冲回放会引入过期帧注入新会话的风险（会话绑定上下文），故不采用。
     */
    private void dispatchToLiveListener(Packet<?> packet) {
        PacketListener listener = resolveListener();
        if (listener == null) {
            LOGGER.debug("Hassium: packet {} inject skipped (no client connection)",
                    packet.getClass().getSimpleName());
            return;
        }
        if (listener instanceof ClientGamePacketListener) {
            // play 监听器就绪：只收 play 阶段包（stale 登录/配置包按原版语义丢弃）
            if (isLoginPhasePacket(packet) || isConfigPhasePacket(packet)) {
                LOGGER.debug("Hassium: stale {}-phase packet {} dropped (client already in PLAY)",
                        isLoginPhasePacket(packet) ? "login" : "config",
                        packet.getClass().getSimpleName());
                return;
            }
            handleOnListener(packet, listener);
            return;
        }
        // 非 play 监听器（握手/配置）：只接各自阶段包；play 包一律丢弃（直推/请求兜底）
        if (isLoginPhasePacket(packet) && listener instanceof ClientLoginPacketListener) {
            handleOnListener(packet, listener);
            return;
        }
#if MC_VER >= MC_1_20_2
        if (listener instanceof net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl) {
            if (isConfigPhasePacket(packet)) {
                handleOnListener(packet, listener);
            } else {
                LOGGER.debug("Hassium: packet {} inject skipped (config listener not ready for phase)",
                        packet.getClass().getSimpleName());
            }
            return;
        }
#endif
        LOGGER.debug("Hassium: packet {} inject skipped (listener {} not ready for its phase)",
                packet.getClass().getSimpleName(), listener.getClass().getSimpleName());
    }

    private PacketListener resolveListener() {
        Minecraft mc = Minecraft.getInstance();
        // Minecraft.getConnection() = ClientPacketListener（play 期非 null）。
        // 登录早期为 null（登录响应注入骨架边界，T11 接管后补 ConnectScreen 缝）；
        // 配置阶段亦为 null——T10 回退到 vanilla Connection 的配置监听器
        // （CONFIG_S2C 帧在配置期分发，避免重复注册表处理时静默丢弃）。
        // M3 仅网关登录：mc.getConnection() 为 null 但本地登录会话存在——登录 S2C /
        // handleGameProfile 后 PLAY S2C 分发到本地 Connection 的当前监听器
        // （无 vanilla 登录，不存在双物化风险；正常登录期仍刻意不注入）。
        PacketListener listener = mc.getConnection();
        if (listener == null) {
            listener = configStageListener();
        }
        if (listener == null) {
            listener = NetworkCore.getInstance().gatewayOnlyLoginListener();
        }
        return listener;
    }

    /** 登录阶段 clientbound 包集合（与 GatewayPlayerBridge.detectProtocol 同宏分界：
     * <1.21.2 为 GameProfile，>=1.21.2 改名 LoginFinished）。 */
    private boolean isLoginPhasePacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.login.ClientboundHelloPacket
                || packet instanceof net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket
                || packet instanceof net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket
#if MC_VER < MC_1_21_2
                || packet instanceof net.minecraft.network.protocol.login.ClientboundGameProfilePacket
#else
                || packet instanceof net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket
#endif
                || packet instanceof net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
    }

    /**
     * 配置阶段 clientbound 包集合（1.20.2+）：configuration 协议包 + common 协议族
     * （配置/play 共用通道，配置监听器可处理）。包名前缀判定覆盖版本间类名变化
     * （SelectKnownPacks 改名等，与 NetworkCore.isConfigPacket 同款口径）。
     * 1.20.1 无配置协议，恒 false。
     */
#if MC_VER >= MC_1_20_2
    private static boolean isConfigPhasePacket(Packet<?> packet) {
        String name = packet.getClass().getName();
        if (name.startsWith("net.minecraft.network.protocol.configuration.")) {
            return true;
        }
#if MC_VER >= MC_1_20_5
        if (name.startsWith("net.minecraft.network.protocol.cookie.")) {
            return true;
        }
#endif
        return packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
                || packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
                || packet instanceof net.minecraft.network.protocol.common.ClientboundPingPacket
                || packet instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
    }
#else
    private static boolean isConfigPhasePacket(Packet<?> packet) {
        return false;
    }
#endif

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
