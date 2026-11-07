package com.bai.bookshelf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.bai.bookshelf.data.entity.CheckIn;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.util.List;

/**
 * 打卡行的轮次归属（v26）：旧数据一律算第一轮，且这件事不靠回填。
 *
 * <p>DAO 的按轮查询要真库才能跑，归在 CheckInInstrumentedTest；这里只钉住
 * 「默认值」这条地基——一旦它变成 0，历史打卡就会掉进一个不存在的第 0 轮。
 */
public class CheckInSeqTest {

    private static final Gson GSON = new Gson();

    @Test
    public void 新建打卡行默认第一轮() {
        assertEquals(1, new CheckIn().seq);
    }

    @Test
    public void 老备份没有seq字段时补第一轮() {
        // Gson 走无参构造，字段初始化器生效（ReadingRecords 的 progress = -1 用的就是这条）
        String legacy = "[{\"bookId\":7,\"dayKey\":20261103,\"createdAt\":1,\"backfill\":false,"
                + "\"pages\":0}]";
        List<CheckIn> list = GSON.fromJson(legacy, new TypeToken<List<CheckIn>>() {
        }.getType());
        assertEquals(1, list.size());
        assertEquals("旧行要落在第一轮，不是第 0 轮", 1, list.get(0).seq);
    }

    @Test
    public void 带seq的备份按备份里的轮次还原() {
        String withSeq = "{\"bookId\":7,\"dayKey\":20261103,\"createdAt\":1,\"backfill\":false,"
                + "\"pages\":0,\"seq\":3}";
        CheckIn c = GSON.fromJson(withSeq, CheckIn.class);
        assertEquals(3, c.seq);
    }

    @Test
    public void 版本升到二十六且迁移清单不缺这一级() {
        assertEquals(26, AppDatabase.VERSION);
        assertNotNull("缺 25→26 的迁移", AppDatabase.migration(25));
        assertNotNull("24→25 仍在清单里", AppDatabase.migration(24));
    }
}
