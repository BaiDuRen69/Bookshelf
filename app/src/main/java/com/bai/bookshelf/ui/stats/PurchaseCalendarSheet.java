package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.SheetPurchaseCalendarBinding;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「全部记录」日历面板（v4.22）。
 *
 * <p>参照微信读书「阅读天数」面板（用户提供的截图）：
 * <ul>
 *   <li>顶部标题行左侧是「购买日历 · 2026/7」，<b>右上角有「按月 / 按年」分段切换</b>；</li>
 *   <li>「按月」时下面按月分块，每块是「2026年7月 · 累计购买 12 本」+ 该月网格，
 *       日期数字画在色块<b>上方</b>；</li>
 *   <li>「按年」时按年分块，每块是「2026年 · 累计购买 88 本」+ 12 个月的缩略网格；</li>
 *   <li>底部固定一条是选中某天的详情（左侧主色竖线 + 数值 + 日期）。</li>
 * </ul>
 *
 * <p><b>v4.22 修正的数据范围</b>：v4.20 的热力图只喂「当前游标月/年」，
 * 日历因此被限制在当前或近一年数据。本面板改为一次性汇总
 * <b>全部年份</b>的每日购买量，再按「按月 / 按年」两种粒度切分展示；
 * 切换粒度后展示的正是该粒度范围内的完整记录。
 */
public class PurchaseCalendarSheet {

    /** 选中某天的回调 */
    public interface OnDaySelectListener {
        /**
         * @param year   年
         * @param month0 月（0-based）
         * @param day    日（1-based）
         * @param count  当天购买本数
         */
        void onDaySelect(int year, int month0, int day, int count);
    }

    /** 面板初始定位参数 */
    public static class Start {
        /** 初始粒度：true=按年，false=按月 */
        public final boolean yearMode;
        /** 初始聚焦的年 */
        public final int year;
        /** 初始聚焦的月（0-based） */
        public final int month0;

        public Start(boolean yearMode, int year, int month0) {
            this.yearMode = yearMode;
            this.year = year;
            this.month0 = month0;
        }
    }

    private static final int MODE_MONTH = 0;
    private static final int MODE_YEAR = 1;

    /**
     * 显示「全部记录」日历面板。
     *
     * @param context     上下文（需是 Activity）
     * @param title       面板标题
     * @param summary     副标题（汇总说明），可为 null
     * @param dailyCounts <b>全部年份</b>的每日购买量，键为 yyyy-MM-dd
     * @param start       初始粒度与聚焦周期
     * @param listener    选中某天的回调，可为 null
     */
    public static BottomSheetDialog show(Context context, String title, @Nullable String summary,
                                        Map<String, Integer> dailyCounts, Start start,
                                        @Nullable OnDaySelectListener listener) {
        return new Impl(context, title, summary, dailyCounts, start, listener).show();
    }

    /**
     * 面板实现。用内部类把状态（当前粒度、聚焦周期）收在一起，
     * 避免多个 Runnable 之间互相引用带来的前向引用问题。
     */
    private static final class Impl {

        private final Context context;
        private final SheetPurchaseCalendarBinding b;
        /** 标题基名（如「购买日历」），实际标题会带上当前聚焦周期 */
        private final String titleBase;
        @Nullable
        private final String summary;
        private final Map<String, Integer> dailyCounts;
        private final Start start;
        @Nullable
        private final OnDaySelectListener listener;

        private final int primary;
        private final int textMain;
        private final int textSub;

        /** 当前粒度：MODE_MONTH / MODE_YEAR */
        private int mode;
        /** 当前聚焦的年 */
        private int focusYear;
        /** 当前聚焦的月（0-based） */
        private int focusMonth;

        /** 全部年份切分出来的月块 / 年块（倒序） */
        private final List<Block> monthBlocks;
        private final List<Block> yearBlocks;

        /** 当前创建出来的热力图（用于统一清除选中描边） */
        private final List<PurchaseHeatmapView> heatmaps = new ArrayList<>();

        private Impl(Context context, String title, @Nullable String summary,
                     Map<String, Integer> dailyCounts, Start start,
                     @Nullable OnDaySelectListener listener) {
            this.context = context;
            this.titleBase = title;
            this.summary = summary;
            this.dailyCounts = dailyCounts;
            this.start = start;
            this.listener = listener;
            this.b = SheetPurchaseCalendarBinding.inflate(LayoutInflater.from(context));
            this.primary = ThemeManager.resolveColor(context, ThemeManager.ATTR_COLOR_PRIMARY);
            this.textMain = ContextCompat.getColor(context, R.color.text_main);
            this.textSub = ContextCompat.getColor(context, R.color.text_sub);
            this.mode = start.yearMode ? MODE_YEAR : MODE_MONTH;
            this.focusYear = start.year;
            this.focusMonth = start.month0;
            this.monthBlocks = buildMonthBlocks(dailyCounts);
            this.yearBlocks = buildYearBlocks(dailyCounts);
        }

        BottomSheetDialog show() {
            BottomSheetDialog dialog = new BottomSheetDialog(context);
            dialog.setContentView(b.getRoot());

            renderTitle();
            if (summary != null && !summary.isEmpty()) {
                b.sheetSummary.setText(summary);
                b.sheetSummary.setVisibility(View.VISIBLE);
            } else {
                b.sheetSummary.setVisibility(View.GONE);
            }
            // 固定星期表头：只在按月模式显示
            b.heatmapWeekHeader.setHeaderOnly(true);
            b.heatmapWeekHeader.setYearMode(false);

            b.tvModeMonth.setOnClickListener(v -> switchMode(MODE_MONTH));
            b.tvModeYear.setOnClickListener(v -> switchMode(MODE_YEAR));
            b.sheetClose.setOnClickListener(v -> dialog.dismiss());

            renderModeSwitch();
            renderCalendar();

            // ⚠ 日历区必须限定高度，否则全部年份的月块会把内容撑得极高，
            //   底部的「当天详情条」会被挤出屏幕看不见。
            //   android:maxHeight 对 NestedScrollView 无效，只能代码里设 LayoutParams，
            //   且必须在 dialog.show()（触发测量）之前设，否则测量已过、设置不生效。
            int screenH = context.getResources().getDisplayMetrics().heightPixels;
            if (screenH > 0) {
                ViewGroup.LayoutParams lp = b.calendarScroll.getLayoutParams();
                lp.height = (int) (screenH * 0.52f);
                b.calendarScroll.setLayoutParams(lp);
            }

            dialog.show();

            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                int h = dialog.getWindow() != null
                        && dialog.getWindow().getDecorView() != null
                        ? dialog.getWindow().getDecorView().getHeight() : 0;
                if (h > 0) {
                    behavior.setMaxHeight((int) (h * 0.92f));
                    behavior.setPeekHeight((int) (h * 0.72f));
                }
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            return dialog;
        }

        /**
         * 刷新标题：标题跟着当前粒度与聚焦周期走。
         *
         * <p>按月 → 「购买日历 · 2026/10」；按年 → 「购买日历 · 2026」。
         */
        private void renderTitle() {
            String period = mode == MODE_YEAR
                    ? String.valueOf(focusYear)
                    : String.format(Locale.getDefault(), "%d/%d", focusYear, focusMonth + 1);
            b.sheetTitle.setText(titleBase + " · " + period);
        }

        /** 切换粒度：按月 ↔ 按年 */
        private void switchMode(int newMode) {
            if (mode == newMode) {
                return;
            }
            mode = newMode;
            // 切到按年时，聚焦年取当前聚焦月的所属年
            renderModeSwitch();
            renderTitle();
            renderCalendar();
            resetDayDetail();
        }

        /** 刷新「按月 / 按年」切换控件的选中态 */
        private void renderModeSwitch() {
            boolean monthSelected = mode == MODE_MONTH;
            b.tvModeMonth.setBackgroundResource(monthSelected
                    ? R.drawable.bg_seg_selected : 0);
            b.tvModeMonth.setTextColor(monthSelected ? primary : textSub);
            b.tvModeMonth.setTypeface(monthSelected
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.tvModeYear.setBackgroundResource(!monthSelected
                    ? R.drawable.bg_seg_selected : 0);
            b.tvModeYear.setTextColor(!monthSelected ? primary : textSub);
            b.tvModeYear.setTypeface(!monthSelected
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            // 星期表头只在按月模式有意义
            b.llWeekHeader.setVisibility(monthSelected ? View.VISIBLE : View.GONE);
        }

        /** 渲染全部年份的日历块 */
        private void renderCalendar() {
            List<Block> blocks = mode == MODE_YEAR ? yearBlocks : monthBlocks;
            b.llCalendarBlocks.removeAllViews();
            heatmaps.clear();
            if (blocks.isEmpty()) {
                b.tvCalendarEmpty.setVisibility(View.VISIBLE);
                b.calendarScroll.setVisibility(View.GONE);
                return;
            }
            b.tvCalendarEmpty.setVisibility(View.GONE);
            b.calendarScroll.setVisibility(View.VISIBLE);
            for (Block block : blocks) {
                b.llCalendarBlocks.addView(buildBlock(block));
            }
            // 定位到聚焦周期
            scrollToFocus();
        }

        /**
         * 构造一个日历块。
         *
         * <p>按月：月份标题（年-月 + 累计本数）+ 月网格（数字在色块上方，微信读书样式）。
         * <p>按年：年份标题（年份 + 累计本数）+ 12 个月的缩略网格。
         */
        private View buildBlock(Block block) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            boxLp.bottomMargin = dp(20);
            box.setLayoutParams(boxLp);

            // 标题：2026年10月 · 累计购买 4 本
            TextView title = new TextView(context);
            if (mode == MODE_YEAR) {
                title.setText(context.getString(R.string.stats_calendar_block_year,
                        block.year, block.total));
            } else {
                title.setText(context.getString(R.string.stats_calendar_block_month,
                        block.year, block.month0 + 1, block.total));
            }
            title.setTextColor(textMain);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(title);

            // 副标题：有购买记录的天数
            TextView sub = new TextView(context);
            sub.setText(context.getString(R.string.stats_calendar_active_days, block.activeDays));
            sub.setTextColor(textSub);
            sub.setTextSize(12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            subLp.bottomMargin = dp(6);
            box.addView(sub, subLp);

            PurchaseHeatmapView heat = new PurchaseHeatmapView(context);
            if (mode == MODE_YEAR) {
                heat.setMiniYearData(block.year, block.monthly);
                heat.setShowNumbers(false);
                heat.setOnMonthClickListener((year, month) -> {
                    // 点迷你年里的某个月 → 切回按月模式并聚焦该月
                    focusYear = year;
                    focusMonth = month;
                    mode = MODE_MONTH;
                    renderModeSwitch();
                    renderTitle();
                    renderCalendar();
                });
            } else {
                heat.setMonthData(block.year, block.month0, block.daily);
                // 星期表头在面板顶部固定，块内不再重复画
                heat.setShowHeader(false);
                // 数字画在色块上方（微信读书样式）
                heat.setLabelOutside(true);
            }
            heatmaps.add(heat);

            // 选中某天：清掉其他块的描边 → 高亮 → 底部详情
            heat.setOnDayClickListener((year, month, day, count) -> {
                for (PurchaseHeatmapView other : heatmaps) {
                    if (other != heat) {
                        other.setSelectedDay(-1, -1, -1);
                    }
                }
                heat.setSelectedDay(year, month, day);
                // 聚焦周期跟着选中的那天走，切换粒度时能定位到正确的周期
                focusYear = year;
                focusMonth = month;
                showDayDetail(year, month, day, count);
                if (listener != null) {
                    listener.onDaySelect(year, month, day, count);
                }
            });

            box.addView(heat, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return box;
        }

        /** 滚动到当前聚焦的周期 */
        private void scrollToFocus() {
            b.calendarScroll.post(() -> {
                int index = -1;
                List<Block> blocks = mode == MODE_YEAR ? yearBlocks : monthBlocks;
                for (int i = 0; i < blocks.size(); i++) {
                    Block blk = blocks.get(i);
                    if (mode == MODE_YEAR) {
                        if (blk.year == focusYear) {
                            index = i;
                            break;
                        }
                    } else if (blk.year == focusYear && blk.month0 == focusMonth) {
                        index = i;
                        break;
                    }
                }
                if (index < 0 || index >= b.llCalendarBlocks.getChildCount()) {
                    return;
                }
                View target = b.llCalendarBlocks.getChildAt(index);
                b.calendarScroll.scrollTo(0, Math.max(0, target.getTop()));
            });
        }

        /** 底部详情条显示某天的信息 */
        private void showDayDetail(int year, int month0, int day, int count) {
            b.llDayDetail.setVisibility(View.VISIBLE);
            if (count > 0) {
                b.tvDayValue.setText(context.getString(R.string.stats_day_bought, count));
            } else {
                b.tvDayValue.setText(R.string.stats_day_none);
            }
            b.tvDayDate.setVisibility(View.VISIBLE);
            // 按年模式下同一年内有多个月份，只写「M月D日」会分不清是哪一年，
            // 因此年模式补上年份。
            if (mode == MODE_YEAR) {
                b.tvDayDate.setText(String.format(Locale.getDefault(), "%d年%d月%d日",
                        year, month0 + 1, day));
            } else {
                b.tvDayDate.setText(String.format(Locale.getDefault(), "%d月%d日",
                        month0 + 1, day));
            }
        }

        /** 重置底部详情条为提示文案 */
        private void resetDayDetail() {
            b.llDayDetail.setVisibility(View.VISIBLE);
            b.tvDayValue.setText(R.string.stats_click_day_hint);
            b.tvDayDate.setVisibility(View.GONE);
            for (PurchaseHeatmapView heat : heatmaps) {
                heat.setSelectedDay(-1, -1, -1);
            }
        }

        private int dp(int v) {
            return (int) (v * context.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * 一个日历块：某个月或某一年。
     */
    static class Block {
        final int year;
        /** 0-based 月；年块为 -1 */
        final int month0;
        /** 月块：该块内的每日购买数（下标 0 = 1 日） */
        final int[] daily;
        /** 年块：12 个月每天的购买数（外层下标 = 月，内层下标 = 日-1） */
        final int[][] monthly;
        /** 该块合计本数 */
        final int total;
        /** 该块内有购买的天数 */
        final int activeDays;

        Block(int year, int month0, int[] daily, int[][] monthly) {
            this.year = year;
            this.month0 = month0;
            this.daily = daily;
            this.monthly = monthly;
            int sum = 0;
            int act = 0;
            if (daily != null) {
                for (int v : daily) {
                    sum += v;
                    if (v > 0) {
                        act++;
                    }
                }
            }
            if (monthly != null) {
                for (int[] arr : monthly) {
                    if (arr == null) {
                        continue;
                    }
                    for (int v : arr) {
                        sum += v;
                        if (v > 0) {
                            act++;
                        }
                    }
                }
            }
            this.total = sum;
            this.activeDays = act;
        }
    }

    /**
     * 把每日购买量切分成「按月」的块列表（覆盖全部年份，按时间倒序）。
     *
     * @param dailyCounts 每日购买量，键为 yyyy-MM-dd
     */
    static List<Block> buildMonthBlocks(Map<String, Integer> dailyCounts) {
        TreeMap<Integer, TreeMap<Integer, int[]>> byYearMonth = new TreeMap<>();
        for (Map.Entry<String, Integer> e : dailyCounts.entrySet()) {
            int[] ymd = parseYmd(e.getKey());
            if (ymd == null) {
                continue;
            }
            TreeMap<Integer, int[]> months =
                    byYearMonth.computeIfAbsent(ymd[0], k -> new TreeMap<>());
            int[] arr = months.get(ymd[1]);
            if (arr == null) {
                arr = new int[daysInMonth(ymd[0], ymd[1])];
                months.put(ymd[1], arr);
            }
            arr[ymd[2] - 1] += e.getValue();
        }
        List<Block> list = new ArrayList<>();
        for (Map.Entry<Integer, TreeMap<Integer, int[]>> ye : byYearMonth.entrySet()) {
            for (Map.Entry<Integer, int[]> me : ye.getValue().entrySet()) {
                list.add(new Block(ye.getKey(), me.getKey(), me.getValue(), null));
            }
        }
        // 倒序：最近的月份排在最前面
        list.sort((a, b) -> {
            if (a.year != b.year) {
                return Integer.compare(b.year, a.year);
            }
            return Integer.compare(b.month0, a.month0);
        });
        return list;
    }

    /**
     * 把每日购买量切分成「按年」的块列表（覆盖全部年份，倒序）。
     */
    static List<Block> buildYearBlocks(Map<String, Integer> dailyCounts) {
        TreeMap<Integer, int[][]> byYear = new TreeMap<>();
        for (Map.Entry<String, Integer> e : dailyCounts.entrySet()) {
            int[] ymd = parseYmd(e.getKey());
            if (ymd == null) {
                continue;
            }
            int[][] months = byYear.get(ymd[0]);
            if (months == null) {
                months = new int[12][];
                for (int m = 0; m < 12; m++) {
                    months[m] = new int[daysInMonth(ymd[0], m)];
                }
                byYear.put(ymd[0], months);
            }
            months[ymd[1]][ymd[2] - 1] += e.getValue();
        }
        List<Block> list = new ArrayList<>();
        for (Map.Entry<Integer, int[][]> e : byYear.entrySet()) {
            list.add(new Block(e.getKey(), -1, null, e.getValue()));
        }
        list.sort((a, b) -> Integer.compare(b.year, a.year));
        return list;
    }

    private static int[] parseYmd(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
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
