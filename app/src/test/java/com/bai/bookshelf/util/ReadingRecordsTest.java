package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ReadingRecordsTest {

    private static ReadingRecords.Record record(int seq, int status,
                                                String start, String end, double rating) {
        ReadingRecords.Record r = new ReadingRecords.Record(seq);
        r.status = status;
        r.startDate = start;
        r.endDate = end;
        r.rating = rating;
        return r;
    }

    @Test
    public void legacyBookWithoutJsonBecomesFirstRecord() {
        Book book = new Book();
        book.readStatus = Book.READ_DONE;
        book.readingStartDate = "2026-10-01";
        book.readingFinishDate = "2026-10-05";
        book.rating = 4;
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).seq);
        assertEquals(Book.READ_DONE, records.get(0).status);
        assertEquals("2026-10-05", records.get(0).endDate);
        assertEquals(4.0, records.get(0).rating, 0.001);
    }

    @Test
    public void legacyHalfRatingComesFromRatingAvg() {
        Book book = new Book();
        book.readStatus = Book.READ_DONE;
        book.rating = 5;
        book.ratingAvg = 4.5;
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        assertEquals(4.5, records.get(0).rating, 0.001);
    }

    @Test
    public void emptyBookHasNoRecords() {
        assertTrue(ReadingRecords.load(new Book()).isEmpty());
    }

    @Test
    public void syntheticSessionInheritsReadingProgress() {
        Book reading = new Book();
        reading.readStatus = Book.READING;
        reading.readingProgress = 60;
        assertEquals("在读会话继承书上的进度", 60,
                ReadingRecords.load(reading).get(0).progress);

        Book done = new Book();
        done.readStatus = Book.READ_DONE;
        done.readingProgress = 65;
        assertEquals("已读会话固定 100", 100,
                ReadingRecords.load(done).get(0).progress);
    }

    @Test
    public void jsonRoundTripKeepsAllRecords() {
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DROPPED, "2024-01-01", "2024-01-03", 3));
        records.add(record(2, Book.READ_DONE, "2026-10-01", "2026-10-10", 4.5));
        List<ReadingRecords.Record> back = ReadingRecords.parse(
                ReadingRecords.toJson(records));
        assertEquals(2, back.size());
        assertEquals(Book.READ_DROPPED, back.get(0).status);
        assertEquals(4.5, back.get(1).rating, 0.001);
    }

    @Test
    public void badJsonIsIgnored() {
        assertTrue(ReadingRecords.parse("not-json").isEmpty());
    }

    @Test
    public void syncToTakesLatestSeqButAveragesRating() {
        Book book = new Book();
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "2024-01-01", "2024-01-03", 3));
        records.add(record(2, Book.READING, "2026-10-01", "", 4));
        ReadingRecords.syncTo(book, records);
        // v5.8.31 口径变更：状态看在读会话，日期看「最后一场 DONE」，
        // 平均值只算已读会话——再读中的书不再把上一轮的读完日期抹掉。
        assertEquals(Book.READING, book.readStatus);
        assertEquals("2026-10-01", book.readingStartDate);
        assertEquals("2024-01-03", book.readingFinishDate);
        assertEquals(3.0, book.ratingAvg, 0.001);
        assertEquals(3, book.rating);
        assertEquals(0, book.readingProgress);   // 进行中的会话没进度 → 0
        assertEquals(2, ReadingRecords.parse(book.readingRecords).size());
    }

    @Test
    public void syncToEmptyClearsSummary() {
        Book book = new Book();
        book.readStatus = Book.READ_DONE;
        book.readingFinishDate = "2026-10-10";
        book.rating = 5;
        book.ratingAvg = 4.5;
        book.readingRecords = "[{\"seq\":1}]";
        ReadingRecords.syncTo(book, new ArrayList<>());
        assertEquals(Book.READ_NONE, book.readStatus);
        assertEquals("", book.readingFinishDate);
        assertEquals(0, book.rating);
        assertEquals(0.0, book.ratingAvg, 0.001);
        assertEquals("", book.readingRecords);
    }

    @Test
    public void averageIgnoresUnratedRecords() {
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "2024-01-01", "2024-01-03", 5));
        records.add(record(2, Book.READ_DONE, "2026-10-01", "2026-10-10", 0));
        records.add(record(3, Book.READ_DONE, "2026-10-11", "2026-10-12", 4));
        assertEquals(4.5, ReadingRecords.averageRating(records), 0.001);
    }

    @Test
    public void averageRoundsToOneDecimal() {
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "", "", 4.5));
        records.add(record(2, Book.READ_DONE, "", "", 4));
        records.add(record(3, Book.READ_DONE, "", "", 4.5));
        assertEquals(4.3, ReadingRecords.averageRating(records), 0.001);
    }

    @Test
    public void formatRatingKeepsHalfStep() {
        assertEquals("4", ReadingRecords.formatRating(4));
        assertEquals("4.5", ReadingRecords.formatRating(4.5));
        assertEquals("4.3", ReadingRecords.formatRating(4.33));
    }

    @Test
    public void refreshSummaryFixesStaleBookRating() {
        Book book = new Book();
        book.rating = 5;
        book.ratingAvg = 5;
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "2026-10-01", "2026-10-05", 4));
        book.readingRecords = ReadingRecords.toJson(records);
        ReadingRecords.refreshSummary(book);
        assertEquals(4.0, book.ratingAvg, 0.001);
        assertEquals(4, book.rating);
    }

    @Test
    public void refreshSummaryKeepsLegacyRatingWhenRecordsUnrated() {
        Book book = new Book();
        book.rating = 5;
        book.ratingAvg = 5;
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "2026-10-01", "2026-10-05", 0));
        book.readingRecords = ReadingRecords.toJson(records);
        ReadingRecords.refreshSummary(book);
        assertEquals(5.0, book.ratingAvg, 0.001);
    }

    @Test
    public void daysCountsBothEnds() {
        assertEquals(3, ReadingRecords.days(
                record(1, Book.READ_DONE, "2026-10-01", "2026-10-03", 0)));
    }

    @Test
    public void daysOfReadingRunsToToday() {
        String today = DateKeys.formatKey(DateKeys.todayKey());
        assertEquals(1, ReadingRecords.days(record(1, Book.READING, today, "", 0)));
    }

    @Test
    public void suggestedStatusFollowsDates() {
        assertEquals(Book.READ_DONE,
                ReadingRecords.suggestedStatus("2026-10-01", "2026-10-03"));
        assertEquals(Book.READING,
                ReadingRecords.suggestedStatus("2026-10-01", ""));
        assertEquals(Book.READ_NONE, ReadingRecords.suggestedStatus("", ""));
    }

    @Test
    public void applyDroppedFillsEndDate() {
        Book book = new Book();
        ReadingRecords.applyStatus(book, Book.READ_DROPPED, "2026-10-30");
        assertEquals(Book.READ_DROPPED, book.readStatus);
        // v5.8.31：弃读日期只留在会话的 endDate 上，不再冒充「读完日期」
        // （旧实现给 DONE/DROPPED 都补 endDate，再无条件抄进 readingFinishDate）
        assertEquals("", book.readingFinishDate);
        assertEquals("2026-10-30", ReadingRecords.lastOf(
                ReadingRecords.parse(book.readingRecords)).endDate);
    }

    @Test
    public void applyReadingClearsEndDate() {
        Book book = new Book();
        book.readStatus = Book.READ_DONE;
        book.readingStartDate = "2026-10-01";
        book.readingFinishDate = "2026-10-20";
        ReadingRecords.applyStatus(book, Book.READING, "2026-10-30");
        assertEquals(Book.READING, book.readStatus);
        assertEquals("2026-10-01", book.readingStartDate);
        assertEquals("", book.readingFinishDate);
    }

    @Test
    public void applyNoneClearsAllRecords() {
        Book book = new Book();
        List<ReadingRecords.Record> records = new ArrayList<>();
        records.add(record(1, Book.READ_DONE, "2026-10-01", "2026-10-05", 4));
        ReadingRecords.syncTo(book, records);
        ReadingRecords.applyStatus(book, Book.READ_NONE, "2026-10-30");
        assertEquals(Book.READ_NONE, book.readStatus);
        assertEquals("", book.readingRecords);
        assertEquals(0, book.rating);
        assertEquals(0.0, book.ratingAvg, 0.001);
    }
}
