package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.LunarCalendar;
import com.bai.bookshelf.util.LunarCalendarIcu;

import org.junit.Test;
import org.junit.runner.RunWith;

/** 平台 ICU 农历换算的真机验收：春节、闰月、腊月三十、连续性与范围外兜底。 */
@RunWith(AndroidJUnit4.class)
public class LunarCalendarInstrumentedTest {

    private final LunarCalendarIcu provider = new LunarCalendarIcu();
    private final LunarCalendar calendar = new LunarCalendar(provider);

    @Test
    public void 春节换算落在正月初一附近() {
        int[] v = provider.lunar(20260217); // 2026 年春节
        assertNotNull(v);
        assertEquals("春节应落在正月", 1, v[0]);
        assertTrue("春节应是初一（时区容错到初二）", v[1] <= 2);
        assertTrue("农历月合法", v[0] >= 1 && v[0] <= 12);
        assertTrue("农历日合法", v[1] >= 1 && v[1] <= 30);
        assertFalse(calendar.label(20260217).isEmpty());
    }

    @Test
    public void 连续两天农历日号递增或跨月归一() {
        int a = 20260301;
        int b = DateKeys.shiftDayKey(a, 1);
        int[] x = provider.lunar(a);
        int[] y = provider.lunar(b);
        assertNotNull(x);
        assertNotNull(y);
        assertTrue("次日的农历日 +1 或回到初一",
                y[1] == x[1] + 1 || y[1] == 1);
    }

    @Test
    public void 一年之内能找到闰月与腊月三十() {
        boolean leapFound = false;
        boolean lastDayFound = false;
        for (int m = 1; m <= 12 && (!leapFound || !lastDayFound); m++) {
            int days = DateKeys.daysInMonth(2025, m);
            for (int d = 1; d <= days; d++) {
                int[] v = provider.lunar(2025 * 10000 + m * 100 + d);
                if (v == null) {
                    continue;
                }
                if (v.length > 2 && v[2] == 1) {
                    leapFound = true;
                }
                if (v[0] == 12 && v[1] == 30) {
                    lastDayFound = true;
                }
            }
        }
        assertTrue("平台 ICU 应能提供闰月标记", leapFound);
        assertTrue("应能找到腊月三十", lastDayFound);
    }

    @Test
    public void 范围外日期退成不显示农历() {
        assertNull(provider.lunar(18000101));
        assertNull(provider.lunar(0));
        assertEquals("", calendar.label(22000101));
        assertEquals("", new LunarCalendar(null).label(20261107));
    }
}
