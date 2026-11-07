package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsHealth;
import com.bai.bookshelf.util.StatsPace;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 洞察文案（v5.9.0 从 StatsFragment 抽出）：这些是「数据进、文字出」的纯函数，
 * 抽出来就是为了能直接断言，不再只能靠真机截图核对。
 */
public class StatsInsightTest {

    @Test
    public void speedLineCarriesSampleSize() {
        StatsPace.Pace p = new StatsPace.Pace();
        p.medianDays = 21;
        p.medianPagesPerDay = 14.76;
        p.daySample = 1;
        assertEquals("阅读速度：中位读完 21 天，15 页/天（样本 1 种）", StatsInsight.speedLine(p));

        StatsPace.Pace empty = new StatsPace.Pace();
        empty.medianDays = -1;
        assertTrue(StatsInsight.speedLine(empty).startsWith("阅读速度数据不足"));
    }

    @Test
    public void speedLineMarksMixedCaliberOnlyWhenBothSidesExist() {
        StatsPace.Pace mixed = new StatsPace.Pace();
        mixed.medianDays = 21;
        mixed.daySample = 12;
        mixed.preciseSample = 5;
        mixed.fuzzySample = 7;
        assertEquals("阅读速度：中位读完 21 天（样本 12 种 · 其中精确 5 本、区间 7 本）",
                StatsInsight.speedLine(mixed));

        StatsPace.Pace single = new StatsPace.Pace();
        single.medianDays = 21;
        single.daySample = 3;
        single.preciseSample = 3;
        assertEquals("阅读速度：中位读完 21 天（样本 3 种）", StatsInsight.speedLine(single));
    }

    @Test
    public void holdingsLineNoLongerRepeatsHeroTotals() {
        StatsHoldings.Holdings h = new StatsHoldings.Holdings();
        h.titles = 121;
        h.copies = 128;
        h.readDone = 7;
        String line = StatsInsight.holdingsLine(h);
        // v5.9.0 合并：种/册由页头 hero 说，这里只说读完情况（口径不变，只是不重复）
        assertEquals("已读 7 种（6%）", line);
        assertTrue(line, !line.contains("121"));
        assertTrue(line, !line.contains("128"));
    }

    @Test
    public void anomalyLineAppendsLendingTail() {
        StatsHealth.Anomalies a = new StatsHealth.Anomalies();
        String clean = StatsInsight.anomalyLine(a, Collections.<String>emptyList(), 0);
        assertEquals("数据体检：没发现重复或异常记录", clean);

        // 只有借阅那两项有问题：计数与清单都要出现
        String onlyLending = StatsInsight.anomalyLine(a, Arrays.asList("整本在外"), 2);
        assertTrue(onlyLending, onlyLending.startsWith("数据体检 3 处要看看"));
        assertTrue(onlyLending, onlyLending.contains("在读但全部册在外 1 本"));
        assertTrue(onlyLending, onlyLending.contains("《整本在外》"));
        assertTrue(onlyLending, onlyLending.contains("借出/归还日期残缺 2 条"));
    }

    @Test
    public void anomalyLineKeepsClassicCountsBeforeLendingTail() {
        StatsHealth.Anomalies a = new StatsHealth.Anomalies();
        a.duplicateIsbn.add("978x");
        a.duplicateTitle.add("同名书");
        a.negativePrice = 1;
        String line = StatsInsight.anomalyLine(a, Collections.<String>emptyList(), 0);
        assertTrue(line, line.startsWith("数据体检 3 处要看看：重复 ISBN 1、同名 1、负价 1"));
        // 借阅两项都干净时不该出现尾巴
        assertTrue(line, !line.contains("全部册在外"));
    }

    @Test
    public void stocktakeLineReportsInStoreAndOut() {
        StatsHoldings.Holdings h = new StatsHoldings.Holdings();
        h.copies = 10;
        h.copiesLent = 3;
        h.copiesLost = 1;
        assertEquals(6, h.copiesInStore());
        assertTrue(StatsInsight.stocktakeLine(h).contains("在架 6 册"));
    }
}
