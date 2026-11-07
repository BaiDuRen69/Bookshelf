package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 双轨天数（v5.9.1 第二步）：模糊轨一个字都不许变，精确轨只认日集合。
 */
public class ReadDaysTest {

    private static ReadingRecords.Record fuzzy() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        r.startDate = "2026-11-01";
        r.endDate = "2026-11-10";
        return r;
    }

    private static ReadingRecords.Record precise() {
        ReadingRecords.Record r = fuzzy();
        r.mode = ReadingRecords.MODE_PRECISE;
        return r;
    }

    @Test
    public void 模糊轨与旧区间口径逐字相同() {
        ReadingRecords.Record r = fuzzy();
        assertEquals(ReadingRecords.MODE_FUZZY, r.mode);
        assertEquals(10, ReadingRecords.readDays(r, null, null));
        assertEquals(ReadingRecords.days(r), ReadingRecords.readDays(r, null, null));

        List<int[]> spans = Collections.singletonList(new int[]{20261103, 20261105});
        assertEquals("借出去那三天要照旧扣掉", 7, ReadingRecords.readDays(r, null, spans));
        assertEquals(ReadingRecords.days(r, spans), ReadingRecords.readDays(r, null, spans));
    }

    @Test
    public void 模糊轨的在读仍然算到今天() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.startDate = DateKeys.formatKey(DateKeys.todayKey());
        r.endDate = "";
        assertEquals("今天开始、还没结束 = 1 天", 1, ReadingRecords.readDays(r, null, null));
    }

    @Test
    public void 精确轨只数日集合不看外借区间() {
        ReadingRecords.Record r = precise();
        List<Integer> keys = Arrays.asList(20261101, 20261103, 20261105, 20261108, 20261110);
        assertEquals(5, ReadingRecords.readDays(r, keys, null));
        List<int[]> spans = Collections.singletonList(new int[]{20261101, 20261110});
        assertEquals("日集合本身就是事实：打过卡的那天就是读了，不再扣区间",
                5, ReadingRecords.readDays(r, keys, spans));
    }

    @Test
    public void 精确轨重复日子只算一天空集合算零天() {
        assertEquals(2, ReadDays.precise(Arrays.asList(20261101, 20261101, 20261102)));
        assertEquals(0, ReadDays.precise(new ArrayList<>()));
        assertEquals(0, ReadDays.precise(null));
        // 空集合不偷偷退回区间口径：那样会把「没记日子」显示成「记满了十天」
        assertEquals(0, ReadingRecords.readDays(precise(), Collections.emptyList(), null));
    }

    @Test
    public void 按轮取日集合去重并升序() {
        // keysOf 只按 seq 过滤：传进来的行必须是同一本书（DAO 已按 bookId 查）
        List<CheckIn> rows = new ArrayList<>();
        rows.add(row(7, 20261105, 1));
        rows.add(row(7, 20261101, 1));
        rows.add(row(7, 20261101, 1));
        rows.add(row(7, 20261109, 2));

        List<Integer> first = ReadDays.keysOf(rows, 1);
        assertEquals(Arrays.asList(20261101, 20261105), first);
        assertEquals(Collections.singletonList(20261109), ReadDays.keysOf(rows, 2));
        assertTrue("没有第三轮就是空集合", ReadDays.keysOf(rows, 3).isEmpty());
        assertEquals(2, ReadDays.precise(first));
    }

    @Test
    public void 口径判定不误伤空记录() {
        assertFalse(ReadDays.isPrecise(null));
        assertFalse(ReadDays.isPrecise(fuzzy()));
        assertTrue(ReadDays.isPrecise(precise()));
        assertEquals("记录为空时天数按 0，不抛", 0, ReadingRecords.readDays(null, null, null));
    }

    @Test
    public void 全量打卡按书和轮次分组() {
        List<CheckIn> rows = new ArrayList<>();
        rows.add(row(7, 20261105, 1));
        rows.add(row(7, 20261101, 1));
        rows.add(row(7, 20261109, 2));
        rows.add(row(8, 20261103, 1));
        java.util.Map<Long, java.util.Map<Integer, List<Integer>>> byBook = ReadDays.byBook(rows);
        assertEquals(2, byBook.size());
        assertEquals(Arrays.asList(20261101, 20261105), byBook.get(7L).get(1));
        assertEquals(Collections.singletonList(20261109), byBook.get(7L).get(2));
        assertEquals(Collections.singletonList(20261103), byBook.get(8L).get(1));
        assertTrue(ReadDays.byBook(null).isEmpty());
    }

    private static CheckIn row(long bookId, int dayKey, int seq) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.seq = seq;
        return c;
    }
}
