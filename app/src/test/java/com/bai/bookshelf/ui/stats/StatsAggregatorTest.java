package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class StatsAggregatorTest {

    private static Book book(int id, String purchaseDate) {
        Book b = new Book();
        b.id = id;
        b.title = "书" + id;
        b.purchaseDate = purchaseDate;
        return b;
    }

    @Test
    public void dailyCountsGroupsByDay() {
        List<Book> books = Arrays.asList(
                book(1, "2026-10-29"),
                book(2, "2026-10-29"),
                book(3, "2026-10-05"),
                book(4, "2026-09-29")); // 不同月份不计入
        int[] daily = StatsAggregator.dailyCounts(books, 2026, 9);
        assertEquals(31, daily.length);
        assertEquals(2, daily[28]);
        assertEquals(1, daily[4]);
        assertEquals(0, daily[0]);
    }

    @Test
    public void monthlyCountsGroupsByMonth() {
        List<Book> books = Arrays.asList(
                book(1, "2026-01-03"),
                book(2, "2026-10-29"),
                book(3, "2025-10-29"));
        int[] monthly = StatsAggregator.monthlyCounts(books, 2026);
        assertEquals(12, monthly.length);
        assertEquals(1, monthly[0]);
        assertEquals(1, monthly[9]);
    }

    @Test
    public void yearDailyUsesDayOfYearIndex() {
        List<Book> books = Arrays.asList(
                book(1, "2025-10-29"),
                book(2, "2025-01-01"));
        int[] days = StatsAggregator.yearDaily(books, 2025);
        assertEquals(365, days.length);
        assertEquals(1, days[0]);
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2025, 9, 29);
        assertEquals(1, days[c.get(Calendar.DAY_OF_YEAR) - 1]);
    }

    @Test
    public void monthSummaryCountsOnlyPaidPurchasesInCost() {
        Book bought = book(1, "2026-10-29");
        bought.ownType = Book.OWN_BUY;
        bought.purchasePrice = 30.5;
        bought.createdAt = millis(2026, 9, 29);
        Book gift = book(2, "2026-10-04");
        gift.ownType = Book.OWN_GIFT;
        gift.purchasePrice = 99;
        Book otherMonth = book(3, "2026-09-01");
        otherMonth.createdAt = millis(2026, 8, 1);

        StatsAggregator.MonthSummary s = StatsAggregator.monthSummary(
                Arrays.asList(bought, gift, otherMonth), 2026, 9);
        assertEquals(2, s.bought);
        assertEquals(1, s.entered);
        assertEquals(30.5, s.cost, 0.001);
        assertEquals(1, s.daily[28]);
        assertEquals(1, s.daily[3]);
    }

    @Test
    public void yearSummaryGroupsMonthsAndEntered() {
        Book a = book(1, "2026-01-03");
        a.ownType = Book.OWN_BUY;
        a.purchasePrice = 10;
        a.createdAt = millis(2026, 0, 3);
        Book b = book(2, "2026-01-20");
        b.ownType = Book.OWN_BORROW;
        b.purchasePrice = 50;
        Book c = book(3, "2026-12-31");

        StatsAggregator.YearSummary s = StatsAggregator.yearSummary(
                Arrays.asList(a, b, c), 2026);
        // v5.7：借阅来源的书不计入购买数/购买分布，但录入数照常
        assertEquals(2, s.bought);
        assertEquals(1, s.entered);
        assertEquals(10, s.cost, 0.001);
        assertEquals(1, s.monthly[0]);
        assertEquals(1, s.monthly[11]);
    }

    private static long millis(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day);
        return c.getTimeInMillis();
    }
}
