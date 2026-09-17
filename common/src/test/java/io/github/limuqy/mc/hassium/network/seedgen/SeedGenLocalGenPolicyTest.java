package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地生成：有缓存禁止 worldgen；新柱必须落盘；权威窗先比对再算光。
 */
class SeedGenLocalGenPolicyTest {

    @Test
    @DisplayName("首物化且无盘 hash 才是本会话 vanilla worldgen")
    void freshLocalWorldgenIsFirstMaterializeWithoutDisk() {
        assertTrue(SeedGenExecutor.isFreshLocalWorldgen(false, false));
        assertFalse(SeedGenExecutor.isFreshLocalWorldgen(true, false),
                "注入表已有柱：网络 FULL / 读盘短路，不是本地生成");
        assertFalse(SeedGenExecutor.isFreshLocalWorldgen(false, true),
                "磁盘命中禁止再 worldgen");
        assertFalse(SeedGenExecutor.isFreshLocalWorldgen(true, true));
    }

    @Test
    @DisplayName("无盘柱必须 dirty 落盘；磁盘命中不得重写")
    void persistAsDirtyWhenNoDiskBaseline() {
        assertTrue(SeedGenExecutor.persistAsDirty(false),
                "本地生成没落盘就是缓存基线丢失");
        assertFalse(SeedGenExecutor.persistAsDirty(true));
    }

    @Test
    @DisplayName("权威窗内本地生成延后算光；OVD / 缓存命中不延后")
    void deferLightOnlyForAuthorityWindowLocalGen() {
        assertTrue(SeedGenExecutor.deferLightUntilAuthority(true, true),
                "先 compare，UNCHANGED/DELTA/FULL 后再算光，避免权威微变二次算光");
        assertFalse(SeedGenExecutor.deferLightUntilAuthority(true, false),
                "OVD 无权威比对，persist 后直接 publishOvd");
        assertFalse(SeedGenExecutor.deferLightUntilAuthority(false, true),
                "磁盘/注入基线复用后 compare，不是本地生成");
        assertFalse(SeedGenExecutor.deferLightUntilAuthority(false, false));
    }
}
