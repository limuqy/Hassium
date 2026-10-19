package io.github.limuqy.mc.hassium.compat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 影子端 listeners 剪枝空路径（非 TagManager stub 需完整 MC reload API，跨段签名不一）。
 */
class ShadowReloadListenersCompatTest {

    @Test
    void emptyOrNullReturnsEmpty() {
        assertTrue(ShadowReloadListenersCompat.filterForShadow(null).isEmpty());
        assertTrue(ShadowReloadListenersCompat.filterForShadow(List.of()).isEmpty());
    }

    @Test
    void recognizesFabricLoaderId() {
        assertTrue(ShadowReloadListenersCompat.hasFabricResourceLoader(
                Set.of("fabric-resource-loader-v0")::contains));
    }

    @Test
    void recognizesForgifiedLoaderIdFromNeoForgeManifest() {
        assertTrue(ShadowReloadListenersCompat.hasFabricResourceLoader(
                Set.of("fabric_resource_loader_v0")::contains));
    }

    @Test
    void unrelatedModsDoNotRequireRecipes() {
        assertFalse(ShadowReloadListenersCompat.hasFabricResourceLoader(
                Set.of("neoforge", "hassium")::contains));
    }
}
