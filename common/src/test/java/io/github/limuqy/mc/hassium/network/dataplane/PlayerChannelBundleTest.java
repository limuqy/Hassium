package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerChannelBundleTest {

    @Test @DisplayName("添加通道后 getDataChannels 包含该通道")
    void addChannel() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        assertEquals(0, b.getDataChannels().size());
        b.addChannel(new PlayerChannel(null, 50) {
            @Override public boolean isActive() { return true; }
            @Override public boolean isWritable() { return true; }
        });
        assertEquals(1, b.getDataChannels().size());
    }

    @Test @DisplayName("移除通道后列表为空")
    void removeChannel() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        PlayerChannel ch = new PlayerChannel(null, 50) {
            @Override public boolean isActive() { return true; }
            @Override public boolean isWritable() { return true; }
        };
        b.addChannel(ch);
        b.removeChannel(ch);
        assertEquals(0, b.getDataChannels().size());
    }

    @Test @DisplayName("degraded 从 false 翻转为 true")
    void degradeFlip() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        assertFalse(b.degraded);
        b.degraded = true;
        assertTrue(b.degraded);
    }

    @Test @DisplayName("consecutiveDrops 归零")
    void resetDrops() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        b.consecutiveDrops = 3;
        b.resetDrops();
        assertEquals(0, b.consecutiveDrops);
    }
}
