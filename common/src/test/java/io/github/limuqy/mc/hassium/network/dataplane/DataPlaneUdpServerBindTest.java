package io.github.limuqy.mc.hassium.network.dataplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Task 3 — {@link DataPlaneUdpServer} 端点绑定/首帧 Bind 分派（RED）。
 *
 * <p>纯 server-side 行为：用真实 {@link DatagramSocket} 发一帧裸 UDP {@link UdpBindRequestCodec#encodeRequest}
 * 至 {@link DataPlaneUdpServer#bind()} 起的 NioDatagramChannel，验证服务端对配置错配/正确 BindRequest 的
 * 「拒绝 endpointId 不匹配」「token+uuid+epoch+endpoint 全对后再创建会话」两类行为。
 *
 * <p>故意用两端口 0（OS 自由分配）避免与已有 server/CI 环境冲突；server 完 bind 后由
 * {@link DataPlaneUdpServer#getBoundEndpoints()} 反查实际端口。
 */
final class DataPlaneUdpServerBindTest {

    private static DataPlanePoCConfig.Endpoint ep(String bindHost, int bindPort, int weight) {
        // address 仅用于客户端连接的「对外标识」，本测试不验证客户端反连，故与 bindHost 一致
        return new DataPlanePoCConfig.Endpoint(bindHost, bindPort, weight, bindHost, bindPort);
    }

    /** 用端口 0 全部绑定；server 的 NioDatagramChannel 解析出实际端口后写入 endpointId。 */
    private DataPlanePoCConfig.Endpoint[] eps() {
        return new DataPlanePoCConfig.Endpoint[]{
                ep("127.0.0.1", 0, 10),
                ep("127.0.0.1", 0, 20),
        };
    }

    private static byte[] validBindRequest(byte[] token, UUID player, long epoch, int endpointId, int channelId) {
        return UdpBindRequestCodec.encodeRequest(token, player, epoch, endpointId, channelId);
    }

    /** 端口 0 由 OS 分配；实际 bound port 从 {@link DataPlaneUdpServer#getBoundEndpoints()} 拿。 */
    @Test
    void everyConfiguredEndpointBindsAndAcceptsOnlyMatchingBindEndpoint() throws Exception {
        DataPlaneUdpServer server = DataPlaneUdpServer.forTest(eps());
        try {
            server.bind();
            assertTrue(server.isBound());
            assertEquals(2, server.getBoundEndpoints().size());

            DataPlaneUdpServer.BoundEndpoint first = server.getBoundEndpoints().get(0);
            DataPlaneUdpServer.BoundEndpoint second = server.getBoundEndpoints().get(1);
            // 实际 bind port 由 OS 决定，不全是 0
            assertTrue(first.bindPort() > 0, "OS-assigned port must be positive");
            assertTrue(second.bindPort() > 0);

            // 对每个 endpoint 发一封「正确 endpointId」的 BindRequest，server 应建立会话
            byte[] token = server.getSessionToken();
            UUID alice = new UUID(0xA11CE_A11CEL, 7L);
            long epoch = 1L;

            sendDatagram("127.0.0.1", first.bindPort(),
                    validBindRequest(token, alice, epoch, first.endpointId(), 1));
            sendDatagram("127.0.0.1", second.bindPort(),
                    validBindRequest(token, alice, epoch, second.endpointId(), 1));

            server.awaitDispatchedFrames(2, 1500);

            // 用注册表「sessions(player, epoch)」必须能看到 <2> 条会话——每个 endpoint 一条
            assertEquals(2, server.registry().sessions(alice, epoch).size(),
                    "two matching binds must create two server-side sessions");

            // 错配 endpointId 的 BindRequest 必须被丢弃——endpointId=99 不在配置中
            UUID bob = new UUID(0xB0B_B0BL, 9L);
            sendDatagram("127.0.0.1", first.bindPort(),
                    validBindRequest(token, bob, epoch, 99, 1));
            sendDatagram("127.0.0.1", second.bindPort(),
                    validBindRequest(token, bob, epoch, 99, 1));
            // 唯一匹配留有 99 的配置 (不要 by accident 把 0 算成匹配)
            server.awaitDispatchedFrames(4, 1500);
            assertTrue(server.registry().sessions(bob, epoch).isEmpty(),
                    "wrong endpointId must not create any session");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shutdownReleasesAllChannelsAndTokenAndRegistry() {
        DataPlaneUdpServer server = DataPlaneUdpServer.forTest(eps());
        server.bind();
        assertTrue(server.isBound());

        server.shutdown();
        assertFalse(server.isBound());
        assertFalse(server.isAvailable());
    }

    private static void sendDatagram(String host, int port, byte[] payload) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(800);
            InetAddress addr = InetAddress.getByName(host);
            s.send(new DatagramPacket(payload, payload.length, addr, port));
        }
    }
}
