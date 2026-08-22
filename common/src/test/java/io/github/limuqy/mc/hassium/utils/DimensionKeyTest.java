package io.github.limuqy.mc.hassium.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionKeyTest {

    private static void assertRoundTrip(String dimension, int chunkX, int chunkZ) {
        long key = DimensionKey.key(dimension, chunkX, chunkZ);
        assertEquals(dimension, DimensionKey.dimensionOf(key), "dimension round-trip");
        assertEquals(chunkX, DimensionKey.chunkXOf(key), "chunkX round-trip");
        assertEquals(chunkZ, DimensionKey.chunkZOf(key), "chunkZ round-trip");
    }

    @Test
    void roundTrip_regularNegativeAndExtremeCoordinates() {
        String[] dims = {DimensionKey.OVERWORLD, DimensionKey.NETHER, DimensionKey.END,
                "minecraft:custom_extremes"};
        for (String dim : dims) {
            assertRoundTrip(dim, 0, 0);
            assertRoundTrip(dim, 1, -1);
            assertRoundTrip(dim, -1, 1);
            assertRoundTrip(dim, 1875000, -1875000);   // ±30M 方块 / 16
            assertRoundTrip(dim, -1875000, 1875000);
            assertRoundTrip(dim, (1 << 25) - 1, -(1 << 25)); // 26 位补码边界
            assertRoundTrip(dim, -(1 << 25), (1 << 25) - 1);
        }
    }

    @Test
    void sameCoordinateAcrossDimensions_neverCollide() {
        int[][] coords = {{0, 0}, {-1, -1}, {1875000, -1875000}, {(1 << 25) - 1, -(1 << 25)}};
        String[] dims = {DimensionKey.OVERWORLD, DimensionKey.NETHER, DimensionKey.END,
                "minecraft:dim_a", "minecraft:dim_b", "minecraft:dim_c"};
        for (int[] c : coords) {
            for (int i = 0; i < dims.length; i++) {
                for (int j = i + 1; j < dims.length; j++) {
                    final String di = dims[i];
                    final String dj = dims[j];
                    assertNotEquals(DimensionKey.key(di, c[0], c[1]), DimensionKey.key(dj, c[0], c[1]),
                            () -> di + " vs " + dj + " @ (" + c[0] + "," + c[1] + ")");
                }
            }
        }
    }

    @Test
    void differentCoordinatesSameDimension_neverCollide() {
        String dim = DimensionKey.NETHER;
        long a = DimensionKey.key(dim, 0, 0);
        long b = DimensionKey.key(dim, 0, 1);
        long c = DimensionKey.key(dim, 1, 0);
        long d = DimensionKey.key(dim, -1, 0);
        long e = DimensionKey.key(dim, 0, -1);
        long f = DimensionKey.key(dim, Integer.MAX_VALUE >> 6, Integer.MIN_VALUE >> 6);
        assertEquals(6, distinctCount(a, b, c, d, e, f));
    }

    private static int distinctCount(long... keys) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (long k : keys) {
            set.add(k);
        }
        return set.size();
    }

    @Test
    void bareChunkPosKeyOverload_matchesVanillaLayoutAndCoordinateForm() {
        // vanilla ChunkPos.asLong 布局：x 低 32 位、z 高 32 位（有符号）
        long posKey = ((long) (-7) << 32) | (5 & 0xFFFFFFFFL); // x=5, z=-7
        long key = DimensionKey.key(DimensionKey.END, posKey);
        assertEquals(key, DimensionKey.key(DimensionKey.END, 5, -7),
                "bare-key overload must agree with coordinate form");
        assertEquals(5, DimensionKey.chunkXOf(key));
        assertEquals(-7, DimensionKey.chunkZOf(key));
        assertEquals(DimensionKey.END, DimensionKey.dimensionOf(key));

        // 负坐标裸键同样一致
        long negKey = DimensionKey.key(DimensionKey.OVERWORLD, -12345678, 87654321);
        assertEquals(negKey, DimensionKey.key(DimensionKey.OVERWORLD,
                ((long) 87654321 << 32) | (-12345678 & 0xFFFFFFFFL)));
    }

    @Test
    void unregisteredDimension_autoRegistersIdempotently() {
        String custom = "minecraft:dimkey_test_dim";
        assertFalse(DimensionKey.isCacheableDimension(custom));

        int id1 = DimensionKey.register(custom);
        int id2 = DimensionKey.register(custom);
        assertEquals(id1, id2, "register must be idempotent");

        long key = DimensionKey.key(custom, 123, -456);
        assertEquals(custom, DimensionKey.dimensionOf(key));
        assertEquals(123, DimensionKey.chunkXOf(key));
        assertEquals(-456, DimensionKey.chunkZOf(key));

        // 自动注册路径与显式注册得到同一稳定键
        assertEquals(key, DimensionKey.key(custom, 123, -456));
        assertEquals(id1, DimensionKey.register(custom));
    }

    @Test
    void isCacheableDimension_whitelistOnly() {
        assertTrue(DimensionKey.isCacheableDimension(DimensionKey.OVERWORLD));
        assertTrue(DimensionKey.isCacheableDimension(DimensionKey.NETHER));
        assertTrue(DimensionKey.isCacheableDimension(DimensionKey.END));
        assertFalse(DimensionKey.isCacheableDimension("minecraft:custom"));
        assertFalse(DimensionKey.isCacheableDimension(null));
        assertFalse(DimensionKey.isCacheableDimension(""));
    }

    @Test
    void mainDimensionConstants_matchVanillaIds() {
        assertEquals("minecraft:overworld", DimensionKey.OVERWORLD);
        assertEquals("minecraft:the_nether", DimensionKey.NETHER);
        assertEquals("minecraft:the_end", DimensionKey.END);
    }

    @Test
    void unknownDimensionId_decodesToNull() {
        // 未分配的高位 id → 防御性 null，不抛异常
        assertNull(DimensionKey.dimensionOf(-1L));
    }
}
