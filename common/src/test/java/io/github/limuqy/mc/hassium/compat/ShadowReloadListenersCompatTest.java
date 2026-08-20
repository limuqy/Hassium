package io.github.limuqy.mc.hassium.compat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影子端 listeners 剪枝空路径（非 TagManager stub 需完整 MC reload API，跨段签名不一）。
 */
class ShadowReloadListenersCompatTest {

    @Test
    void emptyOrNullReturnsEmpty() {
        assertTrue(ShadowReloadListenersCompat.filterForShadow(null).isEmpty());
        assertTrue(ShadowReloadListenersCompat.filterForShadow(List.of()).isEmpty());
    }
}
