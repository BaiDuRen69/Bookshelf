package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void 已打卡行的副标题后缀分今天与往日() {
        assertEquals("读到 43% · 今天已打卡",
                CheckInUiState.subtitleChecked("读到 43%", TODAY, TODAY));
        assertEquals("在读 · 已打卡",
                CheckInUiState.subtitleChecked("在读", 20261103, TODAY));
        // 选中日不是今天时不能写「今天」，那是假信息
        assertFalse(CheckInUiState.subtitleChecked("在读", 20261103, TODAY).contains("今天"));
        // 日子过了不等于这笔是补的，补卡标记在实体上，文案这里不猜
        assertFalse(CheckInUiState.subtitleChecked("在读", 20261103, TODAY).contains("补"));
        assertEquals("读到 12% · 借给 张三 · 今天已打卡",
                CheckInUiState.subtitleChecked(
                        CheckInUiState.subtitle(12, "张三"), TODAY, TODAY));
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
    public void 年视图两单元与整句同一批数字() {
        int[] sum = {87, 214, 2};
        CheckInUiState.Unit[] u = CheckInUiState.yearUnits(sum);
        assertEquals(2, u.length);
        assertEquals("今年打卡", u[0].label);
        assertEquals("87", u[0].value);
        assertEquals("天", u[0].unit);
        assertTrue(u[0].accent);
        assertEquals("累计", u[1].label);
        assertEquals("214", u[1].value);
        assertEquals("次", u[1].unit);
        String line = CheckInUiState.yearLine(sum);
        assertTrue(line.contains(u[0].value + " 天"));
        assertTrue(line.contains(u[1].value + " 次"));
        // 0 天 0 次：数字照实给，但别用主色喊
        CheckInUiState.Unit[] zero = CheckInUiState.yearUnits(new int[]{0, 0, 0});
        assertEquals("0", zero[0].value);
        assertFalse(zero[0].accent);
        assertFalse(zero[1].accent);
        assertNull(CheckInUiState.yearUnits(null)[0].note);
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

    @Test
    public void 摘要行三单元的标签数字单位() {
        CheckInUiState.Unit[] u =
                CheckInUiState.summaryUnits(new int[]{12, 20, 2}, new int[]{5, TODAY}, null);
        assertEquals(3, u.length);
        assertEquals("本月打卡", u[0].label);
        assertEquals("12", u[0].value);
        assertEquals("天", u[0].unit);
        assertTrue(u[0].accent);
        assertEquals("连续", u[1].label);
        assertEquals("5", u[1].value);
        assertEquals("补卡", u[2].label);
        assertEquals("2", u[2].value);
        assertEquals("次", u[2].unit);
    }

    @Test
    public void 三单元与整句用同一批数字() {
        int[] sum = {12, 20, 2};
        int[] streak = {5, TODAY};
        CheckInUiState.Unit[] u = CheckInUiState.summaryUnits(sum, streak, null);
        String line = CheckInUiState.summaryLine(202611, sum, streak);
        assertTrue(line.contains(u[0].value + " 天"));
        assertTrue(line.contains("连续 " + u[1].value + " 天"));
        assertTrue(line.contains("补卡 " + u[2].value + " 次"));
    }

    @Test
    public void 连续为零时数字照显示但不走主色() {
        CheckInUiState.Unit[] u =
                CheckInUiState.summaryUnits(new int[]{0, 0, 0}, new int[]{0, 0}, "今天未打");
        assertEquals("0", u[0].value);
        assertEquals("今天未打", u[0].note);
        assertEquals("0", u[1].value);
        assertFalse(u[1].accent);
        assertEquals("0", u[2].value);
        assertTrue(u[2].accent);
        assertNull(u[1].note);
        assertNull(u[2].note);
    }

    @Test
    public void 摘要单元容忍空入参() {
        CheckInUiState.Unit[] u = CheckInUiState.summaryUnits(null, null, null);
        assertEquals("0", u[0].value);
        assertEquals("0", u[1].value);
        assertEquals("0", u[2].value);
        assertFalse(u[1].accent);
    }

    @Test
    public void 今天未打提示只跟着今天那个月() {
        assertEquals("今天未打", CheckInUiState.todayNote(202611, TODAY, false));
        assertNull(CheckInUiState.todayNote(202611, TODAY, true));
        // 翻到上月看历史，不该在「本月打卡」旁挂今天的提示
        assertNull(CheckInUiState.todayNote(202610, TODAY, false));
    }
}
