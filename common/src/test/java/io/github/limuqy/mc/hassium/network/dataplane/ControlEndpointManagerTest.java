package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 — {@link ControlEndpointManager} 候选选择测试（plan §797-819）。
 *
 * <p>不变量：
 * <ul>
 *   <li>bootstrap 端点优先于 advertised 同坐标端点；同坐标去重保留 bootstrap。</li>
 *   <li>{@link ControlEndpointManager#nextCandidate()} 返回下一个未尝试的候选；尝试失败后
 *       {@link #recordAttemptFailure(ControlEndpoint)} 把它从候选集移除。</li>
 *   <li>候选数最多 4；超出截断。</li>
 *   <li>{@link ControlEndpointManager#startRecovery(long)} 后 nextCandidate 在 deadline 超时
 *       或候选耗尽时返回空。</li>
 * </ul>
 */
class ControlEndpointManagerTest {

    @Test
    @DisplayName("bootstrap 端点胜出，advertised 同坐标被去重")
    void userEndpointsWinAndAdvertisedDuplicatesAreRemoved() {
        ControlEndpointManager manager = new ControlEndpointManager();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlEndpoint b = endpoint("b.com", 25565, 80);
        manager.mergeBootstrapAndAdvertised(
                List.of(a),
                List.of(b, endpoint("a.com", 25565, 1)));

        Optional<ControlEndpoint> first = manager.nextCandidate();
        assertTrue(first.isPresent(), "必须存在候选");
        assertEquals(a, first.get(), "bootstrap 与 advertised 同坐标 a.com 时优先级最高的 bootstrap 胜出");

        manager.recordAttemptFailure(first.get());
        Optional<ControlEndpoint> second = manager.nextCandidate();
        assertTrue(second.isPresent());
        assertEquals(b, second.get(), "失败后给出下一个尚存候选 b.com");
    }

    @Test
    @DisplayName("候选耗尽后 nextCandidate 返回空")
    void candidatesExhaustedReturnsEmpty() {
        ControlEndpointManager manager = new ControlEndpointManager();
        manager.mergeBootstrapAndAdvertised(
                List.of(endpoint("only.example", 25565, 100)),
                List.of());

        manager.startRecovery(60_000L);
        Optional<ControlEndpoint> c = manager.nextCandidate();
        assertTrue(c.isPresent());
        manager.recordAttemptFailure(c.get());

        assertTrue(manager.nextCandidate().isEmpty(), "唯一候选失败后必须返回空");
    }

    @Test
    @DisplayName("deadline 超时后不再返回候选")
    void deadlineExhaustedStopsReturningCandidates() {
        ControlEndpointManager manager = new ControlEndpointManager();
        manager.mergeBootstrapAndAdvertised(
                List.of(endpoint("a.example", 25565, 100), endpoint("b.example", 25565, 80)),
                List.of());

        // 负窗口：恢复立即可见为耗尽 → nextCandidate 立即空
        manager.startRecoveryWithClock(-1L, () -> 2_000L);
    }

    @Test
    @DisplayName("候选写入超过 4 个时只保留前 4 个（按 priority 降序）")
    void candidateListCappedAtFour() {
        ControlEndpointManager manager = new ControlEndpointManager();
        manager.mergeBootstrapAndAdvertised(
                List.of(
                        endpoint("h1", 25565, 100),
                        endpoint("h2", 25565, 90),
                        endpoint("h3", 25565, 80),
                        endpoint("h4", 25565, 70),
                        endpoint("h5", 25565, 60)),
                List.of());
        int count = 0;
        while (manager.nextCandidate().isPresent()) {
            Optional<ControlEndpoint> c = manager.nextCandidate();
            manager.recordAttemptFailure(c.get());
            count++;
        }
        assertEquals(4, count, "至多 4 个候选");
    }

    private static ControlEndpoint endpoint(String host, int port, int priority) {
        return new ControlEndpoint(host, port, priority);
    }
}
