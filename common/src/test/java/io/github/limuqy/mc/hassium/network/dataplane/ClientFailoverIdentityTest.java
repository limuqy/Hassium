package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientFailoverIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void initialFailureLaunchesPersistedCandidatesWithoutTerminalCleanup() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClientFailoverIdentity.initialize(tempDir.resolve("endpoints.properties"), launcher);
        ClientFailoverIdentity.prepareInitialConnection("primary.example:25565");
        ClientFailoverIdentity.mergeAdvertisedCandidates(List.of(
                new ControlEndpoint("backup-a.example", 25565, 10),
                new ControlEndpoint("backup-b.example", 25565, 9)));

        assertTrue(ClientFailoverIdentity.onInitialTcpConnectionFailed());
        assertEquals(List.of("backup-a.example:25565"), launcher.launched);
        assertTrue(ClientFailoverIdentity.isRecovering());
    }

    @Test
    void successfulFallbackUsesPrimaryCacheIdentityOnce() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClientFailoverIdentity.initialize(tempDir.resolve("endpoints.properties"), launcher);
        ClientFailoverIdentity.prepareInitialConnection("primary.example:25565");
        ClientFailoverIdentity.mergeAdvertisedCandidates(List.of(
                new ControlEndpoint("backup.example", 25565, 10)));
        assertTrue(ClientFailoverIdentity.onInitialTcpConnectionFailed());

        assertTrue(ClientFailoverIdentity.onPrimaryHandshakeAccepted(
                new ControlEndpoint("backup.example", 25565, 10)));
        assertEquals("primary.example:25565",
                ClientFailoverIdentity.cacheIdentity("backup.example:25565"));
        assertTrue(ClientFailoverIdentity.consumeSuccessfulFallback().isPresent());
        assertTrue(ClientFailoverIdentity.consumeSuccessfulFallback().isEmpty());
    }

    @Test
    void fallbackHandshakeKeepsCacheIdentityAfterSuccess() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClientFailoverIdentity.initialize(tempDir.resolve("endpoints.properties"), launcher);
        ClientFailoverIdentity.prepareInitialConnection("primary.example:25565");
        ClientFailoverIdentity.mergeAdvertisedCandidates(List.of(
                new ControlEndpoint("backup.example", 25565, 10)));

        assertTrue(ClientFailoverIdentity.onInitialTcpConnectionFailed());
        assertTrue(ClientFailoverIdentity.onPrimaryHandshakeAccepted(
                new ControlEndpoint("backup.example", 25565, 10)));

        assertEquals("primary.example:25565",
                ClientFailoverIdentity.cacheIdentity("backup.example:25565"));
    }

    @Test
    void loggedOutReconnectMarksSyntheticCandidateAndRotatesOnFailure() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClientFailoverIdentity.initialize(tempDir.resolve("endpoints.properties"), launcher);
        ClientFailoverIdentity.prepareInitialConnection("primary.example:25565");
        ClientFailoverIdentity.mergeAdvertisedCandidates(List.of(
                new ControlEndpoint("backup-a.example", 25565, 10),
                new ControlEndpoint("backup-b.example", 25565, 9)));
        // 首次成功进服（不需恢复态握手）
        ClientFailoverIdentity.onHandshakeAccepted();

        // 已登录后主控断开：坐标 P1 #3 —— 必须记录 marker + activeFallback，使 MixinMinecraft 拦截。
        ClientFailoverIdentity.onPrimaryDisconnected(null, "channel_inactive");
        assertEquals(List.of("backup-a.example:25565"), launcher.launched);
        // 备用缓存身份指向主地址
        assertEquals("primary.example:25565",
                ClientFailoverIdentity.cacheIdentity("backup-a.example:25565"));
        // 该候选失败应轮转到 backup-b
        // 运行时走 DisconnectedScreen → onInitialTcpConnectionFailed，orchestrator 的
        // recovering 分支轮演下一候选。
        assertTrue(ClientFailoverIdentity.onInitialTcpConnectionFailed());
        assertEquals(List.of("backup-a.example:25565", "backup-b.example:25565"), launcher.launched);
    }
    private static final class RecordingLauncher implements ControlReconnectLauncher {
        final java.util.ArrayList<String> launched = new java.util.ArrayList<>();

        @Override
        public void connect(ControlEndpoint endpoint, Runnable onFailure) {
            launched.add(endpoint.host() + ":" + endpoint.port());
        }
    }
}
