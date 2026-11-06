package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 4.3 / 4.4 接线的纯 JVM 校验：Excel 打卡 sheet、提醒文案、备份元信息、全局搜索入口。 */
public class CheckInWiringTest {

    private static CheckIn ci(long bookId, int dayKey, boolean backfill) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.backfill = backfill;
        return c;
    }

    @Test
    public void Excel打卡sheet无数据不生成有数据按日期升序() {
        assertFalse(CheckInSheet.needed(null));
        assertFalse(CheckInSheet.needed(Collections.emptyList()));
        List<String[]> empty = CheckInSheet.rows(Collections.emptyList(), null);
        assertEquals("无数据时也只返回表头", 1, empty.size());
        assertArrayEquals(new String[]{"书名", "日期", "是否补卡"}, empty.get(0));

        Map<Long, String> titles = new HashMap<>();
        titles.put(1L, "三体");
        titles.put(2L, "活着");
        List<String[]> rows = CheckInSheet.rows(Arrays.asList(
                ci(1, 20261105, false), ci(2, 20261028, true), ci(9, 20261101, false)), titles);
        assertEquals(4, rows.size());
        assertArrayEquals(new String[]{"活着", "2026-10-28", "是"}, rows.get(1));
        assertArrayEquals(new String[]{"（已删除）", "2026-11-01", "否"}, rows.get(2));
        assertArrayEquals(new String[]{"三体", "2026-11-05", "否"}, rows.get(3));
    }

    @Test
    public void XLSX部件在打卡sheet开启时声明第二张表() {
        assertTrue(XlsxParts.workbookXml(true).contains("打卡"));
        assertTrue(XlsxParts.workbookXml(true).contains("rId2"));
        assertFalse(XlsxParts.workbookXml(false).contains("rId2"));
        assertTrue(XlsxParts.workbookRelsXml(true).contains("worksheets/sheet2.xml"));
        assertFalse(XlsxParts.workbookRelsXml(false).contains("sheet2"));
        assertTrue(XlsxParts.contentTypesXml(false, true)
                .contains("/xl/worksheets/sheet2.xml"));
        assertFalse(XlsxParts.contentTypesXml(false, false).contains("sheet2"));
        assertTrue("原有无参重载仍可用", XlsxParts.workbookXml().contains("藏书"));
    }

    @Test
    public void 每日打卡提醒文案与触发条件() {
        assertEquals("今天还没打卡，3 本书在读",
                CheckInUiState.dailyReminderText(3));
        assertTrue(CheckInUiState.needDailyReminder(true, false, 3));
        assertFalse("今天已打卡不发", CheckInUiState.needDailyReminder(true, true, 3));
        assertFalse("没有在读书不发", CheckInUiState.needDailyReminder(true, false, 0));
        assertFalse("开关关掉不发", CheckInUiState.needDailyReminder(false, false, 3));
    }

    @Test
    public void 备份元信息带打卡条数且旧包缺字段不报错() {
        BackupMeta meta = new BackupMeta();
        meta.books = 12;
        meta.checkIns = 87;
        String json = meta.toJson();
        assertTrue(json.contains("checkIns"));
        assertEquals(87, BackupMeta.parse(json).checkIns);
        BackupMeta old = BackupMeta.parse("{\"format\":1,\"books\":5}");
        assertEquals("旧 meta 没有该字段按 0", 0, old.checkIns);
        assertEquals(5, old.books);
    }

    @Test
    public void 全局搜索打卡只指向打卡页() {
        GlobalSearch.Entry checkin = null;
        GlobalSearch.Entry calendar = null;
        for (GlobalSearch.Entry e : GlobalSearch.entries()) {
            if ("打卡".equals(e.name)) {
                checkin = e;
            }
            if ("阅读日历".equals(e.name)) {
                calendar = e;
            }
        }
        assertTrue("应有打卡入口", checkin != null);
        assertEquals(GlobalSearch.Target.CHECK_IN, checkin.target);
        assertTrue(checkin.keywords.contains("签到"));
        assertTrue(checkin.keywords.contains("连续"));
        assertTrue(calendar != null);
        assertFalse("阅读日历不能再抢「打卡」这个词",
                calendar.keywords.contains("打卡"));
    }

    @Test
    public void 打卡按钮读屏朗读为书名加动作() {
        assertEquals("为《三体》打卡", CheckInUiState.actionContentDescription(
                "三体", false, 20261105, 20261105));
        assertEquals("为《三体》补卡", CheckInUiState.actionContentDescription(
                "三体", false, 20261028, 20261105));
        assertEquals("《三体》已打卡，点击撤销", CheckInUiState.actionContentDescription(
                "三体", true, 20261105, 20261105));
    }

    @Test
    public void 本轮只新增一个SharedPreferences键() {
        assertEquals("notify_checkin_daily", ReminderPrefs.KEY_NOTIFY_CHECKIN_DAILY);
        assertEquals("ui_prefs", ReminderPrefs.PREFS);
    }
}
