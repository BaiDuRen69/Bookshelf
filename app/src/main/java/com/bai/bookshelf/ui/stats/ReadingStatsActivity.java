package com.bai.bookshelf.ui.stats;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
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
import com.bai.bookshelf.databinding.DialogPickPeriodBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 我的阅读：从统计页进入的二级页，含 月 / 年 / 总 三个页签。
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

    /** 当前打开的年/月选择弹窗（选完直接关闭） */
    private androidx.appcompat.app.AlertDialog pickerDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityReadingStatsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

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
        b.tvMonthLabel.setOnClickListener(v -> showMonthPicker());
        b.btnMonthPick.setOnClickListener(v -> showMonthPicker());
        b.tvYearLabel.setOnClickListener(v -> showYearPicker());
        b.btnYearPick.setOnClickListener(v -> showYearPicker());

        // v4.20：点击日历某天，下方显示当天买了几本
        b.heatmapMonth.setOnDayClickListener((year, month, day, count) -> {
            b.tvMonthDayDetail.setText(dayDetailText(year, month, day, count));
        });
        b.heatmapYear.setOnDayClickListener((year, month, day, count) -> {
            b.tvYearDayDetail.setText(dayDetailText(year, month, day, count));
        });

        // v4.21：柱状图右上角「全部记录」按钮 —— 从底部升起半屏面板，列出完整记录
        b.btnMonthDetail.setOnClickListener(v -> showMonthDetailSheet());
        b.btnYearDetail.setOnClickListener(v -> showYearDetailSheet());
        b.btnTotalDetail.setOnClickListener(v -> showTotalDetailSheet());

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
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd == null) {
                continue;
            }
            String dayKey = pd[0] + "-" + pad2(pd[1] + 1) + "-" + pad2(pd[2]);
            perDay.merge(dayKey, 1, Integer::sum);
            allDailyCounts.merge(dayKey, 1, Integer::sum);
            perMonth.merge(pd[0] + "-" + pad2(pd[1] + 1), 1, Integer::sum);
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

    /** 选中态：白底胶囊 + 主题色加粗；未选中：普通文字 */
    private void styleTab(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected ? R.drawable.bg_tab_pill : 0);
        tv.setTextColor(selected
                ? ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)
                : getColor(R.color.text_main));
        tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void render() {
        if (!loaded) {
            return;
        }
        boolean empty = books.isEmpty();
        b.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
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
            renderTotal();
        }
    }

    // ==================== 年/月快速选择器（v4.20） ====================

    /** 弹出月份选择器：上方年份快捷条 + 下方 12 个月格子 */
    private void showMonthPicker() {
        DialogPickPeriodBinding db = DialogPickPeriodBinding.inflate(getLayoutInflater());
        db.hsYearChips.setVisibility(View.VISIBLE);
        db.llYearGrid.setVisibility(View.GONE);
        db.gridMonths.setVisibility(View.VISIBLE);

        int currentYear = monthCursor.get(Calendar.YEAR);
        int currentMonth = monthCursor.get(Calendar.MONTH);
        Calendar now = Calendar.getInstance();
        int nowYear = now.get(Calendar.YEAR);
        int nowMonth = now.get(Calendar.MONTH);

        // 当前选中的年份（点年份 Chip 会改它，随后点月份即跳到该年该月）
        final int[] pickedYear = {currentYear};
        final int primary = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        final int textMain = ContextCompat.getColor(this, R.color.text_main);

        // 12 个月格子
        final TextView[] monthViews = new TextView[]{
                db.m1, db.m2, db.m3, db.m4, db.m5, db.m6,
                db.m7, db.m8, db.m9, db.m10, db.m11, db.m12};

        // 年份变化时刷新月份格子的可用状态（未来月份禁用）与当前选中高亮
        Runnable refreshMonths = () -> {
            for (int i = 0; i < 12; i++) {
                final int m = i;
                TextView tv = monthViews[i];
                boolean future = pickedYear[0] > nowYear
                        || (pickedYear[0] == nowYear && m > nowMonth);
                tv.setEnabled(!future);
                tv.setAlpha(future ? 0.35f : 1f);
                boolean cur = pickedYear[0] == currentYear && m == currentMonth;
                tv.setTextColor(cur ? primary : textMain);
                tv.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                tv.setOnClickListener(v -> {
                    monthCursor.set(pickedYear[0], m, 1);
                    renderMonth();
                    pickerDialog.dismiss();
                });
            }
        };

        // 年份快捷条：数据里出现过的年份 + 当前年，倒序
        List<Integer> years = availableYears();
        ChipGroup group = db.chipYearGroup;
        for (int y : years) {
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(String.valueOf(y));
            chip.setCheckable(true);
            group.addView(chip);
            if (y == currentYear) {
                group.check(chip.getId());
            }
        }
        // ChipGroup 单选：选中哪个年份就更新 pickedYear 并刷新月份格子
        group.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) {
                return;
            }
            View v = g.findViewById(ids.get(0));
            if (v instanceof Chip) {
                try {
                    pickedYear[0] = Integer.parseInt(((Chip) v).getText().toString());
                    refreshMonths.run();
                } catch (NumberFormatException ignored) {
                    // 理论上不会发生，忽略
                }
            }
        });

        refreshMonths.run();

        pickerDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("选择月份")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        pickerDialog.show();
    }

    /** 弹出年份选择器 */
    private void showYearPicker() {
        DialogPickPeriodBinding db = DialogPickPeriodBinding.inflate(getLayoutInflater());
        db.hsYearChips.setVisibility(View.GONE);
        db.gridMonths.setVisibility(View.GONE);
        db.llYearGrid.setVisibility(View.VISIBLE);

        int currentYear = yearCursor.get(Calendar.YEAR);
        List<Integer> years = availableYears();
        // 4 列一行
        int perRow = 4;
        for (int i = 0; i < years.size(); i += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < perRow && i + j < years.size(); j++) {
                final int y = years.get(i + j);
                TextView tv = new TextView(this);
                tv.setText(String.valueOf(y));
                tv.setTextSize(15);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(y == currentYear
                        ? ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)
                        : ContextCompat.getColor(this, R.color.text_main));
                if (y == currentYear) {
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                }
                tv.setBackgroundResource(R.drawable.bg_chip);
                tv.setPadding(dp(8), dp(12), dp(8), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> {
                    yearCursor.set(y, 0, 1);
                    renderYear();
                    pickerDialog.dismiss();
                });
                row.addView(tv);
            }
            db.llYearGrid.addView(row);
        }

        pickerDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("选择年份")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        pickerDialog.show();
    }

    /** 可选年份：书籍购买记录里出现过的年份 ∪ 当前年，倒序 */
    private List<Integer> availableYears() {
        TreeMap<Integer, Boolean> set = new TreeMap<>();
        set.put(Calendar.getInstance().get(Calendar.YEAR), true);
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null) {
                set.put(pd[0], true);
            }
        }
        List<Integer> list = new ArrayList<>(set.keySet());
        Collections.reverse(list);
        return list;
    }

    // ==================== 「全部记录」日历面板（v4.22） ====================

    /**
     * 月视图的「全部记录」：打开日历面板（按月粒度），初始定位到当前游标月。
     *
     * <p>v4.22：面板内的日历展示<b>全部年份</b>的记录，不限于当前月；
     * 右上角可切换「按月 / 按年」，切换后展示对应粒度下的完整记录。
     */
    private void showMonthDetailSheet() {
        int year = monthCursor.get(Calendar.YEAR);
        int month = monthCursor.get(Calendar.MONTH);
        showCalendarSheet(false, year, month);
    }

    /**
     * 年视图的「全部记录」：打开日历面板（按年粒度），初始定位到当前游标年。
     */
    private void showYearDetailSheet() {
        int year = yearCursor.get(Calendar.YEAR);
        showCalendarSheet(true, year, 0);
    }

    /**
     * 总视图的「全部记录」：打开日历面板（按年粒度），定位到有记录的最新年份。
     */
    private void showTotalDetailSheet() {
        int year = latestYearWithRecords();
        showCalendarSheet(true, year, 0);
    }

    /**
     * 打开「全部记录」日历面板。
     *
     * @param yearMode true=按年，false=按月
     * @param year    初始聚焦的年
     * @param month0  初始聚焦的月（0-based）
     */
    private void showCalendarSheet(boolean yearMode, int year, int month0) {
        // 标题的周期后缀由面板自己管理（切换粒度时会跟着变），
        // 这里只传基名「购买日历」。
        String summary = getString(R.string.stats_calendar_summary,
                activeDayCount(), totalBookCount());

        PurchaseCalendarSheet.show(this, getString(R.string.stats_calendar_title),
                summary, allDailyCounts,
                new PurchaseCalendarSheet.Start(yearMode, year, month0),
                (y, m, d, count) -> {
                    // 选中某天：把下方页面的当天详情同步更新
                    b.tvMonthDayDetail.setText(dayDetailText(y, m, d, count));
                    b.tvYearDayDetail.setText(dayDetailText(y, m, d, count));
                });
    }

    /** 有购买记录的天数（全部年份） */
    private int activeDayCount() {
        return allDailyCounts.size();
    }

    /** 有购买记录的书的总数（全部年份） */
    private int totalBookCount() {
        int sum = 0;
        for (int v : allDailyCounts.values()) {
            sum += v;
        }
        return sum;
    }

    /** 最新一个有购买记录的年份（没有记录时返回当前年） */
    private int latestYearWithRecords() {
        int year = 0;
        for (String key : allDailyCounts.keySet()) {
            int[] pd = parseYmd(key);
            if (pd != null && pd[0] > year) {
                year = pd[0];
            }
        }
        return year > 0 ? year : Calendar.getInstance().get(Calendar.YEAR);
    }

    /** 月视图柱状图点击：联动下方的当天详情 */
    private void onChartBarClick(int index, boolean secondHalf) {
        int year = monthCursor.get(Calendar.YEAR);
        int month = monthCursor.get(Calendar.MONTH);
        int days = daysInMonth(year, month);
        int half = (days + 1) / 2;
        int day = secondHalf ? (half + 1 + index) : (index + 1);
        if (day > days) {
            return;
        }
        int[] daily = dailyCounts(year, month);
        b.chartMonth.setHighlightedIndex(secondHalf ? -1 : index);
        b.chartMonth2.setHighlightedIndex(secondHalf ? index : -1);
        b.tvMonthDayDetail.setText(dayDetailText(year, month, day, daily[day - 1]));
    }

    /** 年视图柱状图点击：联动下方的当月详情 */
    private void onYearChartBarClick(int index) {
        int year = yearCursor.get(Calendar.YEAR);
        int[] monthly = monthlyCounts(year);
        b.chartYear.setHighlightedIndex(index);
        b.tvYearDayDetail.setText(String.format(Locale.getDefault(),
                "%d年%d月 · 购买 %d 本", year, index + 1, monthly[index]));
    }

    /** 某年某月每天的购买数量（下标 0 = 1 日） */
    private int[] dailyCounts(int year, int month) {
        int[] out = new int[daysInMonth(year, month)];
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && pd[1] == month) {
                out[pd[2] - 1]++;
            }
        }
        return out;
    }

    /** 某年每月购买数量（下标 0 = 1 月） */
    private int[] monthlyCounts(int year) {
        int[] out = new int[12];
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year) {
                out[pd[1]]++;
            }
        }
        return out;
    }

    /** 星期几（周一/周二…），用于明细行的副标题 */
    private static String weekLabel(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day);
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        // Calendar.DAY_OF_WEEK：1=周日 … 7=周六 → 转成「周一为 0」
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        return names[dow];
    }

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

        int bought = 0;
        int entered = 0;
        double cost = 0;
        int days = daysInMonth(year, month);
        int[] daily = new int[days];
        // v3.0：createdAt 的 Calendar 只创建一次并复用，避免每本书都 new 一次
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && pd[1] == month) {
                bought++;
                daily[pd[2] - 1]++;
                // v4.20：花费只统计自购（排除赠送 OWN_GIFT / 借阅 OWN_BORROW）
                if (book.ownType == Book.OWN_BUY) {
                    cost += book.purchasePrice;
                }
            }
            created.setTimeInMillis(book.createdAt);
            if (created.get(Calendar.YEAR) == year && created.get(Calendar.MONTH) == month) {
                entered++;
            }
        }
        b.tvMonthSummary.setText(String.format(Locale.getDefault(),
                "本月购买 %d 本 · 录入 %d 本", bought, entered));
        b.tvMonthCost.setText("本月花费 " + formatMoney(cost));

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
        // 切换月份后重置上一次的「日详情」，避免残留上一个月的点击结果
        b.tvMonthDayDetail.setText(R.string.stats_click_day_hint);
        // v4.21：切换月份后清掉柱状图高亮
        b.chartMonth.setHighlightedIndex(-1);
        b.chartMonth2.setHighlightedIndex(-1);

        // v4.20：本月购买排行（按天降序，没有购买的天不入榜）
        renderRank(b.llMonthRank, b.tvMonthRankTitle, buildDayRank(year, month, daily));
    }

    /** 月排行：把有购买的日子按数量降序排列 */
    private List<RankEntry> buildDayRank(int year, int month, int[] daily) {
        List<RankEntry> list = new ArrayList<>();
        for (int i = 0; i < daily.length; i++) {
            if (daily[i] > 0) {
                list.add(new RankEntry(String.format(Locale.getDefault(), "%d月%d日", month + 1, i + 1),
                        daily[i]));
            }
        }
        list.sort((a, b) -> b.count - a.count);
        return list;
    }

    // ==================== 年视图 ====================

    private void renderYear() {
        int year = yearCursor.get(Calendar.YEAR);
        b.tvYearLabel.setText(String.format(Locale.getDefault(), "%d年", year));

        Calendar now = Calendar.getInstance();
        boolean atCurrentYear = year == now.get(Calendar.YEAR);
        b.btnYearNext.setEnabled(!atCurrentYear);
        b.btnYearNext.setAlpha(atCurrentYear ? 0.3f : 1f);

        int bought = 0;
        int entered = 0;
        double cost = 0;
        int[] monthly = new int[12];
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year) {
                bought++;
                monthly[pd[1]]++;
                if (book.ownType == Book.OWN_BUY) {
                    cost += book.purchasePrice;
                }
            }
            created.setTimeInMillis(book.createdAt);
            if (created.get(Calendar.YEAR) == year) {
                entered++;
            }
        }
        b.tvYearSummary.setText(String.format(Locale.getDefault(),
                "本年购买 %d 本 · 录入 %d 本", bought, entered));
        b.tvYearCost.setText("本年花费 " + formatMoney(cost));

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
        int[] yearDays = buildYearDaily(year);
        b.heatmapYear.setYearMode(true);
        b.heatmapYear.setYearData(year, yearDays);
        // 切换年份后重置上一次的「日详情」
        b.tvYearDayDetail.setText(R.string.stats_click_day_hint);
        // v4.21：切换年份后清掉柱状图高亮
        b.chartYear.setHighlightedIndex(-1);

        // v4.20：本年购买排行（按月降序，没有购买的月份不入榜）
        List<RankEntry> list = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            if (monthly[m] > 0) {
                list.add(new RankEntry((m + 1) + "月", monthly[m]));
            }
        }
        list.sort((a, b) -> b.count - a.count);
        renderRank(b.llYearRank, b.tvYearRankTitle, list);
    }

    /** 构造整年每天的购买数（下标 0 = 1 月 1 日） */
    private int[] buildYearDaily(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, 0, 1);
        int len = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        int[] out = new int[len];
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd == null || pd[0] != year) {
                continue;
            }
            Calendar d = Calendar.getInstance();
            d.clear();
            d.set(pd[0], pd[1], pd[2]);
            int idx = d.get(Calendar.DAY_OF_YEAR) - 1;
            if (idx >= 0 && idx < out.length) {
                out[idx]++;
            }
        }
        return out;
    }

    // ==================== 排行渲染 ====================

    /** 排行条目 */
    private static class RankEntry {
        final String label;
        final int count;

        RankEntry(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    /**
     * 渲染排行列表。
     *
     * <p>没有任何数据时整块隐藏（标题 + 列表都不显示），符合「没有就不入排行」的要求。
     */
    private void renderRank(LinearLayout container, TextView title, List<RankEntry> entries) {
        container.removeAllViews();
        if (entries.isEmpty()) {
            title.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        title.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);
        int max = entries.get(0).count;
        int primary = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        int rank = 1;
        for (RankEntry e : entries) {
            container.addView(buildRankRow(rank++, e, max, primary));
        }
    }

    /** 排行行：名次 + 名称 + 权重条 + 数量 */
    private View buildRankRow(int rank, RankEntry e, int max, int primary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(R.drawable.bg_chip);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));

        TextView tvRank = new TextView(this);
        tvRank.setText(String.valueOf(rank));
        tvRank.setTextSize(12);
        tvRank.setGravity(Gravity.CENTER);
        tvRank.setTextColor(primary);
        row.addView(tvRank, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvName = new TextView(this);
        tvName.setText(e.label);
        tvName.setTextSize(13);
        tvName.setSingleLine(true);
        row.addView(tvName, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 权重条
        LinearLayout barArea = new LinearLayout(this);
        barArea.setOrientation(LinearLayout.HORIZONTAL);
        barArea.setWeightSum(max);
        View bar = new View(this);
        bar.setBackgroundResource(R.drawable.bg_stat_bar);
        bar.getBackground().setTint(primary);
        barArea.addView(bar, new LinearLayout.LayoutParams(0, dp(14), e.count));
        if (max > e.count) {
            barArea.addView(new View(this),
                    new LinearLayout.LayoutParams(0, dp(14), max - e.count));
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        barLp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(barArea, barLp);

        TextView tvCount = new TextView(this);
        tvCount.setText(e.count + " 本");
        tvCount.setTextSize(13);
        tvCount.setTypeface(Typeface.DEFAULT_BOLD);
        tvCount.setGravity(Gravity.END);
        row.addView(tvCount, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    // ==================== 总视图 ====================

    private void renderTotal() {
        int bought = 0;
        int done = 0;
        int reading = 0;
        int none = 0;
        String firstPurchase = null;
        for (Book book : books) {
            if (book.purchaseDate != null && !book.purchaseDate.trim().isEmpty()) {
                bought++;
                if (firstPurchase == null || book.purchaseDate.trim().compareTo(firstPurchase) < 0) {
                    firstPurchase = book.purchaseDate.trim();
                }
            }
            if (book.readStatus == Book.READ_DONE) {
                done++;
            } else if (book.readStatus == Book.READING) {
                reading++;
            } else {
                none++;
            }
        }
        int entered = books.size();
        // v2.9：数字单独大字展示（标签在布局里），这里只填数字
        b.tvTotalBought.setText(String.valueOf(bought));
        b.tvTotalEntered.setText(String.valueOf(entered));
        b.tvTotalDone.setText(String.valueOf(done));
        b.tvTotalReading.setText(String.valueOf(reading));
        b.tvTotalNone.setText(String.valueOf(none));

        int[] first = parseYmd(firstPurchase);
        if (first == null) {
            b.tvTotalDuration.setText("暂无购买记录");
            b.tvTotalRange.setText("录入书籍时填写购买日期后即可统计");
            b.tvTotalCompanion.setVisibility(View.GONE);
            b.chartTotal.setData(new ArrayList<>(), new ArrayList<>());
            renderCompanionLine(); // 无购买记录也展示「相伴」
            return;
        }

        Calendar firstCal = Calendar.getInstance();
        firstCal.clear();
        firstCal.set(first[0], first[1], first[2]);
        long now = System.currentTimeMillis();
        long elapsedMillis = Math.max(0, now - firstCal.getTimeInMillis());

        // v2.9：大字改为「已阅读 X 小时 X 分钟」（首购至今换算，数字放大突出）
        long hours = elapsedMillis / 3600000L;
        long minutes = (elapsedMillis / 60000L) % 60;
        b.tvTotalDuration.setText(buildDurationText(hours, minutes));

        // 小字 1：首购至今多少天
        b.tvTotalRange.setText(String.format(Locale.getDefault(),
                "%s至今，已阅读 %d 天", formatYmd(first[0], first[1] + 1, first[2]),
                elapsedMillis / 86400000L));

        // 小字 2：首次打开 App 至今，与小书房相伴多少天
        renderCompanionLine();

        // 每年购买本数
        TreeMap<Integer, Integer> yearly = new TreeMap<>();
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null) {
                yearly.merge(pd[0], 1, Integer::sum);
            }
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : yearly.entrySet()) {
            labels.add(String.valueOf(e.getKey()));
            values.add(e.getValue());
        }
        b.chartTotal.setUnit("本");
        b.chartTotal.setData(labels, values);
        // v4.21：切换页签时清掉高亮
        b.chartTotal.setHighlightedIndex(-1);
    }

    /** 「首次打开 App 至今 · 与小书房相伴了 N 天」（v2.9；first_launch_at 由 BookshelfApp 持久化） */
    private void renderCompanionLine() {
        long firstLaunch = BookshelfApp.firstLaunchAt();
        if (firstLaunch <= 0) {
            b.tvTotalCompanion.setVisibility(View.GONE);
            return;
        }
        b.tvTotalCompanion.setVisibility(View.VISIBLE);
        Calendar firstCal = Calendar.getInstance();
        firstCal.setTimeInMillis(firstLaunch);
        long days = Math.max(0,
                (System.currentTimeMillis() - firstLaunch) / 86400000L);
        b.tvTotalCompanion.setText(String.format(Locale.getDefault(),
                "%s至今 · 与小书房相伴了 %d 天",
                formatYmd(firstCal.get(Calendar.YEAR), firstCal.get(Calendar.MONTH) + 1,
                        firstCal.get(Calendar.DAY_OF_MONTH)), days));
    }

    /** 「已阅读 X 小时 X 分钟」：单位 18sp、数字 32sp 加粗，突出显示 */
    private static CharSequence buildDurationText(long hours, long minutes) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(span("已阅读 ", 18, false));
        sb.append(span(String.valueOf(hours), 32, true));
        sb.append(span(" 小时 ", 18, false));
        sb.append(span(String.valueOf(minutes), 32, true));
        sb.append(span(" 分钟", 18, false));
        return sb;
    }

    private static CharSequence span(String s, int spSize, boolean bold) {
        SpannableString ss = new SpannableString(s);
        ss.setSpan(new AbsoluteSizeSpan(spSize, true),
                0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            ss.setSpan(new StyleSpan(Typeface.BOLD),
                    0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    private static String formatYmd(int y, int m, int d) {
        return y + "年" + m + "月" + d + "日";
    }

    // ==================== 工具 ====================

    /** 点击日历某天后的详情文案（v4.20） */
    private String dayDetailText(int year, int month0, int day, int count) {
        String week = weekLabel(year, month0, day);
        if (count > 0) {
            return String.format(Locale.getDefault(), "%d年%d月%d日 %s · 购买 %d 本",
                    year, month0 + 1, day, week, count);
        }
        return String.format(Locale.getDefault(), "%d年%d月%d日 %s · 没有购买记录",
                year, month0 + 1, day, week);
    }

    /** 金额展示：¥123.45 / ¥123（去掉多余的 .00） */
    private static String formatMoney(double v) {
        if (v <= 0) {
            return "¥0";
        }
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return "¥" + String.valueOf((long) v);
        }
        return String.format(Locale.getDefault(), "¥%.2f", v);
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * 解析 yyyy-MM-dd；非法或空返回 null。返回 {年, 月(0-based), 日}。
     * v3.0：显式校验分隔符，避免 2024/05/06、2024-0506 这类脏数据被误解析成合法日期。
     */
    private static int[] parseYmd(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() < 10 || t.charAt(4) != '-' || t.charAt(7) != '-') {
            return null;
        }
        try {
            int y = Integer.parseInt(t.substring(0, 4));
            int m = Integer.parseInt(t.substring(5, 7));
            int d = Integer.parseInt(t.substring(8, 10));
            if (m < 1 || m > 12 || d < 1 || d > 31) {
                return null;
            }
            return new int[]{y, m - 1, d};
        } catch (Exception e) {
            return null;
        }
    }

    private static int daysInMonth(int year, int month0) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
