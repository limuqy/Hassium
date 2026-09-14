package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
#if MC_VER < MC_1_21_6
import net.minecraftforge.eventbus.api.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#endif
import net.minecraftforge.fml.common.Mod;

#if MC_VER >= MC_1_21_1
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
#endif

/**
 * Forge 服务端主导握手入口（配置阶段任务，FORGE 总线）。
 * <p>
 * 等价 NeoForge {@code RegisterConfigurationTasksEvent}：{@code GatherLoginConfigurationTasksEvent}
 * 在连接进入配置阶段时发出，Forge 自身（{@code ForgeNetworkConfigurationHandler}）以同一事件先注册
 * {@code RegisterChannelsTask}/{@code ModVersionsTask} 等协商任务——本任务排队其后，执行时对端
 * 通道注册已完成（{@code Channel.isRemotePresent} 可靠），故不存在「发早被踢」竞态。
 * 1.20.1 无配置阶段（login query 载体），本类为空壳。
 */
#if MC_VER >= MC_1_21_1
@Mod.EventBusSubscriber(modid = Constants.MOD_ID)
public final class ForgeHandshakeEvents {

    private ForgeHandshakeEvents() {
    }

    @SubscribeEvent
    public static void onGatherLoginConfigurationTasks(GatherLoginConfigurationTasksEvent event) {
        net.minecraft.network.Connection connection = event.getConnection();
        if (connection == null || ForgeNetworkManager.CHANNEL == null) {
            return;
        }
        if (!io.github.limuqy.mc.hassium.network.ServerNetworkGate.shouldSendLoginHandshake(connection)) {
            return;
        }
        // 官方同款过滤（ForgeNetworkConfigurationHandler.gatherInit）：仅 modded 连接发 hello。
        // vanilla 客户端不注册任务（零干扰）；未装 Hassium 的 Forge 客户端会收到 hello，
        // 其网络层按未知 channel 静默忽略（ForgeHooks.onCustomPayload findTarget=null，不踢不崩）。
        if (net.minecraftforge.network.NetworkContext.get(connection).getType()
                != net.minecraftforge.network.ConnectionType.MODDED) {
            return;
        }
        // 冒烟门禁/排障依赖此行：区分「事件未触发 vs 非 modded 连接 vs 正常登记任务」。
        io.github.limuqy.mc.hassium.Constants.LOG.info(
                "[PRE_HANDSHAKE] gather event (forge, modded)");
        event.addTask(new net.minecraftforge.network.config.SimpleConfigurationTask(
                ForgeNetworkManager.PRE_HANDSHAKE_TASK_TYPE,
                ctx -> ForgeNetworkManager.sendPreHandshakeHello(ctx.getConnection())));
    }
}
#else
final class ForgeHandshakeEvents {

    private ForgeHandshakeEvents() {
    }
}
#endif
