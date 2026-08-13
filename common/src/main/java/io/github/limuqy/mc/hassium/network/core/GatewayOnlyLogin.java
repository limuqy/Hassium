package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.client.ConnectScreenAccessor;
import io.github.limuqy.mc.hassium.client.MinecraftAccessor;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.GatewayConnectionAccessor;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * M3 仅网关登录会话（主连接失效恢复，仅网关登录）：原版连接失败（DisconnectedScreen
 * 拦截，{@link NetworkCore#tryStartGatewayOnlyLogin}）后，用本地伪造 Connection 驱动
 * 一次「仅网关」登录——C2S 全走 outbound（MixinConnection 登录监听器中继 LOGIN_C2S），
 * S2C 经 {@link GatewayS2CRouter} 登录期回退注入本地监听器。
 *
 * <p>机制（CONTRACTS §4 / T2/T3 已完成链路）：
 * <ol>
 *   <li>本地 {@link Connection}(CLIENTBOUND) + EmbeddedChannel 伪造（服务端桥
 *       {@code createGatewayConnection} 同款骨架：dummy splitter/decoder/prepender/encoder）；
 *       {@code Minecraft.pendingConnection} 挂载后由 runTick（level==null 分支）泵 tick。</li>
 *   <li>登录监听器 {@link ClientHandshakePacketListenerImpl}（版本分支构造）+ 状态回调
 *       更新 ConnectScreen 文案。</li>
 *   <li>outbound 打开（NetworkCore.onOpen）→ 主线程发 {@link ServerboundHelloPacket} →
 *       MixinConnection 登录监听器分支 → {@code relayLoginPacket} → LOGIN_C2S 帧 →
 *       主控 {@code dispatchLoginFrame} 登录桥 → handleGameProfile/登录完成 →
 *       服务端标准流 attach（幂等）→ PLAY。</li>
 *   <li>失败/收尾：outbound 故障 → 下一端点（池耗尽 → DisconnectedScreen）；登录期断开
 *       （本地连接 onDisconnect 被拦截）→ 取断开原因收尾；用户取消（onClose）→ 静默收尾。</li>
 * </ol>
 *
 * <p>线程：start/tryStart 主线程；onOutboundOpen/onOutboundFailed Netty event loop；
 * 收尾跨线程经 {@code mc.execute}。entry 均以 {@code active} 守卫，幂等。
 */
public final class GatewayOnlyLogin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayOnlyLogin");

    private final NetworkCore core;
    private final Minecraft mc;
    private final ServerData serverData;
    private final Screen parent;
    private final List<HassiumConfig.ReachableEndpoint> endpoints;

    /** 本地 Connection（CLIENTBOUND，EmbeddedChannel 伪造）。登录完成后保留（PLAY 壳连接）。 */
    private Connection connection;
    private EmbeddedChannel embedded;
    private int endpointIndex = -1;
    private boolean helloSent;
    /** 会话仍处于驱动登录阶段（abort / onLoginCompleted 后 false；入口守卫）。 */
    private boolean active = true;

    public GatewayOnlyLogin(NetworkCore core, Minecraft mc, ServerData serverData, Screen parent,
                            List<HassiumConfig.ReachableEndpoint> endpoints) {
        this.core = core;
        this.mc = mc;
        this.serverData = serverData;
        this.parent = parent;
        this.endpoints = List.copyOf(endpoints);
    }

    /**
     * 启动仅网关登录：本地 Connection 伪造 + 登录监听器 + pendingConnection 挂载 →
     * 连接首个 store 端点。失败（异常）时清理并收尾。
     */
    public void start() {
        try {
            Connection conn = new Connection(PacketFlow.CLIENTBOUND);
            EmbeddedChannel embedded = new EmbeddedChannel();
            // 服务端桥 createGatewayConnection 同款管道骨架（dummy handlers 占位，
            // 空实现保证 isConnected()=true 且 flushQueue/sendPacket 无管道类名耦合）
            embedded.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter() {});
            embedded.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter() {});
            embedded.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter() {});
            embedded.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter() {});
            GatewayConnectionAccessor acc = (GatewayConnectionAccessor) (Object) conn;
            acc.hassium$setGatewayChannel(embedded);
            acc.hassium$setGatewayAddress(new InetSocketAddress("127.0.0.1", 0));
            Consumer<Component> status = this::onStatus;
            PacketListener listener;
#if MC_VER < MC_1_20_5
            listener = new ClientHandshakePacketListenerImpl(conn, mc, serverData, parent, false, null, status);
#elif MC_VER < MC_1_21_9
            listener = new ClientHandshakePacketListenerImpl(conn, mc, serverData, parent, false, null, status, null);
#else
            listener = new ClientHandshakePacketListenerImpl(conn, mc, serverData, parent, false, null, status,
                    new net.minecraft.client.multiplayer.LevelLoadTracker(), null);
#endif
            acc.hassium$setGatewayPacketListener(listener);
            this.connection = conn;
            this.embedded = embedded;
            ((MinecraftAccessor) mc).hassium$setPendingConnection(conn);
            // 原版 ConnectScreen.connection 指向本地伪造连接——(a) 丢弃已终局的死连接，
            // 停止 vanilla tick→handleDisconnection 复触发失败界面；(b) fabric
            // ClientLoginNetworking.registerReceiver 经 getLoginConnection() 回退读
            // ConnectScreen.connection（登录期 pendingConnection 为空）找登录监听器注册
            // 全局 query receiver——置 null 会抛 "Cannot register receiver while client
            // is not logging in!" 中断登录（M3 冒烟实测）。
            if (mc.screen instanceof ConnectScreen) {
                ((ConnectScreenAccessor) mc.screen).hassium$setConnection(conn);
            }
            connectEndpoint(0);
        } catch (Throwable t) {
            LOGGER.error("Hassium: gateway-only login start failed", t);
            abort(Component.translatable("disconnect.genericReason",
                    Component.literal("gateway-only login start failed")));
        }
    }

    // ==================== 入口（NetworkCore 钩子） ====================

    /** outbound 打开（NetworkCore.onOpen，event loop）：主线程发送登录 hello。 */
    void onOutboundOpen() {
        if (!active || helloSent) {
            return;
        }
        helloSent = true;
        Minecraft m = Minecraft.getInstance();
        if (m != null) {
            m.execute(this::sendHello);
        }
    }

    /** outbound 连接/握手失败（NetworkCore.onError/onFault 在仅网关登录期调用）。 */
    void onOutboundFailed() {
        if (!active) {
            return;
        }
        helloSent = false;
        int next = endpointIndex + 1;
        if (next < endpoints.size()) {
            connectEndpoint(next);
        } else {
            LOGGER.error("Hassium: gateway-only login failed — all {} endpoint(s) unreachable", endpoints.size());
            abort(Component.translatable("disconnect.genericReason",
                    Component.literal("gateway endpoints unreachable")));
        }
    }

    /** 登录期断开（本地连接 onDisconnect 失败界面被拦截）：取断开原因收尾。 */
    void onLoginDisconnect() {
        if (!active) {
            return;
        }
        Component reason = null;
        Connection conn = connection;
        if (conn != null) {
#if MC_VER < MC_1_21_1
            reason = conn.getDisconnectedReason();
#else
            net.minecraft.network.DisconnectionDetails details = conn.getDisconnectionDetails();
            reason = details == null ? null : details.reason();
#endif
        }
        if (reason == null) {
            reason = Component.translatable("disconnect.lost");
        }
        LOGGER.warn("Hassium: gateway-only login disconnected: {}", reason.getString());
        abort(reason);
    }

    /** 取消判定（MixinMinecraft.setScreen 拦截）：用户取消 = 回父屏（screen == parent）；
     *  登录成功 handleLogin → setScreen(ReceivingLevelScreen) 非 parent，不取消。
     *  parent 为 null（quickPlay）时 screen == null 判为取消（回 TitleScreen）。 */
    boolean isCancelTarget(Screen screen) {
        return screen == parent;
    }

    /** 用户取消（ConnectScreen Cancel 按钮 → setScreen(parent)）：静默收尾。 */
    void onCancel() {
        if (!active) {
            return;
        }
        LOGGER.info("Hassium: gateway-only login cancelled by user");
        abortSilently();
    }

    /** 登录完成（NetworkCore.onLogin）：会话结束但本地 Connection 保留（PLAY 壳连接，
     * 其监听器已为 ClientPacketListener，C2S 经 MixinConnection 路由；keep-alive 无壳
     * 路径由 NetworkCore.isGatewayOnlyLogin 继续生效，onDisconnect 时清）。 */
    void onLoginCompleted() {
        active = false;
        LOGGER.info("Hassium: gateway-only login completed");
    }

    /** 当前本地连接监听器（登录期 = 登录监听器；handleGameProfile 后 = ClientPacketListener）。 */
    PacketListener listener() {
        Connection conn = connection;
        return conn == null ? null : conn.getPacketListener();
    }

    // ==================== 内部 ====================

    private void connectEndpoint(int index) {
        if (!active || index >= endpoints.size()) {
            return;
        }
        endpointIndex = index;
        helloSent = false;
        HassiumConfig.ReachableEndpoint ep = endpoints.get(index);
        LOGGER.info("Hassium: gateway-only login -> endpoint {}:{} ({} of {})",
                ep.host(), ep.port(), index + 1, endpoints.size());
        core.connect(ep.host(), ep.port(), NetworkCore.buildAutoTail(),
                HassiumConfigService.getInstance().getMasterAuthToken());
    }

    private void sendHello() {
        if (!active || connection == null) {
            return;
        }
        try {
#if MC_VER < MC_1_20_2
            connection.send(new ServerboundHelloPacket(mc.getUser().getName(),
                    Optional.ofNullable(mc.getUser().getProfileId())));
#else
            connection.send(new ServerboundHelloPacket(mc.getUser().getName(), mc.getUser().getProfileId()));
#endif
            LOGGER.info("Hassium: gateway-only login hello sent (endpoint {})", core.lastEndpoint());
        } catch (Throwable t) {
            LOGGER.error("Hassium: gateway-only login hello send failed", t);
        }
    }

    private void onStatus(Component component) {
        LOGGER.info("Hassium: gateway-only login status: {}", component.getString());
        Minecraft m = Minecraft.getInstance();
        if (m != null && m.screen instanceof ConnectScreen cs) {
            ((ConnectScreenAccessor) cs).hassium$setStatus(component);
        }
    }

    /** 失败收尾：关 outbound + 清本地 → 原版失败界面（父屏 + CONNECT_FAILED + 原因）。 */
    private void abort(Component reason) {
        if (!active) {
            return;
        }
        active = false;
        core.closeGatewayOnlyOutbound();
        cleanupLocal();
        core.notifyGatewayOnlySessionEnded();
        Minecraft m = Minecraft.getInstance();
        if (m == null) {
            return;
        }
        m.execute(() -> {
            Minecraft self = Minecraft.getInstance();
            if (self != null) {
                self.setScreen(new DisconnectedScreen(parent, CommonComponents.CONNECT_FAILED, reason));
            }
        });
    }

    /** 静默收尾（用户取消）：不弹失败界面。 */
    private void abortSilently() {
        if (!active) {
            return;
        }
        active = false;
        core.closeGatewayOnlyOutbound();
        cleanupLocal();
        core.notifyGatewayOnlySessionEnded();
    }

    private void cleanupLocal() {
        try {
            Minecraft m = Minecraft.getInstance();
            if (m != null) {
                ((MinecraftAccessor) m).hassium$setPendingConnection(null);
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: gateway-only cleanup pendingConnection skipped", t);
        }
        Connection conn = connection;
        connection = null;
        if (conn != null) {
            try {
#if MC_VER < MC_1_21_1
                conn.disconnect(Component.translatable("disconnect.genericReason",
                        Component.literal("gateway-only login ended")));
#else
                conn.disconnect(new net.minecraft.network.DisconnectionDetails(
                        Component.translatable("disconnect.genericReason",
                                Component.literal("gateway-only login ended"))));
#endif
            } catch (Throwable t) {
                LOGGER.debug("Hassium: gateway-only local connection close skipped", t);
            }
        }
        if (embedded != null) {
            try {
                embedded.close();
            } catch (Throwable t) {
                LOGGER.debug("Hassium: gateway-only embedded channel close skipped", t);
            }
            embedded = null;
        }
    }
}
