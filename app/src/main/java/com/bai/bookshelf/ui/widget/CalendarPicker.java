package com.bai.bookshelf.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

import java.util.Calendar;
import java.util.Locale;

/**
 * 日历选择器（v4.40）：单日 / 日期区间两种模式，底部面板样式，跟随主题。
 *
 * <p>为什么替换掉年/月/日三滚轮：滚轮需要逐列滚动三次再确认，选一个区间要
 * 打开两次滚轮、滚 6 次、点 4 下；日历是「点一下开始、点一下结束」，
 * 区间筛选还给了「本月 / 近3月 / 今年 / 去年」四个一键预设。
 *
 * <p>层级：月视图 → 点标题进月网格 → 再点标题进年网格（‹ › 翻页），
 * 任何月份最多 4 次点击可达。所有控件代码构建，颜色走主题语义色，
 * 夜读主题下同样是纸色面板 + 暗金选中。
 */
public final class CalendarPicker {

    /** 区间选择回调（key = yyyyMMdd） */
    public interface OnRangePicked {
        void onPick(int startKey, int endKey);
    }

    /** 单日选择回调（key = yyyyMMdd） */
    public interface OnDayPicked {
        void onPick(int dayKey);
    }

    private CalendarPicker() {
    }

    /**
     * 区间模式（筛选）。
     *
     * @param startKey 当前起始日（0 = 不限）
     * @param endKey   当前结束日（0 = 不限）
     * @param onPick   应用回调
     * @param onClear  清除回调（为 null 时不显示清除）
     */
    public static void showRange(Activity activity, String title, int startKey, int endKey,
                                 OnRangePicked onPick, Runnable onClear) {
        new Sheet(activity, title, true, startKey, endKey, onPick, null, onClear).show();
    }

    /** 单日模式（编辑页购买日期）：点一天即选中并关闭 */
    public static void showSingle(Activity activity, String title, int dayKey,
                                  OnDayPicked onPick, Runnable onClear) {
        new Sheet(activity, title, false, dayKey, 0, null, onPick, onClear).show();
    }

    private static final int MODE_DAY = 0;
    private static final int MODE_MONTH = 1;
    private static final int MODE_YEAR = 2;

    private static final class Sheet {

        private final Activity activity;
        private final boolean range;
        private final String title;
        private final OnRangePicked rangeCb;
        private final OnDayPicked dayCb;
        private final Runnable onClear;

        /** 已选区间（key = yyyyMMdd，0 = 未选） */
        private int selStart;
        private int selEnd;
        private final int initialKey;

        private int focusYear;
        private int focusMonth0;
        private int mode = MODE_DAY;
        private int pageYearStart;

        private final int primary;
        private final int onPrimary;
        private final int onSurface;
        private final int onVariant;
        private final float density;

        /** 月份 / 年份格子的选中底（日期格子是自绘的，不用它） */
        private final GradientDrawable selectedRectBg = new GradientDrawable();

        private Dialog dialog;
        private TextView headerTitle;
        private TextView statusView;
        private LinearLayout weekRow;
        private LinearLayout grid;
        private TextView clearBtn;
        private TextView applyBtn;

        Sheet(Activity activity, String title, boolean range, int startKey, int endKey,
              OnRangePicked rangeCb, OnDayPicked dayCb, Runnable onClear) {
            this.activity = activity;
            this.title = title;
            this.range = range;
            this.rangeCb = rangeCb;
            this.dayCb = dayCb;
            this.onClear = onClear;
            this.density = activity.getResources().getDisplayMetrics().density;

            Context ctx = activity;
            primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            onVariant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);

            initialKey = startKey;
            if (range) {
                selStart = startKey;
                selEnd = endKey;
            } else {
                selStart = startKey;
                selEnd = startKey;
            }
            Calendar now = Calendar.getInstance();
            int anchor = startKey > 0 ? startKey : keyOf(now);
            focusYear = anchor / 10000;
            focusMonth0 = anchor / 100 % 100 - 1;
            if (focusYear < 1900 || focusMonth0 < 0 || focusMonth0 > 11) {
                focusYear = now.get(Calendar.YEAR);
                focusMonth0 = now.get(Calendar.MONTH);
            }
            pageYearStart = Math.max(1900, focusYear - focusYear % 12);

            selectedRectBg.setShape(GradientDrawable.RECTANGLE);
            selectedRectBg.setCornerRadius(dp(9));
            selectedRectBg.setColor(primary);
        }

        void show() {
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout root = new FrameLayout(activity);
            View scrim = new View(activity);
            scrim.setBackgroundColor(0x73000000);
            scrim.setOnClickListener(v -> dialog.dismiss());
            root.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ScrollView scroll = new ScrollView(activity);
            scroll.setBackgroundResource(R.drawable.bg_bottom_sheet);
            scroll.setClipToPadding(false);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int bottomInset = InsetsHelper.systemBars(activity.getWindow().getDecorView()).bottom;
            content.setPadding(dp(16), dp(6), dp(16), dp(12) + bottomInset);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            buildHeader(content);
            buildStatus(content);
            buildMonthHeader(content);
            buildWeekRow(content);
            grid = new LinearLayout(activity);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setMinimumHeight(dp(228));
            content.addView(grid, matchWrap());
            if (range) {
                buildPresets(content);
            }
            buildFooter(content);

            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int maxH = (int) (screenH * 0.9f);
            scroll.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            int sheetH = Math.min(scroll.getMeasuredHeight(), maxH);
            root.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, sheetH, Gravity.BOTTOM));

            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setWindowAnimations(R.style.Animation_Bookshelf_Sheet);
                // 全屏铺开 + 系统栏透明：否则对话框窗口会把导航栏区域留成黑色，
                // 面板底部与手势条之间出现一条黑边（真机实测）。
                InsetsHelper.makeSheetWindowFullScreen(window);
            }
            dialog.setCanceledOnTouchOutside(true);
            render();
            dialog.show();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }
        }

        // ==================== 视图构建 ====================

        private void buildHeader(LinearLayout content) {
            View handle = new View(activity);
            handle.setBackgroundResource(R.drawable.bg_sheet_handle);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
            hlp.gravity = Gravity.CENTER_HORIZONTAL;
            hlp.topMargin = dp(4);
            content.addView(handle, hlp);

            TextView tvTitle = new TextView(activity);
            tvTitle.setText(title);
            tvTitle.setTextColor(onSurface);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.text_title));
            tvTitle.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(10);
            content.addView(tvTitle, tlp);
        }

        private void buildStatus(LinearLayout content) {
            statusView = new TextView(activity);
            statusView.setTextColor(onVariant);
            statusView.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(3);
            content.addView(statusView, lp);
        }

        private void buildMonthHeader(LinearLayout content) {
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            hlp.topMargin = dp(6);
            content.addView(header, hlp);

            ImageView prev = chevron(R.drawable.ic_chevron_left_line);
            prev.setContentDescription("上一页");
            prev.setOnClickListener(v -> shift(-1));
            header.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));

            headerTitle = new TextView(activity);
            headerTitle.setGravity(Gravity.CENTER);
            headerTitle.setTextColor(onSurface);
            headerTitle.setTextSize(17);
            headerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.addView(headerTitle, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView next = chevron(R.drawable.ic_chevron_right_line);
            next.setContentDescription("下一页");
            next.setOnClickListener(v -> shift(1));
            header.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }

        private ImageView chevron(int res) {
            ImageView iv = new ImageView(activity);
            iv.setImageResource(res);
            iv.setPadding(dp(9), dp(9), dp(9), dp(9));
            ImageViewCompat.setImageTintList(iv,
                    android.content.res.ColorStateList.valueOf(onVariant));
            iv.setBackground(selectableBorderless());
            return iv;
        }

        private void buildWeekRow(LinearLayout content) {
            weekRow = new LinearLayout(activity);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
            wlp.topMargin = dp(2);
            content.addView(weekRow, wlp);
            String[] names = {"一", "二", "三", "四", "五", "六", "日"};
            for (String n : names) {
                TextView tv = new TextView(activity);
                tv.setText(n);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(onVariant);
                tv.setTextSize(12);
                weekRow.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }

        private void buildPresets(LinearLayout content) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            rlp.topMargin = dp(8);
            content.addView(row, rlp);
            String[] names = {"本月", "近3月", "今年", "去年"};
            for (int i = 0; i < names.length; i++) {
                final int which = i;
                TextView chip = new TextView(activity);
                chip.setText(names[i]);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(13);
                chip.setTextColor(primary);
                chip.setBackgroundResource(R.drawable.bg_filter_pill);
                chip.setClickable(true);
                chip.setFocusable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMarginEnd(i == names.length - 1 ? 0 : dp(6));
                chip.setLayoutParams(lp);
                chip.setOnClickListener(v -> applyPreset(which));
                row.addView(chip);
            }
        }

        private void buildFooter(LinearLayout content) {
            LinearLayout footer = new LinearLayout(activity);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            flp.topMargin = dp(10);
            content.addView(footer, flp);

            clearBtn = textButton("清除", onVariant, false);
            clearBtn.setOnClickListener(v -> {
                if (onClear != null) {
                    onClear.run();
                }
                dialog.dismiss();
            });
            footer.addView(clearBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            footer.addView(new View(activity), new LinearLayout.LayoutParams(
                    0, 1, 1f));

            TextView cancel = textButton("取消", onVariant, false);
            cancel.setOnClickListener(v -> dialog.dismiss());
            footer.addView(cancel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (range) {
                applyBtn = textButton("应用", primary, true);
                applyBtn.setOnClickListener(v -> apply());
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                alp.setMarginStart(dp(8));
                footer.addView(applyBtn, alp);
            }
        }

        private TextView textButton(String text, int color, boolean bold) {
            TextView tv = new TextView(activity);
            tv.setText(text);
            tv.setTextColor(color);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), 0, dp(12), 0);
            tv.setTypeface(Typeface.create("sans-serif-medium",
                    bold ? Typeface.BOLD : Typeface.NORMAL));
            tv.setBackground(selectableBorderless());
            tv.setClickable(true);
            tv.setFocusable(true);
            return tv;
        }

        @SuppressWarnings("ResourceType")
        private android.graphics.drawable.Drawable selectableBorderless() {
            TypedValue tv = new TypedValue();
            activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true);
            return tv.resourceId != 0
                    ? androidx.core.content.ContextCompat.getDrawable(activity, tv.resourceId)
                    : null;
        }

        private LinearLayout.LayoutParams matchWrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // ==================== 渲染 ====================

        private void render() {
            if (mode == MODE_DAY) {
                headerTitle.setText(String.format(Locale.ROOT, "%d年%d月", focusYear, focusMonth0 + 1));
                headerTitle.setOnClickListener(v -> {
                    mode = MODE_MONTH;
                    render();
                });
            } else if (mode == MODE_MONTH) {
                headerTitle.setText(String.format(Locale.ROOT, "%d年", focusYear));
                headerTitle.setOnClickListener(v -> {
                    mode = MODE_YEAR;
                    pageYearStart = Math.max(1900, focusYear - focusYear % 12);
                    render();
                });
            } else {
                headerTitle.setText(String.format(Locale.ROOT, "%d–%d",
                        pageYearStart, pageYearStart + 11));
                headerTitle.setOnClickListener(null);
            }
            headerTitle.setClickable(mode != MODE_YEAR);
            weekRow.setVisibility(mode == MODE_DAY ? View.VISIBLE : View.INVISIBLE);

            grid.removeAllViews();
            if (mode == MODE_DAY) {
                renderDays();
            } else if (mode == MODE_MONTH) {
                renderMonths();
            } else {
                renderYears();
            }

            statusView.setText(statusText());
            clearBtn.setVisibility(hasClearTarget() ? View.VISIBLE : View.INVISIBLE);
            if (applyBtn != null) {
                applyBtn.setEnabled(selStart > 0);
                applyBtn.setAlpha(selStart > 0 ? 1f : 0.4f);
            }
        }

        private void renderDays() {
            Calendar c = Calendar.getInstance();
            c.set(focusYear, focusMonth0, 1);
            int firstDow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 周一 = 0
            int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
            int todayKey = keyOf(Calendar.getInstance());
            boolean complete = range && selStart != 0 && selEnd != 0;

            LinearLayout row = null;
            for (int i = 0; i < 42; i++) {
                if (i % 7 == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
                }
                int day = i - firstDow + 1;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (day < 1 || day > days) {
                    row.addView(new View(activity), lp);
                    continue;
                }
                int key = keyOf(focusYear, focusMonth0 + 1, day);
                DayCellView cell = new DayCellView(activity);
                cell.bind(day, primary, onPrimary, onSurface,
                        key == selStart,
                        key == selEnd && selEnd != 0,
                        complete && key > selStart && key < selEnd,
                        complete && key >= selStart && key <= selEnd,
                        key == todayKey);
                cell.setContentDescription(String.format(Locale.ROOT,
                        "%d年%d月%d日", focusYear, focusMonth0 + 1, day));
                cell.setOnClickListener(v -> onDayTap(key));
                row.addView(cell, lp);
            }
        }

        private void renderMonths() {
            String[] names = {"1月", "2月", "3月", "4月", "5月", "6月",
                    "7月", "8月", "9月", "10月", "11月", "12月"};
            LinearLayout row = null;
            for (int i = 0; i < 12; i++) {
                if (i % 3 == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
                }
                final int month0 = i;
                TextView cell = bigCell(names[i]);
                boolean selected = isFocusMonthOfSelection(month0);
                if (selected) {
                    cell.setBackground(selectedRectBg);
                    cell.setTextColor(onPrimary);
                }
                cell.setOnClickListener(v -> {
                    focusMonth0 = month0;
                    mode = MODE_DAY;
                    render();
                });
                row.addView(cell);
            }
        }

        /** 当前年是否包含已选区间的起始月（月网格里给一个选中提示） */
        private boolean isFocusMonthOfSelection(int month0) {
            if (selStart == 0) {
                return month0 == focusMonth0;
            }
            return selStart / 10000 == focusYear && selStart / 100 % 100 - 1 == month0;
        }

        private void renderYears() {
            LinearLayout row = null;
            for (int i = 0; i < 12; i++) {
                if (i % 3 == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
                }
                final int year = pageYearStart + i;
                TextView cell = bigCell(year + "年");
                if (year == focusYear) {
                    cell.setBackground(selectedRectBg);
                    cell.setTextColor(onPrimary);
                }
                cell.setOnClickListener(v -> {
                    focusYear = year;
                    mode = MODE_MONTH;
                    render();
                });
                row.addView(cell);
            }
        }

        private TextView bigCell(String text) {
            TextView cell = new TextView(activity);
            cell.setText(text);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(15);
            cell.setTextColor(onSurface);
            cell.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(1), dp(2), dp(1), dp(2));
            cell.setLayoutParams(lp);
            return cell;
        }

        // ==================== 交互 ====================

        private void onDayTap(int key) {
            if (!range) {
                selStart = key;
                selEnd = key;
                render();
                apply();
                return;
            }
            if (selStart == 0 || selEnd != 0) {
                selStart = key;
                selEnd = 0;
            } else if (key < selStart) {
                selEnd = selStart;
                selStart = key;
            } else {
                selEnd = key;
            }
            render();
        }

        private void shift(int delta) {
            if (mode == MODE_DAY) {
                focusMonth0 += delta;
                if (focusMonth0 < 0) {
                    focusMonth0 = 11;
                    focusYear--;
                } else if (focusMonth0 > 11) {
                    focusMonth0 = 0;
                    focusYear++;
                }
            } else if (mode == MODE_MONTH) {
                focusYear += delta;
            } else {
                pageYearStart += delta * 12;
                pageYearStart = Math.max(1900, Math.min(2088, pageYearStart));
            }
            render();
        }

        private void applyPreset(int which) {
            Calendar now = Calendar.getInstance();
            int today = keyOf(now);
            Calendar c = Calendar.getInstance();
            switch (which) {
                case 0: // 本月
                    selStart = keyOf(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, 1);
                    selEnd = today;
                    break;
                case 1: // 近 3 个月（含本月）
                    c.add(Calendar.MONTH, -2);
                    selStart = keyOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, 1);
                    selEnd = today;
                    break;
                case 2: // 今年
                    selStart = keyOf(now.get(Calendar.YEAR), 1, 1);
                    selEnd = today;
                    break;
                default: // 去年
                    c.add(Calendar.YEAR, -1);
                    selStart = keyOf(c.get(Calendar.YEAR), 1, 1);
                    selEnd = keyOf(c.get(Calendar.YEAR), 12, 31);
                    break;
            }
            apply();
        }

        private void apply() {
            int start = selStart;
            int end = selEnd == 0 ? selStart : selEnd;
            if (start == 0) {
                return;
            }
            dialog.dismiss();
            if (range) {
                if (rangeCb != null) {
                    rangeCb.onPick(start, end);
                }
            } else if (dayCb != null) {
                dayCb.onPick(start);
            }
        }

        // ==================== 文案 / 工具 ====================

        private boolean hasClearTarget() {
            return range ? selStart != 0 : initialKey != 0;
        }

        private String statusText() {
            if (!range) {
                return selStart == 0 ? "点选一个日期" : fmt(selStart);
            }
            if (selStart == 0) {
                return "点选开始日期";
            }
            if (selEnd == 0) {
                return "从 " + fmt(selStart) + " 起 · 再点结束日期";
            }
            return fmt(selStart) + " 至 " + fmt(selEnd) + " · 共 " + daysBetween(selStart, selEnd) + " 天";
        }

        private static int daysBetween(int startKey, int endKey) {
            Calendar a = Calendar.getInstance();
            a.set(startKey / 10000, startKey / 100 % 100 - 1, startKey % 100, 0, 0, 0);
            Calendar b = Calendar.getInstance();
            b.set(endKey / 10000, endKey / 100 % 100 - 1, endKey % 100, 0, 0, 0);
            long diff = b.getTimeInMillis() - a.getTimeInMillis();
            return (int) Math.round(diff / 86400000.0) + 1;
        }

        private static int keyOf(Calendar c) {
            return keyOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH));
        }

        private static int keyOf(int year, int month1, int day) {
            return year * 10000 + month1 * 100 + day;
        }

        private static String fmt(int key) {
            return String.format(Locale.ROOT, "%04d-%02d-%02d",
                    key / 10000, key / 100 % 100, key % 100);
        }

        private int dp(int value) {
            return Math.round(value * density);
        }
    }
}
