package io.github.limuqy.mc.hassium.network.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 反压账本的核心契约：**观察者之间互不影响**。这是「远端热点不再连坐近处实体」的根据——
 * 早期实现是一条全服控制器 + 最忙连接的实测值，一个玩家身边的热点会把所有人的视锥一起降帧。
 */
class EntityPressureBookTest {

    private static final int BUDGET = 64;

    @Test
    @DisplayName("每观察者独立：一个观察者超预算升档，其他观察者保持初始档")
    void observersAreIndependent() {
        EntityPressureBook book = new EntityPressureBook(4);
        book.sample("hot", BUDGET * 4, BUDGET);
        book.sample("quiet", 3, BUDGET);

        assertEquals(2, book.pressureOf("hot"), "超载 4× ⇒ 升一挡");
        assertEquals(1, book.pressureOf("quiet"), "安静观察者不受影响");
        assertEquals(1, book.pressureOf("unknown"), "未知观察者（未观测过/原版连接）按 1");
        assertEquals(1, book.pressureOf(null));
    }

    @Test
    @DisplayName("升档可累积到上限，且同一观察者多次超载不会超过 maxPressure")
    void growthIsCappedPerObserver() {
        EntityPressureBook book = new EntityPressureBook(3);
        for (int i = 0; i < 10; i++) {
            book.sample("p", BUDGET * 4, BUDGET);
        }
        assertEquals(3, book.pressureOf("p"));
    }

    @Test
    @DisplayName("回到低载后按回差退档，最终回 1（不会一次安静帧就退）")
    void recoversAfterSustainedUnderload() {
        EntityPressureBook book = new EntityPressureBook(4);
        book.sample("p", BUDGET * 4, BUDGET);
        assertEquals(2, book.pressureOf("p"), "一次超载升一挡");
        book.sample("p", BUDGET * 4, BUDGET);
        assertEquals(4, book.pressureOf("p"), "持续超载继续升挡（×2 递增）");

        book.sample("p", 1, BUDGET);
        assertEquals(4, book.pressureOf("p"), "单帧低载不退档");
        // 退档需要 EWMA 先落回低载区、再连续 20 次低载采样，所以给足窗口（200 帧 ≫ 3 次退档所需）
        for (int i = 0; i < 200; i++) {
            book.sample("p", 1, BUDGET);
        }
        assertEquals(1, book.pressureOf("p"), "持续低载最终回到初始档");
    }

    @Test
    @DisplayName("retainOnly 丢弃断连观察者，避免随会话累积；在册观察者状态保留")
    void retainOnlyDropsDisconnectedObservers() {
        EntityPressureBook book = new EntityPressureBook(4);
        book.sample("a", BUDGET * 4, BUDGET);
        book.sample("b", BUDGET * 4, BUDGET);
        assertEquals(2, book.size());

        book.retainOnly(List.of("a"));
        assertEquals(1, book.size());
        assertEquals(2, book.pressureOf("a"), "在册观察者的档位不受清理影响");
        assertEquals(1, book.pressureOf("b"));
    }

    @Test
    @DisplayName("预算 <= 0（不限配额）时该观察者维持初始档")
    void zeroBudgetKeepsInitialPressure() {
        EntityPressureBook book = new EntityPressureBook(4);
        book.sample("p", BUDGET * 8, 0);
        assertEquals(1, book.pressureOf("p"));
    }
}
