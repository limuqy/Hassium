package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证服务端主时钟会推进已建立的 KCP 会话并输出 UDP wire datagram。 */
final class DataPlaneUdpServerTickTest {

    @Test
    void serverTickFlushesQueuedKcpPayloadToBoundPeer() throws Exception {
        DataPlaneUdpServer server = DataPlaneUdpServer.forTest(List.of(new HassiumConfig.UdpListenerConfig(
                "127.0.0.1", reserveFreeUdpPort(), 100,
                List.of(new HassiumConfig.ReachableEndpoint("127.0.0.1", 25565, 1)))));
        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(1_500);
            server.bind();
            int port = server.getBoundEndpoints().get(0).boundPort();
            UUID player = UUID.randomUUID();
            // D-M1: per-player per-epoch token——先经 beginControlConnection 签发
            long epoch = DataPlaneUdpServer.beginControlConnection(player, () -> { });
            byte[] token = DataPlaneUdpServer.getBindToken(player, epoch);
            byte[] bind = UdpBindRequestCodec.encodeRequest(token, player, epoch, 0, 0);
            peer.send(new DatagramPacket(bind, bind.length, InetAddress.getLoopbackAddress(), port));
            server.awaitDispatchedFrames(1, 1_500);

            List<ReliableDatagramSession> sessions = server.registry().sessions(player, epoch);
            assertFalse(sessions.isEmpty(), "authenticated bind must create a session");
            assertTrue(sessions.get(0).enqueueAuthenticated(DataPlaneFrame.TYPE_KEEPALIVE, new byte[] {1}));

            long now = System.currentTimeMillis();
            for (int i = 0; i < 4; i++) {
                DataPlaneUdpServer.tick(now + i * 10L);
            }

            byte[] wire = new byte[1_500];
            DatagramPacket received = new DatagramPacket(wire, wire.length);
            peer.receive(received);
            assertTrue(received.getLength() > 0, "server clock must flush KCP output to the peer");
        } finally {
            server.shutdown();
        }
    }

    private static int reserveFreeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
