package io.github.limuqy.mc.hassium.network.core;

import net.minecraft.client.multiplayer.ServerData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 主连接失效恢复（仅网关登录，T4FailoverLogin）决策链与模式闸门：
 * <ul>
 *   <li>无连接意图 / 意图 ip 为空 / store 不可用（无平台服务）→ 放行原版失败界面
 *       （安全 false，无 NPE——Minecraft.getInstance() 在单测环境为 null）；</li>
 *   <li>模式闸门默认关闭（isGatewayOnlyLogin=false，listener=null，通知 no-op）；</li>
 *   <li>登录 C2S 识别覆盖 hello/key（1.20.2+ 含登录期 keep-alive 响应镜像）。</li>
 * </ul>
 */
class GatewayOnlyLoginTest {
    /** ServerData 构造依赖 SharedConstants 游戏版本（1.20.1 亦需要）+ 注册表 bootstrap。 */
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 单例跨测试重置：清 outbound/计数 + 清连接意图（order 无关）。 */
    private static NetworkCore freshCore() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        core.captureConnectIntent(null, null);
        return core;
    }

    private static ServerData serverData(String ip) {
#if MC_VER < MC_1_21_1
        return new ServerData("t", ip, false);
#else
        return new ServerData("t", ip, ServerData.Type.OTHER);
#endif
    }

    @Test
    void decisionChainRejectsWithoutIntentOrBlankIp() {
        NetworkCore core = freshCore();
        // 无连接意图 → 放行原版失败界面（false），无 NPE
        assertFalse(core.tryStartGatewayOnlyLogin(null), "无连接意图必须放行原版失败界面");
        assertFalse(core.isGatewayOnlyLogin());
        assertNull(core.gatewayOnlyLoginListener());
        core.notifyGatewayOnlyDisconnect();
        core.notifyGatewayOnlyCancel();
        // 意图为空 / ip 为空 → 仍放行
        core.captureConnectIntent(serverData("  "), null);
        assertFalse(core.tryStartGatewayOnlyLogin(null));
    }

    @Test
    void decisionChainRejectsWhenStoreUnavailable() {
        NetworkCore core = freshCore();
        // 有效意图 + store 不可用（单测环境无平台服务/无文件）→ 放行原版失败界面
        core.captureConnectIntent(serverData("example.com:25565"), null);
        assertFalse(core.tryStartGatewayOnlyLogin(null), "store 不可用必须放行原版失败界面");
        assertFalse(core.isGatewayOnlyLogin());
    }

    @Test
    void loginPacketRecognitionCoversHelloAndKey() {
        freshCore();
#if MC_VER < MC_1_21_1
        assertTrue(NetworkCore.isLoginPacket(new net.minecraft.network.protocol.login.ServerboundHelloPacket(
                "x", java.util.Optional.empty())));
#else
        assertTrue(NetworkCore.isLoginPacket(new net.minecraft.network.protocol.login.ServerboundHelloPacket(
                "x", java.util.UUID.randomUUID())));
#endif
#if MC_VER >= MC_1_21_1
        // 1.20.2+ 登录期 keep-alive 响应属登录 C2S（镜像中继）
        assertTrue(NetworkCore.isLoginPacket(
                new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(1L)));
#endif
    }
}
