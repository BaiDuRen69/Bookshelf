package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.Calendar;
import java.util.Collections;

/** 打卡下界：模糊轮看会话起始日，精确轮没记过时退拥有日 / 录入日。 */
public class CheckInStoreStartTest {

    private static Book book(String purchaseDate, long createdAt) {
        Book b = new Book();
        b.id = 7;
        b.readStatus = Book.READING;
        b.purchaseDate = purchaseDate;
        b.createdAt = createdAt;
        return b;
    }

    private static Book fuzzy(String startDate) {
        Book b = book("2026-10-01", 0);
        b.readingStartDate = startDate;
        b.readingRecords = "";
        return b;
    }

    private static Book precise(String startDate, String purchaseDate, long createdAt) {
        Book b = book(purchaseDate, createdAt);
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.mode = ReadingRecords.MODE_PRECISE;
        r.startDate = startDate;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        return b;
    }

    private static long noon(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void 模糊轮起始日就是会话开始那天() {
        assertEquals(20261101, CheckInStore.earliestCheckInKey(fuzzy("2026-11-01"), 0));
        assertEquals("已记多少天不影响模糊轮",
                20261101, CheckInStore.earliestCheckInKey(fuzzy("2026-11-01"), 5));
    }

    @Test
    public void 精确轮零天退到拥有日() {
        Book b = precise("2026-11-01", "2026-10-01", 0);
        assertEquals(20261001, CheckInStore.earliestCheckInKey(b, 0));
    }

    @Test
    public void 精确轮有日集合时下界收到该轮起点() {
        Book b = precise("2026-11-01", "2026-10-01", 0);
        assertEquals("已有日子后不再回到拥有日",
                20261101, CheckInStore.earliestCheckInKey(b, 3));
    }

    @Test
    public void 缺购买日退到录入当天() {
        Book b = precise("2026-11-01", "", noon(2026, 9, 20));
        assertEquals(20260920, CheckInStore.earliestCheckInKey(b, 0));
    }

    @Test
    public void 购买日与录入日都缺时不设下界() {
        assertEquals(0, CheckInStore.earliestCheckInKey(precise("2026-11-01", "", 0), 0));
        assertEquals(0, CheckInStore.earliestCheckInKey(null, 0));
    }
}
