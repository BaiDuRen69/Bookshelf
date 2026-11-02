package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityReadingStatsBinding;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 统计页的「全部记录」入口：从月/年/总视图打开购买日历半屏面板，
 * 点某天后把当天详情同步到当前视图。
 */
public class StatsCalendarLauncher {

    private final ReadingStatsActivity activity;
    private final ActivityReadingStatsBinding b;

    public StatsCalendarLauncher(ReadingStatsActivity activity, ActivityReadingStatsBinding b) {
        this.activity = activity;
        this.b = b;
    }

    /** 月视图：初始定位到当前游标月 */
    public void showMonth(int year, int month0, Map<String, Integer> dailyCounts,
                          Map<String, List<String>> dailyBooks) {
        show(false, year, month0, dailyCounts, dailyBooks);
    }

    /** 年视图：初始定位到当前游标年 */
    public void showYear(int year, Map<String, Integer> dailyCounts,
                         Map<String, List<String>> dailyBooks) {
        show(true, year, 0, dailyCounts, dailyBooks);
    }

    /** 总视图：定位到有记录的最新年份 */
    public void showTotal(Map<String, Integer> dailyCounts,
                          Map<String, List<String>> dailyBooks) {
        show(true, latestYearWithRecords(dailyCounts), 0, dailyCounts, dailyBooks);
    }

    private void show(boolean yearMode, int year, int month0,
                      Map<String, Integer> dailyCounts,
                      Map<String, List<String>> dailyBooks) {
        // 标题的周期后缀由面板自己管理（切换粒度时会跟着变），这里只传基名「购买日历」
        String summary = activity.getString(R.string.stats_calendar_summary,
                activeDayCount(dailyCounts), totalBookCount(dailyCounts));

        PurchaseCalendarSheet.show(activity, activity.getString(R.string.stats_calendar_title),
                summary, dailyCounts, dailyBooks,
                new PurchaseCalendarSheet.Start(yearMode, year, month0),
                new PurchaseCalendarSheet.Labels(),
                (y, m, d, count) -> {
                    // 选中某天：把下方页面的当天详情同步更新
                    String text = StatsText.dayDetailText(y, m, d, count);
                    b.tvMonthDayDetail.setText(text);
                    b.tvYearDayDetail.setText(text);
                    activity.syncDayBooksFromSheet(y, m, d);
                });
    }

    /** 有购买记录的天数（全部年份） */
    private static int activeDayCount(Map<String, Integer> dailyCounts) {
        return dailyCounts.size();
    }

    /** 有购买记录的书的总数（全部年份） */
    private static int totalBookCount(Map<String, Integer> dailyCounts) {
        int sum = 0;
        for (int v : dailyCounts.values()) {
            sum += v;
        }
        return sum;
    }

    /** 最新一个有购买记录的年份（没有记录时返回当前年） */
    private static int latestYearWithRecords(Map<String, Integer> dailyCounts) {
        int year = 0;
        for (String key : dailyCounts.keySet()) {
            int[] pd = StatsText.parseYmd(key);
            if (pd != null && pd[0] > year) {
                year = pd[0];
            }
        }
        return year > 0 ? year : Calendar.getInstance().get(Calendar.YEAR);
    }
}
