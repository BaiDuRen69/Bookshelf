package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 模糊 / 精确双口径的地基（v5.9.1 第一步）：老数据默认模糊，精确轮的日期是日集合的派生值。
 *
 * <p>精确轮的「哪几天读过」只有 check_ins 一个真相，这里只验派生出来的起止，
 * 不验日集合本身（那是 DAO 与打卡页的事）。
 */
public class ReadingRecordsModeTest {

    @Test
    public void 缺mode字段的老JSON按模糊处理() {
        String legacy = "[{\"seq\":1,\"status\":2,\"startDate\":\"2026-03-01\","
                + "\"endDate\":\"2026-04-02\",\"rating\":4.0,\"progress\":100}]";
        List<ReadingRecords.Record> list = ReadingRecords.parse(legacy);
        assertEquals(1, list.size());
        assertEquals("历史数据不加字段就视为模糊",
                ReadingRecords.MODE_FUZZY, list.get(0).mode);
    }

    @Test
    public void 精确标记能存能取回来() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        r.mode = ReadingRecords.MODE_PRECISE;
        List<ReadingRecords.Record> list = new ArrayList<>();
        list.add(r);
        String json = ReadingRecords.toJson(list);
        assertEquals(ReadingRecords.MODE_PRECISE, ReadingRecords.parse(json).get(0).mode);
    }

    @Test
    public void 已读的精确轮起止取日集合两端() {
        ReadingRecords.Record r = new ReadingRecords.Record(2);
        r.status = Book.READ_DONE;
        ReadingRecords.deriveRange(r, Arrays.asList(20261103, 20261106, 20261120));
        assertEquals("2026-11-03", r.startDate);
        assertEquals("2026-11-20", r.endDate);
    }

    @Test
    public void 在读与弃读的精确轮不写结束日() {
        ReadingRecords.Record reading = new ReadingRecords.Record(1);
        reading.status = Book.READING;
        ReadingRecords.deriveRange(reading, Arrays.asList(20261101, 20261105));
        assertEquals("2026-11-01", reading.startDate);
        assertEquals("还没读完，结束日必须是空", "", reading.endDate);

        ReadingRecords.Record dropped = new ReadingRecords.Record(1);
        dropped.status = Book.READ_DROPPED;
        ReadingRecords.deriveRange(dropped, Arrays.asList(20261101, 20261105));
        assertEquals("2026-11-01", dropped.startDate);
        assertEquals("", dropped.endDate);
    }

    @Test
    public void 空日集合一个字都不写() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        r.startDate = "2026-01-01";
        r.endDate = "2026-02-02";
        ReadingRecords.deriveRange(r, new ArrayList<>());
        assertEquals("空集合不能把用户已有的日期抹掉", "2026-01-01", r.startDate);
        assertEquals("2026-02-02", r.endDate);
        ReadingRecords.deriveRange(r, null);
        assertEquals("2026-01-01", r.startDate);
    }

    @Test
    public void 乱序日集合派生出的起止与集合一致() {
        List<Integer> keys = Arrays.asList(20261120, 20261103, 20261111, 20261106);
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        ReadingRecords.deriveRange(r, keys);
        assertEquals(DateKeys.formatKey(Collections.min(keys)), r.startDate);
        assertEquals(DateKeys.formatKey(Collections.max(keys)), r.endDate);
        // 派生出来的两端确实落在集合里，不是集合外的空档
        assertTrue(keys.contains(DateKeys.parseKey(r.startDate)));
        assertTrue(keys.contains(DateKeys.parseKey(r.endDate)));
    }
}
