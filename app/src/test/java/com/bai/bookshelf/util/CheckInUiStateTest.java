package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 打卡页所有文案与按钮态：纯 JVM。 */
public class CheckInUiStateTest {

    private static final int TODAY = 20261105;

    @Test
    public void 按钮文案按今天补卡已打卡三态() {
        assertEquals("打卡", CheckInUiState.actionLabel(false, TODAY, TODAY));
        assertEquals("补卡", CheckInUiState.actionLabel(false, 20261103, TODAY));
        assertEquals("已打卡 ✓", CheckInUiState.actionLabel(true, TODAY, TODAY));
        assertEquals("已打卡 ✓", CheckInUiState.actionLabel(true, 20261103, TODAY));
    }

    @Test
    public void 未来日期不可打卡() {
        assertTrue(CheckInUiState.actionable(TODAY, TODAY));
        assertTrue(CheckInUiState.actionable(20261103, TODAY));
        assertFalse(CheckInUiState.actionable(20261106, TODAY));
    }

    @Test
    public void 副标题含进度与借出人() {
        assertEquals("读到 43%", CheckInUiState.subtitle(43, ""));
        assertEquals("读到 43% · 借给 张三", CheckInUiState.subtitle(43, " 张三 "));
        assertEquals("在读", CheckInUiState.subtitle(0, null));
        assertEquals("在读 · 借给 李四", CheckInUiState.subtitle(0, "李四"));
    }

    @Test
    public void 月小结固定格式() {
        assertEquals("11月已打卡 12 天 · 连续 5 天 · 补卡 2 次",
                CheckInUiState.summaryLine(202611, new int[]{12, 20, 2}, new int[]{5, TODAY}));
        assertEquals("1月已打卡 0 天 · 连续 0 天 · 补卡 0 次",
                CheckInUiState.summaryLine(202601, new int[]{0, 0, 0}, new int[]{0, 0}));
        assertEquals("11月已打卡 0 天 · 连续 0 天 · 补卡 0 次",
                CheckInUiState.summaryLine(202611, null, null));
    }

    @Test
    public void 统计行与年视图小结() {
        assertEquals("打卡：今年 87 天 · 连续 5 天 · 补卡 2 次",
                CheckInUiState.statsLine(2026, new int[]{87, 214, 2}, new int[]{5, TODAY}));
        assertEquals("今年打卡 87 天 · 累计 214 次",
                CheckInUiState.yearLine(new int[]{87, 214, 2}));
        assertEquals("今年打卡 0 天 · 累计 0 次", CheckInUiState.yearLine(null));
    }

    @Test
    public void 撤销条文案三种() {
        assertEquals("已为《三体》打卡",
                CheckInUiState.undoMessage(true, false, "三体", TODAY));
        assertEquals("已补卡 10月28日",
                CheckInUiState.undoMessage(true, true, "三体", 20261028));
        assertEquals("已撤销打卡",
                CheckInUiState.undoMessage(false, false, "三体", TODAY));
    }

    @Test
    public void 拒绝原因逐条说明() {
        assertEquals("未来的日期还不能打卡",
                CheckInUiState.rejectText(CheckInStore.Result.REJECTED_FUTURE));
        assertEquals("这一天早于开始阅读日",
                CheckInUiState.rejectText(CheckInStore.Result.REJECTED_BEFORE_START));
        assertEquals("只有「在读」的书能打卡",
                CheckInUiState.rejectText(CheckInStore.Result.REJECTED_NOT_READING));
        assertEquals("这一天不能补卡",
                CheckInUiState.rejectText(CheckInStore.Result.REJECTED_BACKFILL));
        assertEquals("操作未完成",
                CheckInUiState.rejectText(CheckInStore.Result.REMOVED));
        assertEquals("操作未完成", CheckInUiState.rejectText(null));
    }

    @Test
    public void 补卡长按说明与日期格式() {
        assertEquals("补卡会记录在 10月28日，不影响读完日期",
                CheckInUiState.backfillHint(20261028));
        assertEquals("10月28日", CheckInUiState.dayText(20261028));
        assertEquals("1月1日", CheckInUiState.dayText(20260101));
    }
}
