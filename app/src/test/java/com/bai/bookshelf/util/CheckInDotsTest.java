package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 打卡点的排布与当月计数（纯计算）。 */
public class CheckInDotsTest {

    private static CheckIn record(int dayKey, boolean backfill) {
        CheckIn c = new CheckIn();
        c.dayKey = dayKey;
        c.backfill = backfill;
        return c;
    }

    @Test
    public void 点数按本数封顶三个() {
        assertEquals(0, CheckInDots.dotCount(0));
        assertEquals(1, CheckInDots.dotCount(1));
        assertEquals(2, CheckInDots.dotCount(2));
        assertEquals(3, CheckInDots.dotCount(3));
        assertEquals(3, CheckInDots.dotCount(9));
    }

    @Test
    public void 只有单点时走大点() {
        assertTrue(CheckInDots.useLargeDot(1));
        assertFalse(CheckInDots.useLargeDot(0));
        assertFalse(CheckInDots.useLargeDot(2));
        assertFalse(CheckInDots.useLargeDot(5));
    }

    @Test
    public void 实心在左空心在右() {
        assertArrayEquals(new int[]{2, 0}, CheckInDots.filledHollow(2, 0));
        assertArrayEquals(new int[]{1, 1}, CheckInDots.filledHollow(2, 1));
        assertArrayEquals(new int[]{0, 1}, CheckInDots.filledHollow(1, 1));
        assertArrayEquals(new int[]{2, 1}, CheckInDots.filledHollow(3, 1));
    }

    @Test
    public void 超三本时先保实心再排空心() {
        // 5 本里 2 本补卡 → 三个点：1 实心 + 2 空心
        assertArrayEquals(new int[]{1, 2}, CheckInDots.filledHollow(5, 2));
        // 补卡数不可能超过本数，越界也不要把点数算错
        assertArrayEquals(new int[]{0, 3}, CheckInDots.filledHollow(3, 9));
        assertArrayEquals(new int[]{3, 0}, CheckInDots.filledHollow(3, -2));
    }

    @Test
    public void 点数与实心空心之和一致() {
        for (int total = 0; total <= 6; total++) {
            for (int backfill = 0; backfill <= 6; backfill++) {
                int[] fh = CheckInDots.filledHollow(total, backfill);
                assertEquals(CheckInDots.dotCount(total), fh[0] + fh[1]);
            }
        }
    }

    @Test
    public void 当月记录组装出总本数与补卡本数() {
        List<CheckIn> list = new ArrayList<>(Arrays.asList(
                record(20261105, false), record(20261105, true), record(20261106, false),
                null));
        Map<Integer, int[]> marks = CheckInDots.marks(list);
        assertEquals(2, marks.size());
        assertEquals(2, CheckInDots.total(marks, 20261105));
        assertEquals(1, CheckInDots.backfill(marks, 20261105));
        assertEquals(1, CheckInDots.total(marks, 20261106));
        assertEquals(0, CheckInDots.backfill(marks, 20261106));
    }

    @Test
    public void 空输入给空表与零() {
        assertTrue(CheckInDots.marks(null).isEmpty());
        assertEquals(0, CheckInDots.total(null, 20261105));
        assertEquals(0, CheckInDots.backfill(CheckInDots.marks(new ArrayList<>()), 20261105));
    }

    @Test
    public void 精确轮只数本轮模糊轮照旧全量() {
        CheckIn preciseOld = new CheckIn();
        preciseOld.bookId = 1;
        preciseOld.dayKey = 20261103;
        preciseOld.seq = 1;
        CheckIn preciseNow = new CheckIn();
        preciseNow.bookId = 1;
        preciseNow.dayKey = 20261105;
        preciseNow.seq = 2;
        CheckIn fuzzy = new CheckIn();
        fuzzy.bookId = 2;
        fuzzy.dayKey = 20261103;
        fuzzy.seq = 1;
        List<CheckIn> list = new ArrayList<>(Arrays.asList(preciseOld, preciseNow, fuzzy));

        Map<Long, Integer> onlyTwo = new java.util.HashMap<>();
        onlyTwo.put(1L, 2);
        Map<Integer, int[]> marks = CheckInDots.marks(list, onlyTwo);
        // 精确书只认第 2 轮：11/3 只剩模糊那本，11/5 只有精确那本
        assertEquals(1, CheckInDots.total(marks, 20261103));
        assertEquals(1, CheckInDots.total(marks, 20261105));
        // 不指定精确轮 = 老口径，两天各两条都算
        assertEquals(2, CheckInDots.total(CheckInDots.marks(list), 20261103));
    }
}
