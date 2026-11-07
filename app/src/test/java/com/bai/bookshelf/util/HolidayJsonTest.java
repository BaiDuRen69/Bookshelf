package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** 节假日 API 解析与年度表来源（纯 JVM）。 */
public class HolidayJsonTest {

    private static final String BODY = "{"
            + "\"code\":0,"
            + "\"holiday\":{"
            + "\"01-01\":{\"holiday\":true,\"name\":\"元旦\"},"
            + "\"02-17\":{\"holiday\":true,\"name\":\"春节\"},"
            + "\"02-28\":{\"holiday\":false,\"name\":\"调休\"},"
            + "\"05-01\":{\"holiday\":true,\"name\":\"劳动节、五四青年节\"},"
            + "\"10-01\":{\"holiday\":true,\"name\":\"中华人民共和国国庆节\"},"
            + "\"13-01\":{\"holiday\":true,\"name\":\"坏日期\"}"
            + "}}";

    @Test
    public void 解析放假日并忽略调休() {
        Map<Integer, String> map = HolidayJson.parseYear(BODY, 2026);
        assertEquals("元旦", map.get(20260101));
        assertEquals("春节", map.get(20260217));
        assertFalse(map.containsKey(20260228)); // holiday=false 是上班调休
        assertFalse(map.containsKey(20261301)); // 非法月
    }

    @Test
    public void 多节日名取第一个并截到三字() {
        Map<Integer, String> map = HolidayJson.parseYear(BODY, 2026);
        assertEquals("劳动节", map.get(20260501));
        assertEquals("中华人", map.get(20261001));
    }

    @Test
    public void 周末不出现在表中() {
        Map<Integer, String> map = HolidayJson.parseYear(BODY, 2026);
        assertFalse(map.containsKey(20261107)); // 周六
        assertFalse(map.containsKey(20261108)); // 周日
    }

    @Test
    public void 非零code与坏JSON返回空表() {
        assertTrue(HolidayJson.parseYear("{\"code\":1,\"holiday\":{}}", 2026).isEmpty());
        assertTrue(HolidayJson.parseYear("not json", 2026).isEmpty());
        assertTrue(HolidayJson.parseYear("{\"code\":0}", 2026).isEmpty());
        assertTrue(HolidayJson.parseYear(null, 2026).isEmpty());
        assertTrue(HolidayJson.parseYear("", 2026).isEmpty());
    }

    @Test
    public void 年度表标记来源() {
        Map<Integer, String> map = new HashMap<>();
        map.put(20260101, "元旦");
        HolidayCalendar api = HolidayCalendar.ofApi(map);
        assertTrue(api.complete());
        assertEquals("元旦", api.nameOf(20260101));
        assertTrue(api.isHoliday(20260101));
        assertNull(api.nameOf(20260102));

        HolidayCalendar fallback = HolidayCalendar.ofFallback(map);
        assertFalse(fallback.complete());
        assertEquals(1, fallback.size());
    }
}
