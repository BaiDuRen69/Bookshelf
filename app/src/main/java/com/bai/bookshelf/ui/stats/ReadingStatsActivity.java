package com.bai.bookshelf.ui.stats;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityReadingStatsBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 我的阅读（v2.8）：从统计页进入的二级页，含 月 / 年 / 总 三个页签。
 * - 月：可切换月份，展示当月购买/录入本数 + 每日购买本数条形图；
 * - 年：可切换年份，展示当年购买/录入本数 + 每月购买本数条形图；
 * - 总：从第一本书购买日起至今的相伴时长 + 买/录入/已读/未读/在读总量 + 每年购买本数条形图。
 * 数据量小（个人藏书），一次全量加载后在内存里按页签聚合。
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

        selectTab(0);
        loadData();
    }

    private void loadData() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            books.clear();
            books.addAll(BookSanitizer.normalizeAll(app.db().bookDao().getAll()));
            loaded = true;
            app.ui(this::render);
        });
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
        int days = daysInMonth(year, month);
        int[] daily = new int[days];
        // v3.0：createdAt 的 Calendar 只创建一次并复用，避免每本书都 new 一次
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && pd[1] == month) {
                bought++;
                daily[pd[2] - 1]++;
            }
            created.setTimeInMillis(book.createdAt);
            if (created.get(Calendar.YEAR) == year && created.get(Calendar.MONTH) == month) {
                entered++;
            }
        }
        b.tvMonthSummary.setText(String.format(Locale.getDefault(),
                "本月购买 %d 本 · 录入 %d 本", bought, entered));

        // v2.9：每日图表拆两行——上半月 / 下半月，避免 31 根柱挤在一行
        int half = (days + 1) / 2;
        List<String> labels1 = new ArrayList<>();
        List<Integer> values1 = new ArrayList<>();
        for (int d = 1; d <= half; d++) {
            labels1.add(String.valueOf(d));
            values1.add(daily[d - 1]);
        }
        b.chartMonth.setData(labels1, values1);

        if (half < days) {
            List<String> labels2 = new ArrayList<>();
            List<Integer> values2 = new ArrayList<>();
            for (int d = half + 1; d <= days; d++) {
                labels2.add(String.valueOf(d));
                values2.add(daily[d - 1]);
            }
            b.chartMonth2.setVisibility(View.VISIBLE);
            b.chartMonth2.setData(labels2, values2);
        } else {
            b.chartMonth2.setVisibility(View.GONE);
        }
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
        int[] monthly = new int[12];
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year) {
                bought++;
                monthly[pd[1]]++;
            }
            created.setTimeInMillis(book.createdAt);
            if (created.get(Calendar.YEAR) == year) {
                entered++;
            }
        }
        b.tvYearSummary.setText(String.format(Locale.getDefault(),
                "本年购买 %d 本 · 录入 %d 本", bought, entered));

        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            labels.add(String.valueOf(m + 1));
            values.add(monthly[m]);
        }
        b.chartYear.setData(labels, values);
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
        b.chartTotal.setData(labels, values);
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
