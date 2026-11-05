package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsLend;
import com.bai.bookshelf.util.StatsPace;
import com.bai.bookshelf.util.StatsSpend;

/**
 * 栏首摘要行的内容（v5.9.1）：每栏三个「标签 + 数字」单元。
 *
 * <p>只从已有聚合结果里取数，不新增查询；算不出来的给「—」而不是 0
 * （口径层用 -1 表示算不出，这里翻成占位符，避免屏幕上出现无意义的 0）。
 */
final class StatsSummary {

    /** 算不出来时的占位 */
    static final String DASH = "—";

    private StatsSummary() {
    }

    /**
     * 一个单元 = {L5 标签, 数字, 单位}。这里只出字符串，不拼 span：
     * android.text 的 spannable 在纯 JVM 单测里是 stub，把 span 留给
     * StatsFragment 调 StatsText.metric(value, unit) 时再叠。
     */
    static String[] unit(String label, String value, String suffix) {
        if (value == null || value.isEmpty()) {
            return new String[]{label, DASH, ""};
        }
        return new String[]{label, value, suffix == null ? "" : suffix};
    }

    /** 百分比；口径层给 -1 表示样本不足，此时数字给 null 让 unit() 换成「—」 */
    static String percent(double rate) {
        if (Double.isNaN(rate) || rate < 0) {
            return null;
        }
        return String.valueOf(Math.round(rate * 100));
    }

    /** 百分比单元：标签 + 数字 + % */
    static String[] percentUnit(String label, double rate) {
        return unit(label, percent(rate), "%");
    }

    /** 藏书栏：册 / 花费 / 已读率。「种」在 hero 大数字上，这里不重复 */
    static String[][] books(StatsHoldings.Holdings h, StatsSpend.Spend s) {
        return new String[][]{
                unit("册", h == null ? null : String.valueOf(h.copies), null),
                unit("花费", s == null || s.paid <= 0 ? null
                        : com.bai.bookshelf.util.Money.compact(s.paid), null),
                percentUnit("已读率", h == null ? -1 : h.readRate()),
        };
    }

    /** 阅读栏：已读种 / 在读种 / 连续打卡天 */
    static String[][] reading(StatsHoldings.Holdings h, StatsPace.Habit habit) {
        return new String[][]{
                unit("已读", h == null ? null : String.valueOf(h.readDone), "种"),
                unit("在读", h == null ? null : String.valueOf(h.reading), "种"),
                unit("连续打卡", habit == null || habit.currentStreak <= 0 ? null
                        : String.valueOf(habit.currentStreak), "天"),
        };
    }

    /** 购书栏：花费 / 每页均价 / 平均折扣 */
    static String[][] purchase(StatsSpend.Spend s) {
        return new String[][]{
                unit("花费", s == null || s.paid <= 0 ? null
                        : com.bai.bookshelf.util.Money.compact(s.paid), null),
                unit("每页均价", s == null || s.perPage < 0 ? null
                        : com.bai.bookshelf.util.Money.compact(s.perPage), null),
                unit("平均折扣", s == null || s.discount < 0 ? null : s.discountText(), null),
        };
    }

    /** 借阅栏：在外册 / 逾期（按笔，与洞察同一口径）/ 按时归还率 */
    static String[][] loan(StatsHoldings.Holdings h, StatsLend.Lend l) {
        return new String[][]{
                unit("在外", h == null || h.copiesLent <= 0 ? null
                        : String.valueOf(h.copiesLent), "册"),
                unit("逾期", l == null || l.overdue <= 0 ? null
                        : String.valueOf(l.overdue), "笔"),
                percentUnit("按时归还", l == null ? -1 : l.onTimeRate),
        };
    }
}
