package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Excel 第一张表末尾两列：「记录方式 / 已记天数」按最后一轮的口径给值。 */
public class ExcelExporterModeTest {

    private static Book book(long id, ReadingRecords.Record r) {
        Book b = new Book();
        b.id = id;
        b.title = "书" + id;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        return b;
    }

    private static Map<Long, Map<Integer, List<Integer>>> keys(
            long bookId, int seq, Integer... days) {
        Map<Long, Map<Integer, List<Integer>>> out = new HashMap<>();
        Map<Integer, List<Integer>> bySeq = new HashMap<>();
        bySeq.put(seq, new ArrayList<>(Arrays.asList(days)));
        out.put(bookId, bySeq);
        return out;
    }

    @Test
    public void 模糊轮填区间天数() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        r.startDate = "2026-05-01";
        r.endDate = "2026-06-12";
        assertArrayEquals(new String[]{"模糊", "43"},
                ExcelExporter.modeAndDays(book(1, r), null));
    }

    @Test
    public void 精确轮填实际记的天数() {
        ReadingRecords.Record r = new ReadingRecords.Record(2);
        r.mode = ReadingRecords.MODE_PRECISE;
        r.status = Book.READING;
        r.startDate = "2026-05-01";
        Map<Long, Map<Integer, List<Integer>>> byBook = keys(
                2, 2, 20260501, 20260510, 20260520, 20260601, 20260612);
        assertArrayEquals(new String[]{"精确", "5"},
                ExcelExporter.modeAndDays(book(2, r), byBook));
    }

    @Test
    public void 精确轮没有日集合时是零天() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.mode = ReadingRecords.MODE_PRECISE;
        r.status = Book.READING;
        assertArrayEquals(new String[]{"精确", "0"},
                ExcelExporter.modeAndDays(book(3, r), null));
    }

    @Test
    public void 没读过两列都留空() {
        Book b = new Book();
        b.id = 4;
        assertArrayEquals(new String[]{"", ""}, ExcelExporter.modeAndDays(b, null));
        assertArrayEquals(new String[]{"", ""}, ExcelExporter.modeAndDays(null, null));
    }
}
