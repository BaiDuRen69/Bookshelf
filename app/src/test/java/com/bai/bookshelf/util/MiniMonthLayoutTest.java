package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 小日历格子：邻月留白，本月日号按周一开头落位。 */
public class MiniMonthLayoutTest {

    @Test
    public void 固定42格且邻月补位为空白() {
        int[] cells = MiniMonthLayout.cells(2026, 11);
        assertEquals(MiniMonthLayout.CELLS, cells.length);
        assertEquals("2026-11-01 是周日，前面空 6 格", 6, leadBlanks(cells));
        assertEquals(20261101, cells[6]);
        assertEquals(20261130, cells[35]);
        assertEquals("30 天之后全部留白", 0, cells[36]);
        assertEquals(0, cells[41]);
    }

    @Test
    public void 二月与跨年首月不越界() {
        int[] feb = MiniMonthLayout.cells(2024, 2);
        assertEquals(20240201, feb[firstIndex(feb)]);
        assertEquals(20240229, feb[firstIndex(feb) + 28]);
        int[] jan = MiniMonthLayout.cells(2027, 1);
        assertEquals(20270101, jan[firstIndex(jan)]);
        assertEquals(20270131, jan[firstIndex(jan) + 30]);
        for (int key : jan) {
            if (key != 0) {
                assertTrue("只含本月日号", key / 100 == 202701);
            }
        }
    }

    @Test
    public void 非法年月全空白() {
        for (int key : MiniMonthLayout.cells(0, 1)) {
            assertEquals(0, key);
        }
        for (int key : MiniMonthLayout.cells(2026, 13)) {
            assertEquals(0, key);
        }
    }

    private static int leadBlanks(int[] cells) {
        int n = 0;
        while (n < cells.length && cells[n] == 0) {
            n++;
        }
        return n;
    }

    private static int firstIndex(int[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0) {
                return i;
            }
        }
        return -1;
    }
}
