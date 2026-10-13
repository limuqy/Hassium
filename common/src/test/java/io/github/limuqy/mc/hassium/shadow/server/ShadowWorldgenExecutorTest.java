package io.github.limuqy.mc.hassium.shadow.server;

import io.github.limuqy.mc.hassium.shadow.server.ShadowWorldgenExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowWorldgenExecutorTest {

    @Test
    void oldShutdownDoesNotStopNewGenerationTasks() throws Exception {
        var old = ShadowWorldgenExecutor.service(-101);
        var current = ShadowWorldgenExecutor.service(-102);
        try {
            org.junit.jupiter.api.Assertions.assertNotSame(old, current);
            ShadowWorldgenExecutor.shutdown(-101);
            assertTrue(old.isShutdown());
            assertFalse(ShadowWorldgenExecutor.isTerminated(-102));
            assertEquals(42, current.submit(() -> 42).get(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            ShadowWorldgenExecutor.shutdown(-101);
            ShadowWorldgenExecutor.shutdown(-102);
        }
    }

    @Test
    @DisplayName("只有影子上下文才隔离执行器")
    void isolatesOnlyInShadowContext() {
        assertTrue(ShadowWorldgenExecutor.shouldIsolate(true));
        assertFalse(ShadowWorldgenExecutor.shouldIsolate(false),
                "专用服 / 单人必须继续用全局 backgroundExecutor");
    }

    @Test
    @DisplayName("worker 数给渲染留核：processors-2，至少 1")
    void workerCountLeavesCoresForRender() {
        assertEquals(6, ShadowWorldgenExecutor.workerCount(8));
        assertEquals(2, ShadowWorldgenExecutor.workerCount(4));
        assertEquals(1, ShadowWorldgenExecutor.workerCount(3));
        assertEquals(1, ShadowWorldgenExecutor.workerCount(2));
        assertEquals(1, ShadowWorldgenExecutor.workerCount(1));
        assertTrue(ShadowWorldgenExecutor.isTerminated(), "测试进程未创建影子池时应视为已终止");
    }
}
