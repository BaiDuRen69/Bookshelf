package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 农历标签的中文格式化与缓存：注入假 Provider，纯 JVM 不碰 android.icu stub。 */
public class LunarLabelTest {

    private static final class Fake implements LunarCalendar.Provider {
        private final int[] value;
        int calls;

        Fake(int... value) {
            this.value = value;
        }

        @Override
        public int[] lunar(int dayKey) {
            calls++;
            return value;
        }
    }

    @Test
    public void 初一显示月名而不是初一() {
        assertEquals("正月", new LunarCalendar(new Fake(1, 1, 0)).label(20260217));
        assertEquals("冬月", new LunarCalendar(new Fake(11, 1, 0)).label(20261201));
        assertEquals("腊月", new LunarCalendar(new Fake(12, 1, 0)).label(20270101));
    }

    @Test
    public void 闰月初一加闰字() {
        assertEquals("闰二月", new LunarCalendar(new Fake(2, 1, 1)).label(20230322));
    }

    @Test
    public void 其余日子显示农历日名() {
        assertEquals("十五", LunarCalendar.format(new int[]{1, 15, 0}));
        assertEquals("廿一", LunarCalendar.format(new int[]{8, 21, 0}));
        assertEquals("三十", LunarCalendar.format(new int[]{12, 30, 0}));
        assertEquals("初十", LunarCalendar.format(new int[]{5, 10, 0}));
    }

    @Test
    public void 非法换算一律退成空串() {
        assertEquals("", LunarCalendar.format(null));
        assertEquals("", LunarCalendar.format(new int[]{0, 1, 0}));
        assertEquals("", LunarCalendar.format(new int[]{13, 1, 0}));
        assertEquals("", LunarCalendar.format(new int[]{1, 31, 0}));
        assertEquals("", LunarCalendar.format(new int[]{1}));
        assertEquals("", new LunarCalendar(null).label(20261105));
        assertEquals("", new LunarCalendar(new Fake(1, 1, 0)).label(0));
    }

    @Test
    public void 同一天只换算一次清缓存后重算() {
        Fake fake = new Fake(3, 8, 0);
        LunarCalendar lunar = new LunarCalendar(fake);
        assertEquals("初八", lunar.label(20260401));
        assertEquals("初八", lunar.label(20260401));
        assertEquals(1, fake.calls);
        lunar.clearCache();
        assertEquals("初八", lunar.label(20260401));
        assertEquals(2, fake.calls);
    }
}
