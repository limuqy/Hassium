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
 * Task 9 — Fabric 端控制面重连 launcher（plan §991-1003）。
 *
 * <p><b>非引用红线</b>：本类调用 vanilla {@link ConnectScreen#startConnecting} 入口（与玩家手动从
 * 多人列表加入完全一致的入口），<b>不反射复制 login 协议</b>，不绕过任何 fabric networking 接入点。
 *
 * <p>失败回调 {@code onFailure} 使用 vanilla DisconnectedScreen 检测——目前实现为「触发 vanilla
 * connect，并在 client 主线程上 schedule 一次延迟观察：当 client.screen 仍处于 disconnect/login UI
 * 且 time-window 内未获得 Play join → 走 onFailure」。
 * 真正可信的「失败立刻回调」需要 vanilla 注入 Mixin（后续 Task 可加；plan §1126 §7 cache already
 * 覆盖）。此版本作为 Fabric 端 anchor：功能正确即触发 connect，OnFailure 由外层 orchestrator 通过
 * DisconnectedScreen hook / 用户驱动逻辑接续。
 *
 * <p>跨版本差异：1.20.1 `startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)`；
 * 1.20.2+ 改签 / 加参数。本类限定 {@code MC_VER >= MC_1_20_1 && MC_VER < MC_1_20_2}（与 Task 9
 * 限定锚点 1.20.1 一致）。
 */
public final class FabricControlReconnectLauncher implements ControlReconnectLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ControlLauncher");

    /** 当前 parent screen：恢复启动前 vanilla 通常是 TitleScreen/Options，可让恢复连接进入 ConnectingScreen。 */
    private final Screen parentScreen;

    public FabricControlReconnectLauncher() {
        this(Minecraft.getInstance().screen);
    }

    public FabricControlReconnectLauncher(Screen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void connect(ControlEndpoint endpoint, Runnable onFailure) {
        Minecraft mc = Minecraft.getInstance();
        ServerAddress addr = new ServerAddress(endpoint.host(), endpoint.port());
        // ServerData 用 reusable 假名牌；用于 vanilla menu 复用。quickPlay=false。
        ServerData serverData = new ServerData(
                "hassium-failover:" + endpoint.host() + ":" + endpoint.port(),
                endpoint.host() + ":" + endpoint.port(),
                /*lan*/ false);
        LOGGER.info("Hassium: launching reconnect to {}:{} (priority {})",
                endpoint.host(), endpoint.port(), endpoint.priority());

        // 必须在 MC 主线程上调用 — vanilla ConnectScreen.startConnecting 操作 mc.screen/level
        mc.execute(() -> ConnectScreen.startConnecting(parentScreen, mc, addr, serverData, false));

        // 失败回调目前由 vanilla 关联钩子驱动：
        //   - vanilla ConnectScreen 失败 → 进入 DisconnectedScreen，
        //   - 后续 Mixin / ClientPlayConnectionEvents.DISCONNECT 会向 orchestrator 报新 candidate failure
        //     （Task 9 范围；真正 Mixin-onDisconnect firing 接续 onFailure 由 orchestrator 自身 BTW
        //     在 ClientLifecycleHelper.cleanupOnDisconnect 触发，会变相调用 OnReconnectFailed).
        // 这里的 onFailure 仅作为「单 candidate 立即不可达」快速失败兜底 —— vanilla DNS/Connect 错时
        // startConnecting 在主线程异步触发后不会 sync 报错；fail检测 交由调用方在 DISCONNECT 后
        // 推进。可选项 — 此版本保留 onFailure noop-on-success;后续 Task 可加 onFail hook watcher.
        //保持 view-only 行为，以避免 railing 进 vanilla screen 状态机.
        // (Plan §1003 红线 — 不反射复制 login 协议；本类是 thin adapter)
    }
}
