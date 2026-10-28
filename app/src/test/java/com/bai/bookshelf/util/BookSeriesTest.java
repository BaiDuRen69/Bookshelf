package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class BookSeriesTest {

    @Test
    public void sortedByReadingOrderThenVolume() {
        Book v1 = book(1, "三体", "1", 1);
        Book v3 = book(3, "死神永生", "3", 0);
        Book v4 = book(4, "黑暗森林", "4", 0);
        List<Book> sorted = BookSeries.sorted(Arrays.asList(v3, v4, v1));
        assertEquals(1, sorted.get(0).id);
        assertEquals(3, sorted.get(1).id);
        assertEquals(4, sorted.get(2).id);
    }

    @Test
    public void numericVolumeSortsTenAfterTwo() {
        Book ten = book(10, "十", "10", 0);
        Book two = book(11, "二", "2", 0);
        List<Book> sorted = BookSeries.sorted(Arrays.asList(ten, two));
        assertEquals(11, sorted.get(0).id);
        assertEquals(10, sorted.get(1).id);
    }

    @Test
    public void missingVolumeDetectedOnlyForNumericVolumes() {
        List<String> missing = BookSeries.missingVolumes(Arrays.asList(
                book(1, "a", "1", 0), book(3, "c", "3", 0), book(4, "d", "4", 0)));
        assertEquals(1, missing.size());
        assertEquals("2", missing.get(0));

        List<String> nonNumeric = BookSeries.missingVolumes(Arrays.asList(
                book(1, "上", "上", 0), book(2, "下", "下", 0)));
        assertTrue(nonNumeric.isEmpty());
    }

    @Test
    public void volumeLabelAndNextVolume() {
        assertEquals("第 2 卷", BookSeries.volumeLabel(book(1, "a", "2", 0)));
        assertEquals("外传", BookSeries.volumeLabel(book(1, "a", "外传", 0)));
        assertEquals("第3卷", BookSeries.volumeLabel(book(1, "a", "第3卷", 0)));
        assertEquals("5", BookSeries.nextVolume(Arrays.asList(
                book(1, "a", "1", 0), book(2, "b", "4", 0))));
        assertEquals("", BookSeries.nextVolume(Arrays.asList(book(1, "a", "上", 0))));
    }

    @Test
    public void groupKeepsSeriesOrderAndExcludesStandalone() {
        Book v1 = book(1, "三体", "1", 1);
        Book v3 = book(3, "死神永生", "3", 0);
        Book v4 = book(4, "黑暗森林", "4", 0);
        Book standalone = new Book();
        standalone.id = 9;
        standalone.title = "Guns";
        Map<String, List<Book>> groups = BookSeries.group(
                Arrays.asList(v3, standalone, v1, v4));
        assertEquals(1, groups.size());
        List<Book> members = groups.get("三体");
        assertEquals(3, members.size());
        assertEquals(1, members.get(0).id);
        assertEquals(4, members.get(2).id);
    }

    @Test
    public void nullSafe() {
        assertEquals("", BookSeries.name(new Book()));
        assertEquals("", BookSeries.volumeLabel(new Book()));
        assertEquals("", BookSeries.nextVolume(null));
        assertTrue(BookSeries.group(null).isEmpty());
    }

    private static Book book(long id, String title, String volume, int order) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.seriesName = "三体";
        b.seriesVolume = volume;
        b.seriesOrder = order;
        return b;
    }
}
