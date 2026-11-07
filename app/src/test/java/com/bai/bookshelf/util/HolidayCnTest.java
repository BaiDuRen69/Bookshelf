package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** 离线兜底节日名：农历由参数注入，不依赖 Android ICU；周末与元宵都不算。 */
public class HolidayCnTest {

    @Test
    public void 周末不算节日() {
        assertFalse(HolidayCn.isHoliday(20261107, null)); // 周六
        assertFalse(HolidayCn.isHoliday(20261108, null)); // 周日
        assertFalse(HolidayCn.isHoliday(20261109, null)); // 周一
    }

    @Test
    public void 公历固定日返回节日名() {
        assertEquals("元旦", HolidayCn.nameOf(20260101, null));
        assertEquals("劳动节", HolidayCn.nameOf(20260501, null));
        assertEquals("劳动节", HolidayCn.nameOf(20260503, null));
        assertFalse(HolidayCn.isHoliday(20260504, null));
        assertEquals("国庆节", HolidayCn.nameOf(20261001, null));
        assertEquals("国庆节", HolidayCn.nameOf(20261003, null));
        assertFalse(HolidayCn.isHoliday(20261005, null));
    }

    @Test
    public void 春节端午中秋除夕() {
        assertEquals("春节", HolidayCn.nameOf(20260217, new int[]{1, 1, 0}));
        assertEquals("春节", HolidayCn.nameOf(20260219, new int[]{1, 3, 0}));
        assertNull(HolidayCn.nameOf(20260220, new int[]{1, 4, 0}));
        assertEquals("端午节", HolidayCn.nameOf(20260619, new int[]{5, 5, 0}));
        assertEquals("中秋节", HolidayCn.nameOf(20260925, new int[]{8, 15, 0}));
        assertEquals("除夕", HolidayCn.nameOf(20260216, new int[]{12, 29, 0}));
        assertEquals("除夕", HolidayCn.nameOf(20260216, new int[]{12, 30, 0}));
        assertNull(HolidayCn.nameOf(20260212, new int[]{12, 28, 0}));
    }

    @Test
    public void 元宵不在法定口径内() {
        assertNull(HolidayCn.nameOf(20260303, new int[]{1, 15, 0}));
    }

    @Test
    public void 闰月不算节日() {
        assertNull(HolidayCn.nameOf(20260217, new int[]{1, 1, 1}));
        assertNull(HolidayCn.nameOf(20260619, new int[]{5, 5, 1}));
    }

    @Test
    public void 非法输入不崩() {
        assertNull(HolidayCn.nameOf(0, null));
        assertFalse(HolidayCn.isHoliday(20261109, new int[0]));
        assertFalse(HolidayCn.isHoliday(20261109, new int[]{13, 1, 0}));
        assertFalse(HolidayCn.isHoliday(20261109, new int[]{1, 31, 0}));
    }

    @Test
    public void 年度兜底表只含七个法定日() {
        Map<Integer, String> names = HolidayCn.yearNames(2026, null);
        assertEquals(7, names.size()); // 元旦 1 + 劳动 3 + 国庆 3，无农历输入
        assertEquals("元旦", names.get(20260101));
        assertEquals("劳动节", names.get(20260502));
        assertEquals("国庆节", names.get(20261003));
        assertFalse(names.containsKey(20261107)); // 周六不进来
    }
}
