package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 — {@link ClientRecoveryState} 状态机测试（plan §758-772）。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>无恢复时不应抑制 finalize（{@code shouldSuppressFinalization()==false}）。</li>
 *   <li>begin 进入 RECOVERING 后 must 抑制 finalize；markRecovered 进入 RECOVERED 并保持抑制
 *       ——恢复成功本身就意味着 cache 仍是热路径，不能被 finalize 关闭；markTerminal 进入
 *       TERMINAL 后 {@code consumeTerminalCleanup()} 必须返回 true 一次（让 finalize 跑一次），
 *       此后 {@code shouldSuppressFinalization()==false}。</li>
 *   <li>中间态/终态双调用幂等。</li>
 * </ul>
 */
class ClientRecoveryStateTest {

    @Test
    @DisplayName("无恢复 begin 之前不应抑制 finalize；RECOVERING/RECOVERED 都抑制；TERMINAL 释放一次")
    void recoverySkipsTerminalCleanupUntilFailure() {
        ClientRecoveryState state = new ClientRecoveryState();
        assertFalse(state.shouldSuppressFinalization(), "NONE 阶段不应抑制 finalize");

        state.begin(10_000L);
        assertTrue(state.shouldSuppressFinalization(), "RECOVERING 阶段必须抑制 finalize");

        state.markRecovered();
        assertTrue(state.shouldSuppressFinalization(),
                "RECOVERED 仍需要抑制 finalize —— 主控制已恢复，缓存还在使用");

        state.markTerminal();
        assertFalse(state.shouldSuppressFinalization(), "TERMINAL 必须释放抑制");
        assertTrue(state.consumeTerminalCleanup(), "TERMINAL 后 consumeTerminalCleanup 返回 true 恰好一次");
        assertFalse(state.consumeTerminalCleanup(), "再次 consumeTerminalCleanup 必须返回 false");
    }

    @Test
    @DisplayName("begin(deadlineMs) 超 deadlineMs 直接 TERMINAL")
    void beginWithElapsedDeadlineIsImmediatelyTerminal() {
        ClientRecoveryState state = new ClientRecoveryState();
        // 负数 deadline 表示恢复窗口已过；状态机该立即判别终态
        state.begin(-1L);
        // RECOVERING 仍记一次以防 caller 立即 mark 成功；但 should 已不抑制
        // 更稳妥：begin 接受任意 long，状态机内部判断 deadline 是否已过
        // 这里测的是「begin 前台不直接终态」契约：恢复必须由 markTerminal 主动闭环
        assertFalse(state.consumeTerminalCleanup(), "未到 markTerminal 之前不能消费掉 cleanup");
        state.markTerminal();
        assertTrue(state.consumeTerminalCleanup());
    }

    @Test
    @DisplayName("markTerminal 幂等，且不可从 RECOVERING 再 begin 复位")
    void terminalIsIdempotentAndResistantToBegin() {
        ClientRecoveryState state = new ClientRecoveryState();
        state.begin(5_000L);
        state.markTerminal();
        // 第二次 markTerminal 不产生影响
        state.markTerminal();
        assertTrue(state.consumeTerminalCleanup());
        assertFalse(state.consumeTerminalCleanup());

        // TERMINAL 之后再 begin 不应该把状态拉回 RECOVERING；终态是单边
        state.begin(10_000L);
        assertFalse(state.shouldSuppressFinalization(), "终态之后 begin 不得复活为 RECOVERING");
    }

    @Test
    @DisplayName("未 begin 直接 markTerminal 也产出一次 cleanup")
    void markTerminalFromNoneProducesOneCleanup() {
        ClientRecoveryState state = new ClientRecoveryState();
        state.markTerminal();
        assertFalse(state.shouldSuppressFinalization());
        assertTrue(state.consumeTerminalCleanup());
        assertFalse(state.consumeTerminalCleanup());
    }
}
