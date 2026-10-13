package io.github.limuqy.mc.hassium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连服 {@code clearLevel} 与真实断连的会话门：标题画面空拆除不得当成 logout。
 */
class ClientLifecycleHelperTest {

    @Test
    void registrySyncPlatformsWaitForPlayBeforeLoadingShadowWorld() {
        assertFalse(ClientLifecycleHelper.shouldPreloadShadow(false, false));
        assertFalse(ClientLifecycleHelper.shouldPreloadShadow(true, false));
        assertFalse(ClientLifecycleHelper.shouldPreloadShadow(true, true));
        assertTrue(ClientLifecycleHelper.shouldPreloadShadow(false, true));
    }

    @Test
    void titleScreenConnectIsNotAnActiveSession() {
        assertFalse(ClientLifecycleHelper.hasActiveClientSession(false, false, false));
    }

    @Test
    void loginInitializedIsAnActiveSessionEvenWithoutLevelYet() {
        assertTrue(ClientLifecycleHelper.hasActiveClientSession(true, false, false));
    }

    @Test
    void existingWorldOrPlayerIsAnActiveSession() {
        assertTrue(ClientLifecycleHelper.hasActiveClientSession(false, true, false));
        assertTrue(ClientLifecycleHelper.hasActiveClientSession(false, false, true));
        assertTrue(ClientLifecycleHelper.hasActiveClientSession(true, true, true));
    }
}
