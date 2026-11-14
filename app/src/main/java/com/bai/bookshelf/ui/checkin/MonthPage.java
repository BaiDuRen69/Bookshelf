package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.util.SparseIntArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInDots;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.Typo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一页月历（P2 从 MonthGridView 搬出）：自己的根视图、42 个格子、日号表与所属月份的
 * 打卡点/节日表，外加一张 dayKey → 下标 的表。
 *
 * <p>横向翻月期间 clip 上最多同时挂两页：当前页与邻页，切完旧页就摘掉。
 *
 * <p>绑定是只读的：输入全在 {@link MonthRenderState}，唯一的输出是「这一轮有没有真的
 * 贴到今天那一格」，门面据此决定今天入场动画算不算播过。
 */
final class MonthPage {

    /** 格子被点：页面不认识角色与回调，交回门面派发 */
    interface CellTap {
        void onTap(MonthPage page, int index);
    }

    final LinearLayout root;
    final DayCellView[] cells =
            new DayCellView[CheckInMonthGrid.DAYS_PER_WEEK * CheckInMonthGrid.MAX_ROWS];
    int[] dayKeys = new int[0];
    int yearMonth;
    /** dayKey → {打卡本数, 其中补卡本数} */
    Map<Integer, int[]> marks = Collections.emptyMap();
    @Nullable
    HolidayCalendar holidays;
    /** 换一天数据只查这张表，不再扫 42 个格子 */
    private final SparseIntArray keyToIndex = new SparseIntArray();

    private MonthPage(Context ctx) {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
    }

    /**
     * 建一页：行、周数列、42 个格子。格子先建好但不贴状态——由调用方紧接着
     * {@link #refreshAll} 一遍，入场动画的判定权留在门面。
     *
     * <p>农历预计算只在这里发生（打卡角色），也就是拖动开始建邻页的那一刻；
     * 提前到 DOWN 会让每次点按多付 42 次换算。
     */
    static MonthPage build(Context ctx, int ym, MonthData data,
                           MonthRenderState st, CellTap tap, int cellHeightPx) {
        MonthPage p = new MonthPage(ctx);
        p.yearMonth = ym;
        p.marks = data.dayMarks;
        p.holidays = data.holidays;
        int y = CheckInMonthGrid.yearOf(ym);
        int m = CheckInMonthGrid.monthOf(ym);
        p.dayKeys = CheckInMonthGrid.dayKeys(y, m);
        p.keyToIndex.clear();
        for (int i = 0; i < p.dayKeys.length; i++) {
            p.keyToIndex.put(p.dayKeys[i], i);
        }
        if (st.role == MonthRole.CHECKIN) {
            st.lunar.precompute(p.dayKeys); // 只有打卡月历显示农历，选择器不做无用换算
        }
        int[] weeks = CheckInMonthGrid.weekNumbers(p.dayKeys);

        // 第 10 项：把格子容器声明成「行=周、列=周数+7 天」的单选集合，
        // 每格再带 CollectionItemInfo，TalkBack 才会报「第 x 行第 y 列」。
        // heading 只留在 MonthGridView 上，不要再叠到格子容器。
        p.root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(
                        Math.max(1, p.dayKeys.length / CheckInMonthGrid.DAYS_PER_WEEK),
                        CheckInMonthGrid.DAYS_PER_WEEK + 1, false,
                        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
            }
        });

        for (int r = 0; r < weeks.length; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView week = weekLabel(ctx, String.valueOf(weeks[r]));
            week.setTextColor(st.palette.variant);
            week.setContentDescription(String.format(Locale.ROOT, "第 %d 周", weeks[r]));
            // 周数占第 0 列（第 10 项），只是普通单元格，不是标题
            final int weekRow = r;
            week.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                            weekRow, 1, 0, 1, false, false));
                }
            });
            row.addView(week, weekColumnLp(ctx));

            for (int c = 0; c < CheckInMonthGrid.DAYS_PER_WEEK; c++) {
                int index = r * CheckInMonthGrid.DAYS_PER_WEEK + c;
                DayCellView cell = new DayCellView(ctx);
                final int cellIndex = index;
                cell.setOnClickListener(v -> tap.onTap(p, cellIndex));
                // 转场中重建：新格子也要保持「冻结点动画」，否则滑入时会再播一遍
                cell.setDotAnimationSuspended(st.suspendDots);
                cell.setGridCell(r, c + 1);
                p.cells[index] = cell;
                // 只有打卡月历需要农历行（56dp）；精确日期选择器保持原来的 48dp
                row.addView(cell, new LinearLayout.LayoutParams(0, cellHeightPx, 1f));
            }
            p.root.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return p;
    }

    /** 星期表头与周数列共用的那档小字（L5） */
    static TextView weekLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        Typo.text(tv, R.dimen.text_caption);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setClickable(false);
        tv.setFocusable(false);
        return tv;
    }

    /** 周数列那一格的宽（表头与每行共用，两边必须同宽才对齐） */
    static LinearLayout.LayoutParams weekColumnLp(Context ctx) {
        return new LinearLayout.LayoutParams(
                ctx.getResources().getDimensionPixelSize(R.dimen.checkin_week_w),
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    int count() {
        return dayKeys.length;
    }

    /** 这一天的格子下标；不在本页返回 -1 */
    int indexOf(int dayKey) {
        return keyToIndex.get(dayKey, -1);
    }

    @Nullable
    DayCellView cellAt(int index) {
        return index >= 0 && index < dayKeys.length ? cells[index] : null;
    }

    int dayKeyAt(int index) {
        return index >= 0 && index < dayKeys.length ? dayKeys[index] : 0;
    }

    boolean isAdjacent(int dayKey) {
        return CheckInMonthGrid.isAdjacent(dayKey,
                CheckInMonthGrid.yearOf(yearMonth), CheckInMonthGrid.monthOf(yearMonth));
    }

    /** 重绑整页；返回真正贴上的今天入场 dayKey（0 = 这一轮没播） */
    int refreshAll(MonthRenderState st) {
        int entrance = 0;
        for (int i = 0; i < dayKeys.length; i++) {
            if (bind(i, st)) {
                entrance = dayKeys[i];
            }
        }
        return entrance;
    }

    /** 只重绑这几天所在的格子；不在本页的跳过 */
    int refreshCells(int[] keys, MonthRenderState st) {
        int entrance = 0;
        for (int key : keys) {
            int index = indexOf(key);
            if (index >= 0 && bind(index, st)) {
                entrance = key;
            }
        }
        return entrance;
    }

    void setDotSuspended(boolean suspended) {
        for (DayCellView cell : cells) {
            if (cell != null) {
                cell.setDotAnimationSuspended(suspended);
            }
        }
    }

    /** 两个 map 里取值有差异的 dayKey（含只在一边出现的） */
    static int[] diffKeys(Map<Integer, int[]> a, Map<Integer, int[]> b) {
        Set<Integer> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        int[] out = new int[keys.size()];
        int n = 0;
        for (Integer key : keys) {
            if (CheckInDots.total(a, key) != CheckInDots.total(b, key)
                    || CheckInDots.backfill(a, key) != CheckInDots.backfill(b, key)) {
                out[n++] = key;
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** 把一格当前的状态贴上去：胶囊、今天圈、灰显、打卡点与朗读文案；true = 刚播了今天入场 */
    private boolean bind(int index, MonthRenderState st) {
        DayCellView cell = cells[index];
        if (cell == null || index >= dayKeys.length) {
            return false;
        }
        int key = dayKeys[index];
        boolean adjacent = isAdjacent(key);
        boolean today = key == st.todayKey;
        boolean future = key > st.todayKey;
        int total = CheckInDots.total(marks, key);
        // 邻月永远不画点：不然会被读成「这个月打过卡」
        int backfill = adjacent ? 0 : CheckInDots.backfill(marks, key);
        MonthPalette palette = st.palette;
        if (st.role == MonthRole.PICKER) {
            // 选择器：已选/参考/锚点/预览四种底，越界日 alpha 110 且不响应点选
            boolean blocked = future || adjacent;
            cell.bindPicker(key % 100, palette.primary, palette.pickedBg, palette.referenceBg,
                    palette.previewBg, palette.onSurface, palette.variant,
                    st.picker.isPicked(key), st.picker.isReference(key), key == st.picker.anchor(),
                    st.picker.inPreview(key), today, blocked);
            cell.setContentDescription(MonthCellText.describePick(key, today, blocked,
                    st.picker.anchor(), st.picker.isPicked(key), st.picker.isReference(key)));
            return false;
        }
        // 节日名顶替农历小字；邻月同样取节日/农历，只靠颜色与 alpha 压暗
        String holidayName = holidays == null ? null : holidays.nameOf(key);
        String label = holidayName != null
                ? holidayName
                : (palette.lunarTextSize > 0 ? st.lunar.label(key) : "");
        int dayColor = holidayName != null ? palette.holiday : palette.onSurface;
        // 小字颜色只看「节日/农历」：今天与选中不再改它；邻月农历走灰、邻月节日仍走暖色
        int labelColor = holidayName != null
                ? palette.holiday
                : (adjacent ? palette.adjacentText : palette.lunar);
        cell.bindCheckIn(key % 100, palette.primary, palette.selectedBg, palette.selectedStroke,
                dayColor, palette.variant, key == st.selectedKey, today, adjacent, future,
                adjacent ? 0 : total, backfill, false,
                label, palette.onPrimary, palette.ring, labelColor,
                palette.lunarTextSize, palette.adjacentText,
                palette.todayBg, palette.onToday, palette.todayRing);
        cell.setContentDescription(MonthCellText.describe(holidays, key, today, total, backfill,
                adjacent, key == st.selectedKey, future, st.todayChecked));
        if (today && !adjacent && key == st.todayEntranceKey) {
            cell.startTodayEntrance();
            return true;
        }
        return false;
    }
}
