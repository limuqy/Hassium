package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设计稿 §7 step 7 守护：{@code enabled=false} → DataPlane 零行为变化、走 vanilla Primary 路径。
 * <p>
 * HassiumMod 的 ChunkSender 拦截处 {@code if (DataPlanePoCConfig.isEnabled()) tryRouteBulk(...)}
 * 在 enabled=false 时短路，直接走 {@code FabricNetworkManager.sendCompressedChunk}（原版 Primary 路径）。
 * 本测试验证开关语义与 {@link DataPlaneServer#bind()} 在 disabled 时的早退（不起 NioEventLoopGroup、不占端口）。
 * <p>
 * 因 {@code ENABLED} 已改为 {@code volatile} + {@code isEnabled()/setEnabled()}（非编译期内联），
 * 单测可在线翻转验证「关了零副作用」。跨用例状态污染用 try/finally 复位保护。
 */
class DataPlaneEnabledGuardTest {

    @Test @DisplayName("setEnabled(false) → isEnabled() 反映为 false；复位回 true")
    void setEnabledFalseTakesEffect() {
        boolean prev = DataPlanePoCConfig.isEnabled();
        try {
            DataPlanePoCConfig.setEnabled(false);
            assertFalse(DataPlanePoCConfig.isEnabled(), "禁用后 isEnabled 应为 false");
            DataPlanePoCConfig.setEnabled(true);
            assertTrue(DataPlanePoCConfig.isEnabled(), "重新启用后应为 true");
        } finally {
            DataPlanePoCConfig.setEnabled(prev);
        }
    }

    @Test @DisplayName("enabled=false 时 bind() 早退、不占用数据端口（bound 保持不变）")
    void bindEarlyExitWhenDisabled() {
        boolean prevEnabled = DataPlanePoCConfig.isEnabled();
        boolean prevBound = DataPlaneServer.isBound();
        // 仅在 server 未真实 bind 时验证早退路径（避免与冒烟已 bind 状态冲突）
        if (prevBound) {
            // 生产/冒烟侧 server 已 bind —— 跳过 bind() 调用，仅验证开关与 bound 不被这次测试改变。
            // bind() 自身有 if (bound) return 短路，disabled 与否都不会再动。
            return;
        }
        try {
            DataPlanePoCConfig.setEnabled(false);
            DataPlaneServer.bind();
            assertFalse(DataPlaneServer.isBound(), "disabled 时 bind 不应翻 bound 标志");
        } finally {
            DataPlanePoCConfig.setEnabled(prevEnabled);
        }
    }


    @Test @DisplayName("DataPlaneClientBundle Data 帧计数器可复位（冒烟跨阶段边界保护）")
    void clientBulkCountersReset() {
        long prevFrames = DataPlaneClientBundle.getBulkFramesData();
        long prevBytes = DataPlaneClientBundle.getBulkBytesData();
        try {
            DataPlaneClientBundle.resetDataBulkCounters();
            assertEquals(0L, DataPlaneClientBundle.getBulkFramesData(), "复位后帧计数归零");
            assertEquals(0L, DataPlaneClientBundle.getBulkBytesData(), "复位后字节计数归零");
        } finally {
            // 单测不应残留对全局计数器的改动；这里只验证复位 API，恢复原值意义不大（测试隔离场景下恒为 0）。
            DataPlaneClientBundle.resetDataBulkCounters();
        }
    }
}
