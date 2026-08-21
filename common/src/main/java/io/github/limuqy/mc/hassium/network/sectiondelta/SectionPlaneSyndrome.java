package io.github.limuqy.mc.hassium.network.sectiondelta;

import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

/**
 * 16×16×16 section 的平面综合征：对 {@code int[4096]}（{@code Block.getId} 口径，
 * 与 {@code writeSectionForHash} 在 1.20.1–1.21.8 的逐格顺序一致）做 16+16+16 条
 * xxHash32。脏轴笛卡尔积得到候选格（AABB），<b>不做平面 XOR</b>。
 */
public final class SectionPlaneSyndrome {

    public static final int CELLS = 4096;
    public static final int AXIS = 16;
    public static final int PLANE_COUNT = 48;
    public static final int PLANE_CELLS = 256;
    /** vanilla {@code ClientboundSectionBlocksUpdatePacket}：低 12 位为 section 内坐标。 */
    public static final int LOCAL_POS_MASK = 4095;

    private static final int HASH_SEED = 0;
    private static final XXHash32 XX32 = XXHashFactory.fastestInstance().hash32();
    private static final ThreadLocal<byte[]> PLANE_BYTES =
            ThreadLocal.withInitial(() -> new byte[PLANE_CELLS * Integer.BYTES]);
    private static final ThreadLocal<int[]> PLANE_INTS =
            ThreadLocal.withInitial(() -> new int[PLANE_CELLS]);

    private SectionPlaneSyndrome() {}

    /** {@code writeSectionForHash} 1.20.1–1.21.8 顺序：Y 外、Z 中、X 内。 */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** vanilla packed local pos：{@code x<<8 | z<<4 | y}。 */
    public static int packLocalPos(int x, int y, int z) {
        return (x << 8) | (z << 4) | y;
    }

    public static int localX(int packed) {
        return (packed >>> 8) & 15;
    }

    public static int localY(int packed) {
        return packed & 15;
    }

    public static int localZ(int packed) {
        return (packed >>> 4) & 15;
    }

    /**
     * 计算 48 条平面哈希：{@code [0..15]=X, [16..31]=Y, [32..47]=Z}。
     */
    public static int[] compute(int[] cells) {
        requireCells(cells);
        int[] planes = new int[PLANE_COUNT];
        byte[] bytes = PLANE_BYTES.get();
        int[] scratch = PLANE_INTS.get();

        for (int x = 0; x < AXIS; x++) {
            int n = 0;
            for (int y = 0; y < AXIS; y++) {
                for (int z = 0; z < AXIS; z++) {
                    scratch[n++] = cells[index(x, y, z)];
                }
            }
            planes[x] = hashInts(scratch, bytes);
        }
        for (int y = 0; y < AXIS; y++) {
            int n = 0;
            for (int z = 0; z < AXIS; z++) {
                for (int x = 0; x < AXIS; x++) {
                    scratch[n++] = cells[index(x, y, z)];
                }
            }
            planes[16 + y] = hashInts(scratch, bytes);
        }
        for (int z = 0; z < AXIS; z++) {
            int n = 0;
            for (int y = 0; y < AXIS; y++) {
                for (int x = 0; x < AXIS; x++) {
                    scratch[n++] = cells[index(x, y, z)];
                }
            }
            planes[32 + z] = hashInts(scratch, bytes);
        }
        return planes;
    }

    /**
     * 脏 X/Y/Z 轴的笛卡尔积（packed local pos）。任一轴全净 → 空数组（调用方按碰撞走 FULL）。
     */
    public static int[] candidates(int[] clientPlanes, int[] serverPlanes) {
        if (!validPlanes(clientPlanes) || !validPlanes(serverPlanes)) {
            return new int[0];
        }
        int[] dirtyX = dirtyAxis(clientPlanes, serverPlanes, 0);
        int[] dirtyY = dirtyAxis(clientPlanes, serverPlanes, 16);
        int[] dirtyZ = dirtyAxis(clientPlanes, serverPlanes, 32);
        if (dirtyX.length == 0 || dirtyY.length == 0 || dirtyZ.length == 0) {
            return new int[0];
        }
        int n = dirtyX.length * dirtyY.length * dirtyZ.length;
        int[] out = new int[n];
        int i = 0;
        for (int y : dirtyY) {
            for (int z : dirtyZ) {
                for (int x : dirtyX) {
                    out[i++] = packLocalPos(x, y, z);
                }
            }
        }
        return out;
    }

    /** VarInt(count) + vanilla {@code varLong(stateId<<12 | localPos)}。 */
    public static byte[] encodeBlockList(int[] packedLocalPos, int[] stateIds) {
        if (packedLocalPos == null || stateIds == null || packedLocalPos.length != stateIds.length) {
            throw new IllegalArgumentException("packedLocalPos/stateIds length mismatch");
        }
        int count = packedLocalPos.length;
        byte[] tmp = new byte[varIntSize(count) + count * 10];
        int w = writeVarInt(tmp, 0, count);
        for (int i = 0; i < count; i++) {
            long packed = ((long) stateIds[i] << 12) | (packedLocalPos[i] & LOCAL_POS_MASK);
            w = writeVarLong(tmp, w, packed);
        }
        byte[] out = new byte[w];
        System.arraycopy(tmp, 0, out, 0, w);
        return out;
    }

    public static int encodedBlockListSize(int[] packedLocalPos, int[] stateIds) {
        return encodeBlockList(packedLocalPos, stateIds).length;
    }

    /**
     * 读 BLOCKS 载荷开头的 VarInt 个数。损坏/空返回 {@code -1}。
     */
    public static int peekBlockListCount(byte[] data) {
        if (data == null || data.length == 0) {
            return -1;
        }
        int value = 0;
        int shift = 0;
        for (int i = 0; i < data.length && i < 5; i++) {
            int b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    private static int[] dirtyAxis(int[] client, int[] server, int offset) {
        int[] tmp = new int[AXIS];
        int n = 0;
        for (int i = 0; i < AXIS; i++) {
            if (client[offset + i] != server[offset + i]) {
                tmp[n++] = i;
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static int hashInts(int[] values, byte[] bytes) {
        int p = 0;
        for (int i = 0; i < PLANE_CELLS; i++) {
            int v = values[i];
            bytes[p++] = (byte) v;
            bytes[p++] = (byte) (v >>> 8);
            bytes[p++] = (byte) (v >>> 16);
            bytes[p++] = (byte) (v >>> 24);
        }
        return XX32.hash(bytes, 0, bytes.length, HASH_SEED);
    }

    static boolean validPlanes(int[] planes) {
        return planes != null && planes.length == PLANE_COUNT;
    }

    private static void requireCells(int[] cells) {
        if (cells == null || cells.length != CELLS) {
            throw new IllegalArgumentException("cells must be length " + CELLS);
        }
    }

    static int varIntSize(int value) {
        int n = 0;
        while ((value & -128) != 0) {
            n++;
            value >>>= 7;
        }
        return n + 1;
    }

    static int writeVarInt(byte[] out, int offset, int value) {
        while ((value & -128) != 0) {
            out[offset++] = (byte) (value & 127 | 128);
            value >>>= 7;
        }
        out[offset++] = (byte) value;
        return offset;
    }

    static int writeVarLong(byte[] out, int offset, long value) {
        while ((value & -128L) != 0L) {
            out[offset++] = (byte) ((int) value & 127 | 128);
            value >>>= 7;
        }
        out[offset++] = (byte) value;
        return offset;
    }
}
