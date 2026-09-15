package io.github.limuqy.mc.hassium.network.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityUpdateTieringTest {

    @Test
    @DisplayName("合法四档间隔原样保留（已非降序、未越界）")
    void parseKeepsLegalIntervals() {
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("3,6,10,20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{1, 1, 1, 1},
                EntityUpdateTiering.parseIntervals("1,1,1,1", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{120, 120, 120, 120},
                EntityUpdateTiering.parseIntervals("120,120,120,120", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals(" 3 , 6 ,10, 20 ", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "容忍空白");
    }

    @Test
    @DisplayName("远档比近档勤时按前档上推：\"10,6,10,20\" → {10,10,10,20}")
    void parseForcesNonDescendingOrder() {
        assertArrayEquals(new int[]{10, 10, 10, 20},
                EntityUpdateTiering.parseIntervals("10,6,10,20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{20, 20, 20, 20},
                EntityUpdateTiering.parseIntervals("20,6,10,20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
    }

    @Test
    @DisplayName("非法项回落默认 {3,6,10,20}，越界值 clamp 到 [1,120]，元素个数不对整表回落")
    void parseFallsBackAndClamps() {
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("0,6,10,20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "0 回落默认的 3");
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("-1,-6,-10,-20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "负数为非法项");
        assertArrayEquals(new int[]{3, 6, 10, 120},
                EntityUpdateTiering.parseIntervals("3,6,10,999", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "999 截到 120");
        assertArrayEquals(new int[]{120, 120, 120, 120},
                EntityUpdateTiering.parseIntervals("999,0,10,20", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "回落与截断后仍须非降序");
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("3,6,10", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "少一档整表回落");
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("3,6,10,20,30", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS), "多一档整表回落");
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals(null, EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("   ", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
    }

    @Test
    @DisplayName("物品流表用物品默认 {2,4,8,16}，不串到实体默认表")
    void parseFallsBackToItemDefaults() {
        assertArrayEquals(new int[]{2, 4, 8, 16},
                EntityUpdateTiering.parseIntervals("x,4,8,16", EntityUpdateTiering.DEFAULT_ITEM_INTERVALS));
        assertArrayEquals(new int[]{2, 4, 8, 16},
                EntityUpdateTiering.parseIntervals("-1,-4,-8,-16", EntityUpdateTiering.DEFAULT_ITEM_INTERVALS));
        assertArrayEquals(new int[]{2, 4, 8, 16},
                EntityUpdateTiering.parseIntervals(null, EntityUpdateTiering.DEFAULT_ITEM_INTERVALS));
        // 同一份坏串在两张表上得到各自默认 20 刻 / 16 刻，互不影响
        assertArrayEquals(new int[]{3, 6, 10, 20},
                EntityUpdateTiering.parseIntervals("1", EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS));
        assertArrayEquals(new int[]{2, 4, 8, 16},
                EntityUpdateTiering.parseIntervals("1", EntityUpdateTiering.DEFAULT_ITEM_INTERVALS));
    }

    @Test
    @DisplayName("原版间隔下限：物品流取下限 1（其 20 刻是空闲节拍，不能压平档位表），其它实体保持原版值")
    void intervalFloorDropsVanillaIntervalForItemFlow() {
        // EntityType.ITEM / EXPERIENCE_ORB 的 updateInterval = 20；若沿用 max，档位 2/4/8/16 全被压成 20
        assertEquals(1, EntityUpdateTiering.intervalFloor(20, true));
        assertEquals(20, EntityUpdateTiering.intervalFloor(20, false));
        assertEquals(3, EntityUpdateTiering.intervalFloor(3, false));
        // 下限 1 时档位表原样生效（这是物品流近档不再瞬移的前提）
        assertEquals(2, EntityUpdateTiering.effectiveInterval(
                EntityUpdateTiering.intervalFloor(20, true), 2, 1));
        assertEquals(20, EntityUpdateTiering.effectiveInterval(
                EntityUpdateTiering.intervalFloor(20, false), 2, 1));
    }

    @Test
    @DisplayName("常量与无观察者占位：4 档、上限 40、比例表与档数一致")
    void constantsAndNoObserverPlaceholder() {
        assertEquals(4, EntityUpdateTiering.TIER_COUNT);
        assertEquals(40, EntityUpdateTiering.MAX_INTERVAL);
        assertArrayEquals(new double[]{0.25, 0.5, 0.75, 1.0}, EntityUpdateTiering.TIER_FRACTIONS);
        assertEquals(EntityUpdateTiering.TIER_COUNT, EntityUpdateTiering.TIER_FRACTIONS.length);
        assertEquals(EntityUpdateTiering.MAX_INTERVAL, EntityUpdateTiering.noObserverInterval());
    }

    @Test
    @DisplayName("距离比例分档：档位边界取等号，<=0 归最近档，>1 与非有限值返回 -1")
    void tierOfMapsRatioToTier() {
        assertEquals(0, EntityUpdateTiering.tierOf(0.0));
        assertEquals(0, EntityUpdateTiering.tierOf(0.1));
        assertEquals(0, EntityUpdateTiering.tierOf(0.25));
        assertEquals(1, EntityUpdateTiering.tierOf(0.25001));
        assertEquals(1, EntityUpdateTiering.tierOf(0.5));
        assertEquals(2, EntityUpdateTiering.tierOf(0.51));
        assertEquals(3, EntityUpdateTiering.tierOf(0.9));
        assertEquals(3, EntityUpdateTiering.tierOf(1.0));
        assertEquals(-1, EntityUpdateTiering.tierOf(1.00001));
        assertEquals(-1, EntityUpdateTiering.tierOf(Double.NaN));
        assertEquals(-1, EntityUpdateTiering.tierOf(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("每档热点倍率：仅当实体所在 chunk 活跃实体数 ≥ 该档阈值时生效，且按实体自己那一档取值")
    void densityFactorUsesPerTierTable() {
        int[] counts = {50, 40, 30, 20};
        double[] factors = {1.0, 1.5, 2.0, 3.0};
        // 阈值为「≥」：49 个实体不触发，50 个触发
        assertEquals(1.0, EntityUpdateTiering.densityFactor(49, 0, counts, factors));
        assertEquals(1.0, EntityUpdateTiering.densityFactor(50, 0, counts, factors), "近档倍率 1.0 = 该档不放大");
        // 同一个实体数，不同档用到不同阈值/倍率
        assertEquals(1.0, EntityUpdateTiering.densityFactor(35, 1, counts, factors), "中档阈值 40 未达");
        assertEquals(1.5, EntityUpdateTiering.densityFactor(40, 1, counts, factors));
        assertEquals(2.0, EntityUpdateTiering.densityFactor(30, 2, counts, factors));
        assertEquals(3.0, EntityUpdateTiering.densityFactor(200, 3, counts, factors), "远超阈值仍是单步倍率");
        // 边界与非法输入
        assertEquals(1.0, EntityUpdateTiering.densityFactor(0, 3, counts, factors));
        assertEquals(1.0, EntityUpdateTiering.densityFactor(-1, 3, counts, factors));
        assertEquals(1.0, EntityUpdateTiering.densityFactor(99, -1, counts, factors), "无观察者");
        assertEquals(1.0, EntityUpdateTiering.densityFactor(99, 4, counts, factors), "档位越界");
        assertEquals(1.0, EntityUpdateTiering.densityFactor(99, 0, counts, new double[]{0.5, 1, 1, 1}), "倍率 < 1 夹到 1（只降速）");
    }

    @Test
    @DisplayName("热点阈值解析：单个元素写坏只回落该元素，元素个数不对则整表回落默认")
    void parseDensityCountsFallsBackPerElement() {
        assertArrayEquals(new int[]{50, 40, 30, 20}, EntityUpdateTiering.parseDensityCounts("50,40,30,20"));
        assertArrayEquals(new int[]{50, 40, 30, 20}, EntityUpdateTiering.parseDensityCounts(" 50 , 40,30,20 "), "容忍空白");
        assertArrayEquals(new int[]{32, 40, 30, 20}, EntityUpdateTiering.parseDensityCounts("x,40,30,20"), "首元素坏 → 回落默认 32");
        assertArrayEquals(new int[]{32, 64, 96, 128}, EntityUpdateTiering.parseDensityCounts("50,40,30"), "少一档 → 整表默认");
        assertArrayEquals(new int[]{32, 64, 96, 128}, EntityUpdateTiering.parseDensityCounts("50,40,30,20,10"), "多一档 → 整表默认");
        assertArrayEquals(new int[]{32, 64, 96, 128}, EntityUpdateTiering.parseDensityCounts(null));
        assertArrayEquals(new int[]{32, 64, 96, 128}, EntityUpdateTiering.parseDensityCounts("   "));
        assertArrayEquals(new int[]{1, 50, 50, 50}, EntityUpdateTiering.parseDensityCounts("0,50,50,50"), "阈值下限 1");
    }

    @Test
    @DisplayName("热点倍率解析：支持小数、< 1 夹到 1、NaN/坏值回落该元素默认")
    void parseDensityFactorsSupportsDecimals() {
        assertArrayEquals(new double[]{1.5, 2.0, 2.5, 3.0}, EntityUpdateTiering.parseDensityFactors("1.5,2,2.5,3"));
        assertArrayEquals(new double[]{1.0, 1.0, 1.0, 1.0}, EntityUpdateTiering.parseDensityFactors("0,0.5,-2,1"), "小于 1 一律夹到 1");
        assertArrayEquals(new double[]{1.0, 2.0, 2.0, 3.0}, EntityUpdateTiering.parseDensityFactors("NaN,2,2,3"), "NaN 回落默认 1.0");
        assertArrayEquals(new double[]{1.0, 1.5, 2.0, 3.0}, EntityUpdateTiering.parseDensityFactors("1.5,2.0"), "元素个数不对 → 整表默认");
        assertArrayEquals(new double[]{10.0, 10.0, 10.0, 10.0}, EntityUpdateTiering.parseDensityFactors("10,10,10,10"));
        assertArrayEquals(new double[]{1.0, 1.5, 2.0, 3.0}, EntityUpdateTiering.parseDensityFactors(null));
    }

    @Test
    @DisplayName("降帧倍率含小数时四舍五入到整刻（2 刻 × 1.5 = 3 刻），仍收口 [1,40]")
    void effectiveIntervalRoundsFractionalFactor() {
        assertEquals(3, EntityUpdateTiering.effectiveInterval(1, 2, 1.5));
        assertEquals(6, EntityUpdateTiering.effectiveInterval(1, 3, 2.0));
        assertEquals(40, EntityUpdateTiering.effectiveInterval(1, 16, 36.0), "收口到 40");
        assertEquals(2, EntityUpdateTiering.effectiveInterval(1, 2, 0.4), "倍率 < 1 视作 1 ⇒ 回落到档位间隔");
        assertEquals(2, EntityUpdateTiering.effectiveInterval(1, 2, Double.NaN), "非有限倍率视作 1");
    }

    @Test
    @DisplayName("原版间隔超过 40 时原样透传（ItemFrame 的 MAX_VALUE 不得被截断）")
    void effectiveIntervalPassesThroughHugeVanilla() {
        assertEquals(Integer.MAX_VALUE, EntityUpdateTiering.effectiveInterval(Integer.MAX_VALUE, 3, 4.0));
        assertEquals(41, EntityUpdateTiering.effectiveInterval(41, 3, 4.0));
        assertEquals(40, EntityUpdateTiering.effectiveInterval(40, 3, 4.0), "40 不算越界，仍按降帧合成");
    }

    @Test
    @DisplayName("取原版与本档较大者再乘因子，结果收口到 [1,40]")
    void effectiveIntervalTakesMaxOfVanillaAndTier() {
        assertEquals(20, EntityUpdateTiering.effectiveInterval(20, 3, 1), "原版更稀时保原版");
        assertEquals(20, EntityUpdateTiering.effectiveInterval(3, 20, 1), "本档更稀时取本档");
        assertEquals(12, EntityUpdateTiering.effectiveInterval(3, 3, 4));
        assertEquals(40, EntityUpdateTiering.effectiveInterval(20, 20, 4), "80 被截到 40");
        assertEquals(3, EntityUpdateTiering.effectiveInterval(0, 3, 0), "factor < 1 视作 1");
        assertEquals(3, EntityUpdateTiering.effectiveInterval(3, 0, 1), "tierInterval < 1 视作 1");
    }

    @Test
    @DisplayName("相位偏移落在 [0, interval)，interval<=1 恒为 0")
    void phaseOffsetStaysInRange() {
        assertEquals(0, EntityUpdateTiering.phaseOffset(12345, 1), "每 tick 都发时无相位");
        assertEquals(0, EntityUpdateTiering.phaseOffset(12345, 0));
        assertEquals(0, EntityUpdateTiering.phaseOffset(12345, -3));
        for (int interval = 2; interval <= 20; interval++) {
            for (int hash = -1000; hash <= 1000; hash += 37) {
                int phase = EntityUpdateTiering.phaseOffset(hash, interval);
                assertTrue(phase >= 0 && phase < interval, "phase=" + phase + " interval=" + interval);
            }
        }
    }

    @Test
    @DisplayName("错峰总量不变：interval 内每实体恰发一次，不同 hash 落在不同刻")
    void phaseOffsetSpreadsUniformlyWithoutChangingVolume() {
        final int interval = 3;
        // 模拟连续 6 tick 的门条件 (tick + phase) % interval == 0
        int[] hitsPerEntity = new int[64];
        int[] hitsPerTick = new int[interval];
        for (int hash = 0; hash < 64; hash++) {
            int phase = EntityUpdateTiering.phaseOffset(hash, interval);
            for (int tick = 0; tick < interval; tick++) {
                if ((tick + phase) % interval == 0) {
                    hitsPerEntity[hash]++;
                    hitsPerTick[tick]++;
                }
            }
        }
        for (int hits : hitsPerEntity) {
            assertEquals(1, hits, "每个实体在 interval 窗口内仍只发一次");
        }
        // 均匀分布：64 个 hash 模 3 后各刻约 21/21/22，总量 64 不变
        int total = hitsPerTick[0] + hitsPerTick[1] + hitsPerTick[2];
        assertEquals(64, total, "3 tick 总量不变");
        for (int hits : hitsPerTick) {
            assertTrue(hits >= 20 && hits <= 22, "尖峰被摊平: " + hitsPerTick[0] + "," + hitsPerTick[1] + "," + hitsPerTick[2]);
        }
    }

    @Test
    @DisplayName("同一 hash 相位稳定（不随调用抖动）")
    void phaseOffsetIsStable() {
        int hash = 0xC0FFEE;
        for (int i = 0; i < 10; i++) {
            assertEquals(EntityUpdateTiering.phaseOffset(hash, 7), EntityUpdateTiering.phaseOffset(hash, 7));
        }
    }
}
