package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ReadingCalendarTest {

    private static Book bookWithRecord(int status, String start, String end) {
        Book book = new Book();
        List<ReadingRecords.Record> records = new ArrayList<>();
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = status;
        r.startDate = start;
        r.endDate = end;
        records.add(r);
        ReadingRecords.syncTo(book, records);
        return book;
    }

    @Test
    public void rangeCoversBothEnds() {
        Map<String, Integer> days = ReadingCalendar.dailyDays(
                bookWithRecord(Book.READ_DONE, "2026-01-01", "2026-01-03"));
        assertEquals(3, days.size());
        assertTrue(days.containsKey("2026-01-01"));
        assertTrue(days.containsKey("2026-01-02"));
        assertTrue(days.containsKey("2026-01-03"));
    }

    @Test
    public void ongoingReadingCountsToToday() {
        String today = DateKeys.formatKey(DateKeys.todayKey());
        Map<String, Integer> days = ReadingCalendar.dailyDays(
                bookWithRecord(Book.READING, today, ""));
        assertEquals(1, days.size());
        assertTrue(days.containsKey(today));
    }

    @Test
    public void unfinishedDroppedWithoutEndIsSkipped() {
        Map<String, Integer> days = ReadingCalendar.dailyDays(
                bookWithRecord(Book.READ_DROPPED, "2026-01-01", ""));
        assertTrue(days.isEmpty());
    }

    @Test
    public void titlesByDayListsBooksWithAuthors() {
        Book a = bookWithRecord(Book.READ_DONE, "2026-01-01", "2026-01-02");
        a.title = "三体";
        a.author = "刘慈欣";
        Book b = bookWithRecord(Book.READ_DONE, "2026-01-02", "2026-01-02");
        b.title = "活着";
        java.util.List<String> day = ReadingCalendar
                .titlesByDay(Arrays.asList(a, b)).get("2026-01-02");
        assertEquals(2, day.size());
        assertTrue(day.get(0).contains("三体"));
        assertTrue(day.get(0).contains("刘慈欣"));
        assertTrue(day.get(1).contains("活着"));
    }

    @Test
    public void latestYmdFollowsLatestDay() {
        Book book = bookWithRecord(Book.READ_DONE, "2025-12-30", "2026-01-03");
        int[] ymd = ReadingCalendar.latestYmd(ReadingCalendar.dailyDays(book));
        assertEquals(2026, ymd[0]);
        assertEquals(0, ymd[1]);
    }

    @Test
    public void invalidDatesProduceNoDays() {
        Book book = bookWithRecord(Book.READ_DONE, "not-a-date", "2026-01-03");
        assertTrue(ReadingCalendar.dailyDays(book).isEmpty());
    }
}