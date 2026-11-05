package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 榜单行的文本模型（v5.9.0）：两列行 = 名称 + 用「 · 」串的数值段。
 * 这里只验拼接，不验颜色与布局。
 */
public class StatsRowsTest {

    @Test
    public void joinSkipsNullAndBlankParts() {
        assertEquals("借出 3 笔 · 超期 2 笔",
                StatsRows.join("借出 3 笔", null, "", "   ", "超期 2 笔"));
        assertEquals("只有一段", StatsRows.join("只有一段"));
        assertEquals("", StatsRows.join((String[]) null));
        assertEquals("", StatsRows.join());
    }

    @Test
    public void joinTrimsEachPart() {
        // 不留下「A ·  · B」这种洞，也不留首尾空格
        assertEquals("每页 ¥0.06 · （3 种 · 花费 ¥12）",
                StatsRows.join("  每页 ¥0.06 ", "（3 种 · 花费 ¥12） "));
    }

    @Test
    public void nameFallsBackToPlaceholder() {
        assertEquals("（未命名）", StatsRows.name(null));
        assertEquals("（未命名）", StatsRows.name("   "));
        assertEquals("算法导论", StatsRows.name(" 算法导论 "));
    }

    @Test
    public void valueKeepsEmptyForCallerToDecide() {
        assertEquals("", StatsRows.value(null));
        assertEquals("未填应还日期", StatsRows.value(" 未填应还日期 "));
    }

    @Test
    public void borrowerRowMatchesWording() {
        // 借阅人榜：名称与数值分列后，数值段的说法与原来一致
        assertEquals("借出 3 笔 · 还在手上 3 册 · 超期 3 笔",
                StatsRows.join("借出 3 笔", "还在手上 3 册", "超期 3 笔"));
        assertEquals("借出 1 笔", StatsRows.join("借出 1 笔", null, null));
    }
}
