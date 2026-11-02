package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.util.Typo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityReadingStatsBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 购书统计：从统计页进入的二级页，含 月 / 年 / 总 三个页签。
 * - 月：可切换月份，展示当月购买/录入本数 + 花费 + 每日购买本数条形图 + 购买日历热力图 + 当日购买排行；
 * - 年：可切换年份，展示当年购买/录入本数 + 花费 + 每月购买本数条形图 + 全年购买热力图 + 按月排行；
 * - 总：从第一本书购买日起至今的相伴时长 + 买/录入/已读/未读/在读总量 + 每年购买本数条形图。
 * 数据量小（个人藏书），一次全量加载后在内存里按页签聚合。
 *
 * <p>v4.20 新增：
 * <ul>
 *   <li>年月快速选择器：点标题直接跳转，不再需要连点几十次 ‹ ›；</li>
 *   <li>图表统一刻度：跨半行/跨月份/跨年份都用同一个最大值，柱子高度可直接目测比较；</li>
 *   <li>购买排行（月按天、年按月）与花费统计（只算自购）；</li>
 *   <li>微信读书风格的购买日历热力图，点某天显示当天买了几本。</li>
 * </ul>
 */
public class ReadingStatsActivity extends BaseActivity {

    private ActivityReadingStatsBinding b;
    private final List<Book> books = new ArrayList<>();
    private boolean loaded = false;

    /** 当前页签：0=月 1=年 2=总 */
    private int tab = 0;
    /** 月/年视图的浏览游标（默认当前月/年） */
    private final Calendar monthCursor = Calendar.getInstance();
    private final Calendar yearCursor = Calendar.getInstance();

    /** 全部书籍里「某一天最多买了几本」——全局统一刻度上限 */
    private int globalMaxPerDay = 0;
    /** 全部书籍里「某一月最多买了几本」 */
    private int globalMaxPerMonth = 0;
    /**
     * v4.22：全部年份的每日购买量（键 yyyy-MM-dd → 本数）。
     *
     * <p>v4.20 的热力图只喂「当前游标月/年」，导致日历被限制在当前或近一年数据。
     * 现在一次性汇总全量数据，「全部记录」面板按月/按年切分后即可覆盖所有年份。
     */
    private final Map<String, Integer> allDailyCounts = new LinkedHashMap<>();
    /** 每天买了哪几本（与 allDailyCounts 同步汇总） */
    private final Map<String, List<String>> allDailyBooks = new LinkedHashMap<>();

    /** 当前打开的年/月选择弹窗（选完直接关闭） */
    /** 年/月快速选择器（v5.6 从本类抽出） */
    private StatsPeriodPicker periodPicker;
    /** 购买排行区（v5.6 从本类抽出） */
    private StatsRankView rankView;
    /** 总览区（v5.6 从本类抽出） */
    private StatsTotalView totalView;
    /** 购买日历入口（v5.6 从本类抽出） */
    private StatsCalendarLauncher calendarLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityReadingStatsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        rankView = new StatsRankView(this);
        totalView = new StatsTotalView(this, b);
        calendarLauncher = new StatsCalendarLauncher(this, b);

        // v4.28：空态与书架/统计页统一（插画 + 去录入）
        b.emptyState.tvEmptyTitle.setText("还没有可统计的书");
        b.emptyState.tvEmptyDesc.setText("录入第一种书后，这里会显示购买、阅读与收藏趋势");
        b.emptyState.btnEmptyCta.setOnClickListener(v ->
                startActivity(new android.content.Intent(this,
                        com.bai.bookshelf.ui.edit.BookEditActivity.class)));

        b.tabMonth.setOnClickListener(v -> selectTab(0));
        b.tabYear.setOnClickListener(v -> selectTab(1));
        b.tabTotal.setOnClickListener(v -> selectTab(2));

        // 月切换：不能翻到未来（没有数据的月份）
        b.btnMonthPrev.setOnClickListener(v -> {
            monthCursor.add(Calendar.MONTH, -1);
            renderMonth();
        });
        b.btnMonthNext.setOnClickListener(v -> {
            monthCursor.add(Calendar.MONTH, 1);
            renderMonth();
        });
        // 年切换：同理不能翻到未来
        b.btnYearPrev.setOnClickListener(v -> {
            yearCursor.add(Calendar.YEAR, -1);
            renderYear();
        });
        b.btnYearNext.setOnClickListener(v -> {
            yearCursor.add(Calendar.YEAR, 1);
            renderYear();
        });

        // v4.20：点标题/下拉按钮直接弹出年/月选择器
        periodPicker = new StatsPeriodPicker(this, new StatsPeriodPicker.Host() {
            @Override
            public List<Integer> availableYears() {
                return ReadingStatsActivity.this.availableYears();
            }

            @Override
            public void onMonthPicked(int year, int month0) {
                monthCursor.set(year, month0, 1);
                renderMonth();
            }

            @Override
            public void onYearPicked(int year) {
                yearCursor.set(year, 0, 1);
                renderYear();
            }
        });
        b.tvMonthLabel.setOnClickListener(v ->
                periodPicker.showMonth(monthCursor.get(Calendar.YEAR),
                        monthCursor.get(Calendar.MONTH)));
        b.btnMonthPick.setOnClickListener(v ->
                periodPicker.showMonth(monthCursor.get(Calendar.YEAR),
                        monthCursor.get(Calendar.MONTH)));
        b.tvYearLabel.setOnClickListener(v ->
                periodPicker.showYear(yearCursor.get(Calendar.YEAR)));
        b.btnYearPick.setOnClickListener(v ->
                periodPicker.showYear(yearCursor.get(Calendar.YEAR)));

        // v4.20：点击日历某天，下方显示当天买了几本
        b.heatmapMonth.setOnDayClickListener((year, month, day, count) -> {
            b.tvMonthDayDetail.setText(StatsText.dayDetailText(year, month, day, count));
            bindDayBooks(b.tvMonthDayBooks, year, month, day);
        });
        b.heatmapYear.setOnDayClickListener((year, month, day, count) -> {
            b.tvYearDayDetail.setText(StatsText.dayDetailText(year, month, day, count));
            bindDayBooks(b.tvYearDayBooks, year, month, day);
        });

        // v4.21：柱状图右上角「全部记录」按钮 —— 从底部升起半屏面板，列出完整记录
        b.btnMonthDetail.setOnClickListener(v ->
                calendarLauncher.showMonth(monthCursor.get(Calendar.YEAR),
                        monthCursor.get(Calendar.MONTH), allDailyCounts, allDailyBooks));
        b.btnYearDetail.setOnClickListener(v ->
                calendarLauncher.showYear(yearCursor.get(Calendar.YEAR),
                        allDailyCounts, allDailyBooks));
        b.btnTotalDetail.setOnClickListener(v ->
                calendarLauncher.showTotal(allDailyCounts, allDailyBooks));

        // v4.21：点击柱状图里的某根柱子，同样联动下方的当天详情
        b.chartMonth.setOnBarClickListener((index, value) -> onChartBarClick(index, false));
        b.chartMonth2.setOnBarClickListener((index, value) -> onChartBarClick(index, true));
        b.chartYear.setOnBarClickListener((index, value) -> onYearChartBarClick(index));
        b.chartTotal.setOnBarClickListener((index, value) -> {
            // 「总」视图的柱子按年份，点击后切到该年并展示
            b.chartTotal.setHighlightedIndex(index);
        });

        selectTab(0);
        loadData();
    }

    private void loadData() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            books.clear();
            books.addAll(BookSanitizer.normalizeAll(app.db().bookDao().getAll()));
            computeGlobalMax();
            loaded = true;
            app.ui(this::render);
        });
    }

    /**
     * 计算全局刻度上限。
     *
     * <p>关键：所有图表都用这两个值做 Y 轴上限，这样「本月买 4 本」画出来的高度
     * 和「另一月买 4 本」完全一致，上/下半月之间也能直接比。
     */
    private void computeGlobalMax() {
        Map<String, Integer> perDay = new LinkedHashMap<>();
        Map<String, Integer> perMonth = new LinkedHashMap<>();
        // v4.22：同时汇总「全部年份」的每日购买量，供日历面板使用
        allDailyCounts.clear();
        allDailyBooks.clear();
        for (Book book : books) {
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd == null) {
                continue;
            }
            String dayKey = pd[0] + "-" + StatsText.pad2(pd[1] + 1) + "-" + StatsText.pad2(pd[2]);
            perDay.merge(dayKey, 1, Integer::sum);
            allDailyCounts.merge(dayKey, 1, Integer::sum);
            List<String> dayBooks = allDailyBooks.get(dayKey);
            if (dayBooks == null) {
                dayBooks = new ArrayList<>();
                allDailyBooks.put(dayKey, dayBooks);
            }
            dayBooks.add(BookText.calendarLabel(book));
            perMonth.merge(pd[0] + "-" + StatsText.pad2(pd[1] + 1), 1, Integer::sum);
        }
        globalMaxPerDay = 0;
        for (int v : perDay.values()) {
            globalMaxPerDay = Math.max(globalMaxPerDay, v);
        }
        globalMaxPerMonth = 0;
        for (int v : perMonth.values()) {
            globalMaxPerMonth = Math.max(globalMaxPerMonth, v);
        }
    }

    /** 切换页签并刷新 Tab 视觉 */
    private void selectTab(int index) {
        tab = index;
        b.llMonth.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        b.llYear.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        b.llTotal.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleTab(b.tabMonth, index == 0);
        styleTab(b.tabYear, index == 1);
        styleTab(b.tabTotal, index == 2);
        render();
    }

    /** 选中态：白底胶囊 + 主题色 medium；未选中：次级文字色 */
    private void styleTab(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected ? R.drawable.bg_seg_selected_inset_sm : 0);
        tv.setTextColor(selected
                ? ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        tv.setTypeface(selected
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
    }

    private void render() {
        if (!loaded) {
            return;
        }
        boolean empty = books.isEmpty();
        b.emptyStateContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.llTabs.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            b.llMonth.setVisibility(View.GONE);
            b.llYear.setVisibility(View.GONE);
            b.llTotal.setVisibility(View.GONE);
            return;
        }
        // 空数据时不渲染页签内容；有数据时按当前页签恢复可见性
        b.llMonth.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        b.llYear.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        b.llTotal.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (tab == 0) {
            renderMonth();
        } else if (tab == 1) {
            renderYear();
        } else {
            totalView.render(books);
        }
    }

    // ==================== 年/月快速选择器（v4.20） ====================

    /** 可选年份：书籍购买记录里出现过的年份 ∪ 当前年，倒序 */
    private List<Integer> availableYears() {
        TreeMap<Integer, Boolean> set = new TreeMap<>();
        set.put(Calendar.getInstance().get(Calendar.YEAR), true);
        for (Book book : books) {
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null) {
                set.put(pd[0], true);
            }
        }
        List<Integer> list = new ArrayList<>(set.keySet());
        Collections.reverse(list);
        return list;
    }

    // ==================== 「全部记录」日历面板（v4.22） ====================

    /** 月视图柱状图点击：联动下方的当天详情 */
    private void onChartBarClick(int index, boolean secondHalf) {
        int year = monthCursor.get(Calendar.YEAR);
        int month = monthCursor.get(Calendar.MONTH);
        int days = StatsText.daysInMonth(year, month);
        int half = (days + 1) / 2;
        int day = secondHalf ? (half + 1 + index) : (index + 1);
        if (day > days) {
            return;
        }
        int[] daily = StatsAggregator.dailyCounts(books, year, month);
        b.chartMonth.setHighlightedIndex(secondHalf ? -1 : index);
        b.chartMonth2.setHighlightedIndex(secondHalf ? index : -1);
        b.tvMonthDayDetail.setText(StatsText.dayDetailText(year, month, day, daily[day - 1]));
        bindDayBooks(b.tvMonthDayBooks, year, month, day);
    }

    /** 年视图柱状图点击：联动下方的当月详情 */
    private void onYearChartBarClick(int index) {
        int year = yearCursor.get(Calendar.YEAR);
        int[] monthly = StatsAggregator.monthlyCounts(books, year);
        b.chartYear.setHighlightedIndex(index);
        b.tvYearDayDetail.setText(String.format(Locale.getDefault(),
                "%d年%d月 · 购买 %d 本", year, index + 1, monthly[index]));
        // 柱子是「整月」不是某一天，没有对应的单日书目
        clearDayBooks(b.tvYearDayBooks);
    }

    /** 「当天买了哪几本」最多列几条，超出折叠成「…共 N 本」 */
    private static final int DAY_BOOKS_MAX = 8;

    /**
     * 某天的书名列表拼成多行「· 书名」。
     *
     * @param dayKey yyyy-MM-dd
     * @return 无记录时返回空串（调用方负责收起控件）
     */
    private String dayBooksText(String dayKey) {
        List<String> books = allDailyBooks.get(dayKey);
        if (books == null || books.isEmpty()) {
            return "";
        }
        int shown = Math.min(books.size(), DAY_BOOKS_MAX);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("· ").append(books.get(i));
        }
        if (books.size() > shown) {
            sb.append('\n').append("…共 ").append(books.size()).append(" 本");
        }
        return sb.toString();
    }

    /** 热力图回调给的 month 是 0-based，键里的月要补 1 */
    private static String dayKey(int year, int month0, int day) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month0 + 1, day);
    }

    private void bindDayBooks(TextView target, int year, int month0, int day) {
        String text = dayBooksText(dayKey(year, month0, day));
        if (text.isEmpty()) {
            clearDayBooks(target);
            return;
        }
        target.setText(text);
        target.setVisibility(View.VISIBLE);
    }

    private static void clearDayBooks(TextView target) {
        target.setText("");
        target.setVisibility(View.GONE);
    }

    /** 「全部记录」面板里点了某天：页面两处书目跟着一起更新，保证两边结果一致 */
    void syncDayBooksFromSheet(int year, int month0, int day) {
        bindDayBooks(b.tvMonthDayBooks, year, month0, day);
        bindDayBooks(b.tvYearDayBooks, year, month0, day);
    }

    /** 某年某月每天的购买数量（下标 0 = 1 日） */
    /** 星期几（周一/周二…），用于明细行的副标题 */
    // ==================== 月视图 ====================

    private void renderMonth() {
        int year = monthCursor.get(Calendar.YEAR);
        int month = monthCursor.get(Calendar.MONTH); // 0-based
        b.tvMonthLabel.setText(String.format(Locale.getDefault(), "%d年%d月", year, month + 1));

        // 不能翻到未来
        Calendar now = Calendar.getInstance();
        boolean atCurrentMonth = year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH);
        b.btnMonthNext.setEnabled(!atCurrentMonth);
        b.btnMonthNext.setAlpha(atCurrentMonth ? 0.3f : 1f);

        StatsAggregator.MonthSummary summary = StatsAggregator.monthSummary(books, year, month);
        int days = summary.daily.length;
        int[] daily = summary.daily;
        b.tvMonthBought.setText(String.valueOf(summary.bought));
        b.tvMonthEntered.setText(String.valueOf(summary.entered));
        b.tvMonthCostValue.setText(StatsText.formatMoney(summary.cost));

        // v2.9：每日图表拆两行——上半月 / 下半月，避免 31 根柱挤在一行
        int half = (days + 1) / 2;
        List<String> labels1 = new ArrayList<>();
        List<Integer> values1 = new ArrayList<>();
        for (int d = 1; d <= half; d++) {
            labels1.add(String.valueOf(d));
            values1.add(daily[d - 1]);
        }
        // v4.20：两行共用全局刻度，柱子高度才有可比性
        b.chartMonth.setUnit("本");
        b.chartMonth.setData(labels1, values1, globalMaxPerDay);

        if (half < days) {
            List<String> labels2 = new ArrayList<>();
            List<Integer> values2 = new ArrayList<>();
            for (int d = half + 1; d <= days; d++) {
                labels2.add(String.valueOf(d));
                values2.add(daily[d - 1]);
            }
            b.chartMonth2.setVisibility(View.VISIBLE);
            b.chartMonth2.setUnit("本");
            b.chartMonth2.setData(labels2, values2, globalMaxPerDay);
        } else {
            b.chartMonth2.setVisibility(View.GONE);
        }

        // v4.20：购买日历热力图（当月）
        b.heatmapMonth.setYearMode(false);
        b.heatmapMonth.setMonthData(year, month, daily);
        renderHeatmapLegend(b.llMonthLegend);
        // 切换月份后重置上一次的「日详情」，避免残留上一个月的点击结果
        b.tvMonthDayDetail.setText(R.string.stats_click_day_hint);
        clearDayBooks(b.tvMonthDayBooks);
        // v4.21：切换月份后清掉柱状图高亮
        b.chartMonth.setHighlightedIndex(-1);
        b.chartMonth2.setHighlightedIndex(-1);

        // v4.20：本月购买排行（按天降序，没有购买的天不入榜）
        rankView.render(b.llMonthRank, b.tvMonthRankTitle, StatsRankView.dayRank(month, daily));
    }

    // ==================== 年视图 ====================

    private void renderYear() {
        int year = yearCursor.get(Calendar.YEAR);
        b.tvYearLabel.setText(String.format(Locale.getDefault(), "%d年", year));

        Calendar now = Calendar.getInstance();
        boolean atCurrentYear = year == now.get(Calendar.YEAR);
        b.btnYearNext.setEnabled(!atCurrentYear);
        b.btnYearNext.setAlpha(atCurrentYear ? 0.3f : 1f);

        StatsAggregator.YearSummary summary = StatsAggregator.yearSummary(books, year);
        int[] monthly = summary.monthly;
        b.tvYearBought.setText(String.valueOf(summary.bought));
        b.tvYearEntered.setText(String.valueOf(summary.entered));
        b.tvYearCostValue.setText(StatsText.formatMoney(summary.cost));

        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            labels.add(String.valueOf(m + 1));
            values.add(monthly[m]);
        }
        b.chartYear.setUnit("本");
        // v4.20：年图用「每月最多买几本」的全局刻度，跨年份也能比
        b.chartYear.setData(labels, values, globalMaxPerMonth);

        // v4.20：全年购买热力图
        int[] yearDays = StatsAggregator.yearDaily(books, year);
        b.heatmapYear.setYearMode(true);
        b.heatmapYear.setYearData(year, yearDays);
        renderHeatmapLegend(b.llYearLegend);
        // 切换年份后重置上一次的「日详情」
        b.tvYearDayDetail.setText(R.string.stats_click_day_hint);
        clearDayBooks(b.tvYearDayBooks);
        // v4.21：切换年份后清掉柱状图高亮
        b.chartYear.setHighlightedIndex(-1);

        // v4.20：本年购买排行（按月降序，没有购买的月份不入榜）
        rankView.render(b.llYearRank, b.tvYearRankTitle, StatsRankView.monthRank(monthly));
    }

    // ==================== 工具 ====================

    /** 热力图图例：少 → 5 级色块 → 多（v4.25） */
    private void renderHeatmapLegend(LinearLayout container) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        addLegendText(container, "少");
        int[] alphas = {0, 70, 120, 175, 255};
        int surfaceDim = ThemeManager.resolveColor(this, R.attr.surface_dim);
        for (int alpha : alphas) {
            View cell = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(alpha == 0 ? surfaceDim : ThemeManager.primaryAlpha(this, alpha));
            bg.setCornerRadius(dp(2));
            cell.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
            lp.setMarginStart(dp(4));
            lp.setMarginEnd(dp(4));
            container.addView(cell, lp);
        }
        addLegendText(container, "多");
    }

    private void addLegendText(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        Typo.text(tv, R.dimen.text_caption);
        tv.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        container.addView(tv);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

}
