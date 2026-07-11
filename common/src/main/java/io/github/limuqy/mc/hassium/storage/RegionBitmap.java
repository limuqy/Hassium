package io.github.limuqy.mc.hassium.storage;

import java.util.BitSet;

/**
 * Sector 分配位图，管理 region 文件中的 sector 使用情况
 */
public class RegionBitmap {
    private final BitSet used = new BitSet();

    /**
     * 标记指定范围的 sector 为已使用
     */
    public void force(int sectorOffset, int sectorCount) {
        used.set(sectorOffset, sectorOffset + sectorCount);
    }

    /**
     * 释放指定范围的 sector
     */
    public void free(int sectorOffset, int sectorCount) {
        used.clear(sectorOffset, sectorOffset + sectorCount);
    }

    /**
     * 分配指定数量的连续空闲 sector，返回起始 sector 偏移
     */
    public int allocate(int sectorCount) {
        int searchFrom = 0;
        while (true) {
            int freeStart = used.nextClearBit(searchFrom);
            int nextUsed = used.nextSetBit(freeStart);
            if (nextUsed == -1 || nextUsed - freeStart >= sectorCount) {
                force(freeStart, sectorCount);
                return freeStart;
            }
            searchFrom = nextUsed;
        }
    }
}
