package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.client.multiplayer.ClientChunkCache;

/** ClientChunkCache 半径抬高（OVD 客户端边界；版本差异收口）。 */
final class ClientChunkCacheRadius {

    private ClientChunkCacheRadius() {}

    static void apply(ClientChunkCache cache, int radius) {
        if (cache == null || radius <= 0) {
            return;
        }
        cache.updateViewRadius(radius);
    }
}
