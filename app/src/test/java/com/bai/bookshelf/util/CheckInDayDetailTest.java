package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 某月每日明细：整月都要有一行，0 条的也保留。 */
public class CheckInDayDetailTest {

    private static CheckIn ci(long bookId, int dayKey, boolean backfill) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.backfill = backfill;
        return c;
    }

    @Test
    public void 整月每天一行升序且0条也保留() {
        List<CheckIn> rows = Arrays.asList(ci(2, 20261105, false), ci(1, 20261101, false));
        List<CheckInDayDetail.Day> days =
                CheckInDayDetail.ofMonth(rows, null, 202611, 20261107);
        assertEquals(30, days.size());
        assertEquals(20261101, days.get(0).dayKey);
        assertEquals(20261130, days.get(29).dayKey);
        assertEquals(1, days.get(0).total);
        assertEquals(0, days.get(3).total);
    }

    @Test
    public void 同一天多本计数并记补卡() {
        Map<Long, String> titles = new HashMap<>();
        titles.put(1L, "甲");
        titles.put(2L, "乙");
        List<CheckIn> rows = Arrays.asList(
                ci(2, 20261101, true), ci(1, 20261101, false));
        List<CheckInDayDetail.Day> days =
                CheckInDayDetail.ofMonth(rows, titles, 202611, 20261107);
        CheckInDayDetail.Day d = days.get(0);
        assertEquals(2, d.total);
        assertEquals(1, d.backfill);
        assertEquals(Arrays.asList("甲", "乙"), d.titles);
    }

    @Test
    public void 已删除书籍显示占位而不是漏掉() {
        Map<Long, String> titles = Collections.singletonMap(1L, "还在");
        List<CheckIn> rows = Arrays.asList(ci(1, 20261102, false), ci(9, 20261102, false));
        List<CheckInDayDetail.Day> days =
                CheckInDayDetail.ofMonth(rows, titles, 202611, 20261107);
        assertEquals(2, days.get(1).total);
        assertEquals(Arrays.asList("还在", CheckInDayDetail.DELETED_TITLE),
                days.get(1).titles);
    }

    @Test
    public void 未来日标记且不跨月混入() {
        List<CheckIn> rows = new ArrayList<>(Arrays.asList(
                ci(1, 20261106, false), ci(1, 20261201, false)));
        List<CheckInDayDetail.Day> days =
                CheckInDayDetail.ofMonth(rows, null, 202611, 20261105);
        assertTrue(days.get(5).future);   // 11-06
        assertFalse(days.get(4).future);  // 11-05 = 今天
        assertEquals(0, days.get(0).total);
        int total = 0;
        for (CheckInDayDetail.Day d : days) {
            total += d.total;
        }
        assertEquals("12 月的行不能算进 11 月", 1, total);
    }

    @Test
    public void 非法年月返回空列表() {
        assertTrue(CheckInDayDetail.ofMonth(null, null, 202600, 20261107).isEmpty());
        assertTrue(CheckInDayDetail.ofMonth(null, null, 202613, 20261107).isEmpty());
    }
}
