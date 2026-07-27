package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectLauncher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Task 9 — NeoForge 端控制面重连 launcher（plan §991-1003）。
 *
 * <p>与 {@code FabricControlReconnectLauncher} 等价的 thin adapter：调用 vanilla
 * {@link ConnectScreen#startConnecting} 入口（与玩家手动从多人列表加入完全一致的入口），
 * <b>不反射复制 login 协议</b>，亦不绕过任何 NeoForge networking 接入点。
 *
 * <p>跨版本差异（以 {@code docs/version-segments.md} 九锚点为准）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_20_2}（段 A，1.20.1）：{@code new ServerData(String,String,boolean)}
 *       + {@code startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)} —— 5 参数。</li>
 *   <li>{@code MC_1_20_2 <= MC_VER < MC_1_20_5}（段 B/C，1.20.2/1.20.3/1.20.4）：
 *       {@code new ServerData(String,String,ServerData.Type)} + 同 5 参数 {@code startConnecting}。</li>
 *   <li>{@code MC_VER >= MC_1_20_5}（段 D 及以后，1.20.5~1.21.11）：
 *       {@code new ServerData(String,String,ServerData.Type)} + 6 参数
 *       {@code startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, @Nullable TransferState)}，
 *       额外传 {@code null} 表示非服务器转移场景。</li>
 * </ul>
 * 与 Fabric launcher 共用同一组 vanilla 入口签名；NeoForge 不引入额外差异。
 */
public final class NeoForgeControlReconnectLauncher implements ControlReconnectLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeControlLauncher");

    /** 当前 parent screen：恢复启动前 vanilla 通常是 TitleScreen/Options，可让恢复连接进入 ConnectingScreen。 */
    private final Screen parentScreen;

    public NeoForgeControlReconnectLauncher() {
        this(Minecraft.getInstance().screen);
    }

    public NeoForgeControlReconnectLauncher(Screen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void connect(ControlEndpoint endpoint, Runnable onFailure) {
        Minecraft mc = Minecraft.getInstance();
        ServerAddress addr = new ServerAddress(endpoint.host(), endpoint.port());
        // ServerData 用 reusable 假名牌；用于 vanilla menu 复用。两个 vanilla 与 version-segments 边界：
        //   - MC_1_20_2: 第三个参数从 boolean (lan) 改为 ServerData.Type 枚举。
        //   - MC_1_20_5: 段 B 起 ServerData 构造稳定不变；仅 startConnecting 增加 @Nullable TransferState 第6参。
#if MC_VER < MC_1_20_2
        ServerData serverData = new ServerData(
                "hassium-failover:" + endpoint.host() + ":" + endpoint.port(),
                endpoint.host() + ":" + endpoint.port(),
                /*lan*/ false);
#else
        ServerData serverData = new ServerData(
                "hassium-failover:" + endpoint.host() + ":" + endpoint.port(),
                endpoint.host() + ":" + endpoint.port(),
                ServerData.Type.OTHER);
#endif
        LOGGER.info("Hassium: launching reconnect to {}:{} (priority {})",
                endpoint.host(), endpoint.port(), endpoint.priority());

        // 必须在 MC 主线程上调用 — vanilla ConnectScreen.startConnecting 操作 mc.screen/level
        // 两个段外路径：< MC_1_20_5 是 5 参数，>= MC_1_20_5 添加 TransferState(null 表达非转移场景)
#if MC_VER < MC_1_20_5
        mc.execute(() -> ConnectScreen.startConnecting(parentScreen, mc, addr, serverData, false));
#else
        mc.execute(() -> ConnectScreen.startConnecting(parentScreen, mc, addr, serverData, false, null));
#endif

        // 失败回调目前由 vanilla 关联钩子驱动：
        //   - vanilla ConnectScreen 失败 → 进入 DisconnectedScreen，
        //   - 后续 ClientPlayerNetworkEvent.LoggingOut / ClientRecoveryState 会向 orchestrator 报
        //     新 candidate failure（与 Fabric DISCONNECT 钩子等价语义）。
        // 这里的 onFailure 仅作为「单 candidate 立即不可达」快速失败兜底 —— vanilla DNS/Connect 错时
        // startConnecting 在主线程异步触发后不会 sync 报错；fail检测 交由调用方在 DISCONNECT / LoggingOut 后
        // 推进。保持 view-only 行为，以避免 railing 进 vanilla screen 状态机.
        // (Plan §1003 红线 — 不反射复制 login 协议；本类是 thin adapter)
    }
}
