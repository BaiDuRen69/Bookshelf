package com.bai.bookshelf.ui.checkin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInCalendarState;
import com.bai.bookshelf.util.CheckInDots;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.LunarCalendar;
import com.bai.bookshelf.util.LunarCalendarIcu;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 打卡月视图：周一开头、左侧 ISO 周数列、7 列日期格，全部是真实 View
 * （TalkBack 能逐格朗读），不是一张自绘 bitmap。
 *
 * <p>颜色只取 ?attr 语义色（primary / primary_container / on_surface /
 * on_surface_variant），沿圆角与字阶令牌，不新增硬编码色值。
 * 切月用 GestureDetector + ValueAnimator，不引入 ViewPager2 或日历库。
 */
public class MonthGridView extends LinearLayout {

    public interface Listener {
        /** 点本月某天：列表切到这一天 */
        void onSelectDay(int dayKey);

        /** 点邻月灰显日：定位到那一天所在的月份 */
        void onMoveToDay(int dayKey);

        /** 月份变了：外部更新标题 / 重新取数；selectedDayKey 已夹到新月份 */
        void onMonthChanged(int yearMonth, int selectedDayKey);

        /** 点未来日：只说明原因，选中态不动 */
        void onFutureDay(int dayKey);
    }

    /** 折叠进度 p 的逐帧回调（0 = 整月，1 = 单周），页头用它同步收高一档 */
    public interface CollapseProgressListener {
        void onCollapseProgress(float progress);
    }

    /**
     * 选择器角色的回调（v5.9.1 修订第三步）：按动作通知，集合自己去 {@link #pickedKeys()} 取。
     *
     * <p>与 {@link Listener} 完全分开——打卡页的四个回调一个都没动，
     * {@link #setRole(int)} 切到 ROLE_PICKER 后走这套，切回 ROLE_CHECKIN 回到原来那套。
     */
    public interface PickerListener {
        /** 普通模式下单点勾/取消了一天 */
        void onToggleDay(int dayKey);

        /** 框选填充了一段（含两端） */
        void onRangeFill(int fromKey, int toKey);

        /** 对已填充的段再点两端，整段取消 */
        void onRangeCancel(int fromKey, int toKey);
    }

    /** 打卡页角色（默认）：点一天看那天读了哪几本 */
    public static final int ROLE_CHECKIN = 0;
    /** 精确日期选择器角色：勾/框选「哪几天读过」 */
    public static final int ROLE_PICKER = 1;

    private static final long ANIM_HALF_MS = 110; // 两段共 220ms
    private static final long COLLAPSE_MS = 220L;
    private static final float SWIPE_DISTANCE_DP = 60f;
    private static final float MIN_FLING_VELOCITY = 200f;

    private final LinearLayout grid;
    private final GestureDetector detector;
    private final float density;
    private final FastOutSlowInInterpolator collapseInterpolator =
            new FastOutSlowInInterpolator();
    /** 目标态：true = 只显示选中那一周 */
    private boolean weekMode;
    /** 折叠进度 0..1；手势/动画只改它，测量与位移都从这里派生 */
    private float collapseP;
    @Nullable
    private ValueAnimator collapseAnim;
    @Nullable
    private CollapseProgressListener collapseProgressListener;

    private int yearMonth;                 // yyyymm
    /** 当前结构（行/格子）对应的那个月；与 yearMonth 不一致说明该重建了 */
    private int structureMonth;
    private int selectedDayKey;
    private int todayKey;
    /** dayKey → {打卡本数, 其中补卡本数}，由 Fragment 用当月已有结果组装 */
    private Map<Integer, int[]> marks = Collections.emptyMap();
    private int colorPrimary;
    private int colorSelectedBg;
    private int colorSelectedStroke;
    private int colorOnSurface;
    private int colorVariant;
    private int colorPickedBg;
    private int colorReferenceBg;
    private int colorPreviewBg;
    // 今日实心圆的反色字 / 今天选中外圈 / 农历小字：全部取现有 ?attr 语义色
    private int colorOnPrimary;
    private int colorRing;
    private int colorLunar;
    private int colorHoliday;
    private int colorAdjacentText;
    private int colorTodayBg;
    private int colorOnToday;
    private int colorTodayRing;
    private float lunarTextSize;
    private final LunarCalendar lunar;
    /** 年度节日表：有节日名的日子用节日名替换农历小字 */
    @Nullable
    private HolidayCalendar holidays;
    @Nullable
    private Listener listener;
    // ===== 选择器角色（默认 ROLE_CHECKIN；切回去后与打卡页一字不差）=====
    private int role = ROLE_CHECKIN;
    @Nullable
    private PickerListener pickerListener;
    private final DayPickerState picker = new DayPickerState();
    private boolean scrolling;
    private int[] dayKeys = new int[0];
    private final DayCellView[] cells = new DayCellView[CheckInMonthGrid.DAYS_PER_WEEK
            * CheckInMonthGrid.MAX_ROWS];

    public MonthGridView(Context ctx) {
        this(ctx, null);
    }

    public MonthGridView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        setOrientation(VERTICAL);
        density = ctx.getResources().getDisplayMetrics().density;
        lunar = new LunarCalendar(new LunarCalendarIcu());
        ViewCompat.setAccessibilityHeading(this, true);

        addView(buildHeader(), new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        // 收周时 grid 整体上移，必须由「表头正下方」这层裁剪：
        // 只裁 MonthGridView 自身的话，上移的旧周会从星期表头那一带透出来
        FrameLayout clip = new FrameLayout(ctx);
        clip.setId(R.id.month_grid_clip);
        clip.setClipChildren(true);
        clip.setClipToPadding(true);
        grid = new LinearLayout(ctx);
        grid.setOrientation(VERTICAL);
        clip.addView(grid, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        addView(clip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setClipChildren(true);
        setClipToPadding(true);

        detector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (Math.abs(e2.getX() - e1.getX()) > dp(SWIPE_DISTANCE_DP)
                        && Math.abs(e2.getX() - e1.getX()) > Math.abs(e2.getY() - e1.getY())) {
                    scrolling = true; // 拦截后续事件，避免滑动结束误触发格子点击
                }
                return scrolling;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(SWIPE_DISTANCE_DP)
                        || Math.abs(vx) < MIN_FLING_VELOCITY
                        || Math.abs(dx) < Math.abs(dy)) {
                    return false;
                }
                shiftMonth(dx < 0 ? 1 : -1); // 左滑看下一月，右滑看上一月
                return true;
            }
        });

        yearMonth = CheckInMonthGrid.yearMonthOf(DateKeys.todayKey());
        todayKey = DateKeys.todayKey();
        selectedDayKey = todayKey;
        rebuildStructure();
        refreshAllCells();
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        int variant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        row.addView(weekLabel(""), weekColumnLp());
        for (String name : names) {
            TextView tv = weekLabel(name);
            tv.setTextColor(variant);
            row.addView(tv, dayColumnLp());
        }
        return row;
    }

    private TextView weekLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        Typo.text(tv, R.dimen.text_caption); // 周数与星期表头同档（L5）
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setClickable(false);
        tv.setFocusable(false);
        return tv;
    }

    private LayoutParams weekColumnLp() {
        LayoutParams lp = new LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.checkin_week_w),
                LayoutParams.WRAP_CONTENT);
        return lp;
    }

    private LayoutParams dayColumnLp() {
        return new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
    }

    // ==================== 对外 API ====================

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    public void setCollapseProgressListener(@Nullable CollapseProgressListener l) {
        collapseProgressListener = l;
    }

    /**
     * 5.1：整月 ↔ 单周。只改自身测量高度与 grid 的 translationY，
     * 不 removeAllViews、不改格子的可见性；220ms FastOutSlowIn。
     */
    public void setWeekMode(boolean week, boolean animate) {
        weekMode = week;
        float target = week ? 1f : 0f;
        cancelCollapseAnim();
        if (!animate || Math.abs(collapseP - target) < 0.001f) {
            setCollapseProgress(target);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(collapseP, target);
        anim.setDuration(COLLAPSE_MS);
        anim.setInterpolator(collapseInterpolator);
        anim.addUpdateListener(a -> setCollapseProgress((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (collapseAnim != animation) {
                    return; // 已被新的手势打断
                }
                collapseAnim = null;
                setCollapseProgress(weekMode ? 1f : 0f);
            }
        });
        collapseAnim = anim;
        anim.start();
    }

    public boolean isWeekMode() {
        return weekMode;
    }

    /** 整月高度：星期表头 + 6 行 + 上下内边距 */
    public int getFullHeightPx() {
        return getPaddingTop() + headerHeightPx()
                + CheckInMonthGrid.MAX_ROWS * currentCellHeightPx() + getPaddingBottom();
    }

    /** 单周高度：星期表头 + 1 行 + 上下内边距 */
    public int getWeekHeightPx() {
        return getPaddingTop() + headerHeightPx()
                + currentCellHeightPx() + getPaddingBottom();
    }

    /** 折叠进度变化时同步自身高度与选中周的位移，并通知页头 */
    private void setCollapseProgress(float p) {
        collapseP = Math.max(0f, Math.min(1f, p));
        updateCollapseTranslation();
        requestLayout();
        if (collapseProgressListener != null) {
            collapseProgressListener.onCollapseProgress(collapseP);
        }
    }

    /** 选中那一周在收起后停在星期表头正下方 */
    private void updateCollapseTranslation() {
        grid.setTranslationY(-selectedRow() * currentCellHeightPx() * collapseP);
    }

    private int selectedRow() {
        for (int i = 0; i < dayKeys.length; i++) {
            if (dayKeys[i] == selectedDayKey) {
                return i / CheckInMonthGrid.DAYS_PER_WEEK;
            }
        }
        return 0;
    }

    private int currentCellHeightPx() {
        return getResources().getDimensionPixelSize(role == ROLE_PICKER
                ? R.dimen.checkin_cell_h_picker : R.dimen.checkin_cell_h);
    }

    private int headerHeightPx() {
        if (getChildCount() == 0) {
            return 0;
        }
        View header = getChildAt(0);
        if (header.getMeasuredHeight() > 0) {
            return header.getMeasuredHeight();
        }
        int width = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return header.getMeasuredHeight();
    }

    private void cancelCollapseAnim() {
        ValueAnimator a = collapseAnim;
        collapseAnim = null;
        if (a != null) {
            a.cancel();
        }
    }

    public int getYearMonth() {
        return yearMonth;
    }

    public int getSelectedDayKey() {
        return selectedDayKey;
    }

    public int getDayCellCount() {
        return dayKeys.length;
    }

    @Nullable
    public DayCellView getCellAt(int index) {
        return index >= 0 && index < dayKeys.length ? cells[index] : null;
    }

    public int getDayKeyAt(int index) {
        return index >= 0 && index < dayKeys.length ? dayKeys[index] : 0;
    }

    /** 跨天/从详情页返回时只更新「今天 / 未来」分界，不抢走用户选中的日期 */
    public void refreshTodayKey() {
        todayKey = DateKeys.todayKey();
        refreshAllCells();
    }

    /** 月↔年切换动画期间暂停 42 个格子的打卡点入场动画，保持帧率 */
    public void suspendDotAnimations(boolean suspended) {
        for (DayCellView cell : cells) {
            if (cell != null) {
                cell.setDotAnimationSuspended(suspended);
            }
        }
    }

    /**
     * 选中日期格中心相对动画容器的坐标（px），月↔年切换拿它当缩放中心，
     * 不是屏幕中心。找不到格子时退回容器中心。
     */
    public int[] selectedCellCenterIn(View host) {
        int[] hostLoc = new int[2];
        host.getLocationInWindow(hostLoc);
        for (int i = 0; i < dayKeys.length; i++) {
            DayCellView cell = cells[i];
            if (dayKeys[i] == selectedDayKey && cell != null) {
                int[] loc = new int[2];
                cell.getLocationInWindow(loc);
                return new int[]{loc[0] + cell.getWidth() / 2 - hostLoc[0],
                        loc[1] + cell.getHeight() / 2 - hostLoc[1]};
            }
        }
        return new int[]{host.getWidth() / 2, host.getHeight() / 2};
    }

    // ==================== 选择器模式（v5.9.1 第三步）====================

    /**
     * 切角色（v5.9.1 修订第三步）。ROLE_PICKER 时格子改成勾选/框选，
     * 打卡页的 onSelectDay 等回调不再触发；切回 ROLE_CHECKIN 行为回到原样。
     */
    public void setRole(int role) {
        this.role = role == ROLE_PICKER ? ROLE_PICKER : ROLE_CHECKIN;
        if (this.role == ROLE_CHECKIN) {
            picker.setRangeMode(false);
        }
        resolveCellColors();
        rebuildStructure(); // 角色切换要应用对应的格高（选择器 48dp / 打卡 56dp）
        refreshAllCells();
        updatePickerHeading();
    }

    public void setPickerListener(@Nullable PickerListener l) {
        this.pickerListener = l;
    }

    /** 已选集合（实心胶囊 + 右下点） */
    public void setPickedDays(@Nullable Set<Integer> keys) {
        picker.setPicked(keys);
        if (role == ROLE_PICKER) {
            refreshAllCells();
            updatePickerHeading();
        }
    }

    /** 原模糊区间的参考底纹：浅底、不算已选，点它一下才变已选 */
    public void setReferenceDays(@Nullable Set<Integer> keys) {
        picker.setReference(keys);
        if (role == ROLE_PICKER) {
            refreshAllCells();
        }
    }

    /** 框选起点（描边 + 主色底）；0 = 取消 */
    public void setRangeAnchor(int dayKey) {
        picker.setAnchor(dayKey);
        if (role == ROLE_PICKER) {
            refreshAllCells();
        }
    }

    /** 「连续区间」开关：开着时第一点落锚、第二点填充/取消整段 */
    public void setRangeMode(boolean on) {
        picker.setRangeMode(on);
        if (role == ROLE_PICKER) {
            refreshAllCells();
            announceForAccessibility(on ? "框选模式：点起点再点终点" : "已退出框选模式");
        }
    }

    public boolean isRangeMode() {
        return picker.rangeMode();
    }

    /** 「清空」：只清已选，参考底纹留着对照 */
    public void clearPicked() {
        picker.clear();
        if (role == ROLE_PICKER) {
            refreshAllCells();
            updatePickerHeading();
        }
    }

    /** 当前已选日（升序副本，调用方改不动内部集合）；确定落库时取它 */
    public List<Integer> pickedKeys() {
        return picker.pickedKeys();
    }

    /** 网格整体的读屏标题：选择器角色报已选天数 */
    private void updatePickerHeading() {
        if (role == ROLE_PICKER) {
            setContentDescription("精确记录选择器，已选 " + picker.pickedKeys().size() + " 天");
        } else {
            setContentDescription(CheckInMonthGrid.titleOf(yearMonth) + "日历");
        }
    }

    /**
     * @param dayMarks dayKey → {打卡本数, 其中补卡本数}，由 CheckInDots.marks 组装
     */
    public void setCheckInMarks(@Nullable Map<Integer, int[]> dayMarks) {
        Map<Integer, int[]> old = marks;
        marks = dayMarks == null ? Collections.emptyMap() : new HashMap<>(dayMarks);
        if (structureMonth != yearMonth) {
            rebuildStructure();
        }
        refreshCells(diffKeys(old, marks));
    }

    /** 年度节日表；null = 只显示农历。切年或网络结果回来后重贴整表 */
    public void setHolidays(@Nullable HolidayCalendar cal) {
        this.holidays = cal;
        if (role == ROLE_CHECKIN) {
            refreshAllCells();
        }
    }

    public void setSelectedDay(int dayKey) {
        int prev = selectedDayKey;
        selectedDayKey = dayKey;
        if (structureMonth != yearMonth) {
            rebuildStructure();
            refreshAllCells();
            return;
        }
        // 只有旧选中格和新选中格的胶囊会变，别的格子保持原样
        refreshCells(new int[]{prev, dayKey});
        // 周模式下选中日换了一行：把新的那一周挪到表头下
        updateCollapseTranslation();
    }

    /** 无动画定位（「今」按钮与年视图点月回来用） */
    public void setYearMonth(int ym) {
        if (ym == yearMonth) {
            return;
        }
        yearMonth = ym;
        selectedDayKey = CheckInMonthGrid.clampDayKey(selectedDayKey, ym);
        rebuildStructure();
        refreshAllCells();
        announceMonth();
        fireMonthChanged();
    }

    /**
     * 静默预置目标月：年视图点月时先把它铺好，转场期间看到的就已经是新月份。
     * 不发播报、不回调 onMonthChanged——数据刷新由转场结束后调用方自己触发。
     */
    public void setYearMonthSilently(int yearMonth, int selectedKey) {
        this.yearMonth = yearMonth;
        this.selectedDayKey = selectedKey > 0
                ? selectedKey : CheckInMonthGrid.clampDayKey(this.selectedDayKey, yearMonth);
        rebuildStructure();
        refreshAllCells();
    }

    public void moveToToday() {
        int today = DateKeys.todayKey();
        todayKey = today;
        selectedDayKey = today;
        if (yearMonth == CheckInMonthGrid.yearMonthOf(today)) {
            // 月没变，但「今天/未来」的分界挪了，整表状态要重贴一遍
            refreshAllCells();
            updateCollapseTranslation();
        } else {
            setYearMonth(CheckInMonthGrid.yearMonthOf(today));
        }
    }

    public void shiftMonth(int delta) {
        int target = CheckInMonthGrid.shiftMonth(yearMonth, delta);
        animateToMonth(target, delta > 0);
    }

    /**
     * 固定高度 = 星期表头 + 6 行格高 + 内边距。切月只换格子内容，不再让
     * 5 行月份的月历比 6 行月份矮一截，列表起始位置也就不会跟着跳。
     *
     * <p>收周时按折叠进度 p 把自身高度在「整月」与「单周」之间插值，
     * grid 仍按整月高度测量并整体上移，多出来的行由父容器的裁剪去掉。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = CheckInCalendarState.heightAt(
                getFullHeightPx(), getWeekHeightPx(), collapseP);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    // ==================== 渲染 ====================

    /**
     * 建结构：行、周数列、格子。只有月份变了才走这里（42 个 View 的重建是切月唯一的开销）。
     * 状态一律交给 {@link #bindCell}，选一天、打一次卡都不再 removeAllViews。
     */
    private void rebuildStructure() {
        grid.removeAllViews();
        java.util.Arrays.fill(cells, null);
        int y = CheckInMonthGrid.yearOf(yearMonth);
        int m = CheckInMonthGrid.monthOf(yearMonth);
        dayKeys = CheckInMonthGrid.dayKeys(y, m);
        if (role == ROLE_CHECKIN) {
            lunar.precompute(dayKeys); // 只有打卡月历显示农历，选择器不做无用换算
        }
        int[] weeks = CheckInMonthGrid.weekNumbers(dayKeys);
        structureMonth = yearMonth;

        // nonTransitiveRClass=true：项目 R.attr 不含 appcompat 的 colorPrimary，
        // 走 ThemeManager 暴露的 androidx.appcompat.R.attr.colorPrimary
        resolveCellColors();

        for (int r = 0; r < weeks.length; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            TextView week = weekLabel(String.valueOf(weeks[r]));
            week.setTextColor(colorVariant);
            week.setContentDescription(String.format(Locale.ROOT, "第 %d 周", weeks[r]));
            row.addView(week, weekColumnLp());

            for (int c = 0; c < CheckInMonthGrid.DAYS_PER_WEEK; c++) {
                int index = r * CheckInMonthGrid.DAYS_PER_WEEK + c;
                DayCellView cell = new DayCellView(getContext());
                final int cellIndex = index;
                cell.setOnClickListener(v -> onCellTouched(cellIndex));
                cells[index] = cell;
                // 只有打卡月历需要农历行（56dp）；精确日期选择器保持原来的 48dp
                int cellH = getResources().getDimensionPixelSize(role == ROLE_PICKER
                        ? R.dimen.checkin_cell_h_picker : R.dimen.checkin_cell_h);
                row.addView(cell, new LinearLayout.LayoutParams(0, cellH, 1f));
            }
            grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }
        updatePickerHeading();
        updateCollapseTranslation(); // 切月后 selectedRow 可能变了，周模式下同步平移
    }

    /** 把一格当前的状态贴上去：胶囊、今天圈、灰显、打卡点与朗读文案 */
    private void bindCell(int index) {
        DayCellView cell = cells[index];
        if (cell == null || index >= dayKeys.length) {
            return;
        }
        int key = dayKeys[index];
        boolean adjacent = CheckInMonthGrid.isAdjacent(key,
                CheckInMonthGrid.yearOf(yearMonth), CheckInMonthGrid.monthOf(yearMonth));
        boolean today = key == todayKey;
        boolean future = key > todayKey;
        int total = CheckInDots.total(marks, key);
        // 邻月永远不画点：不然会被读成「这个月打过卡」
        int backfill = adjacent ? 0 : CheckInDots.backfill(marks, key);
        if (role == ROLE_PICKER) {
            // 选择器：已选/参考/锚点/预览四种底，越界日 alpha 110 且不响应点选
            boolean blocked = future || adjacent;
            cell.bindPicker(key % 100, colorPrimary, colorPickedBg, colorReferenceBg,
                    colorPreviewBg, colorOnSurface, colorVariant,
                    picker.isPicked(key), picker.isReference(key), key == picker.anchor(),
                    picker.inPreview(key), today, blocked);
            cell.setContentDescription(describePick(key, today, blocked));
            return;
        }
        // 节日名顶替农历小字；邻月不参与。今天/选中的小字颜色在 DayCellView 里另有分支
        String holidayName = adjacent || holidays == null ? null : holidays.nameOf(key);
        String label = holidayName != null
                ? holidayName
                : (lunarTextSize > 0 ? lunar.label(key) : "");
        int dayColor = holidayName != null ? colorHoliday : colorOnSurface;
        int labelColor = today ? colorOnToday
                : (key == selectedDayKey ? colorPrimary
                : (holidayName != null ? colorHoliday : colorLunar));
        cell.bindCheckIn(key % 100, colorPrimary, colorSelectedBg, colorSelectedStroke,
                dayColor, colorVariant, key == selectedDayKey, today, adjacent, future,
                adjacent ? 0 : total, backfill, false,
                label, colorOnPrimary, colorRing, labelColor,
                lunarTextSize, colorAdjacentText,
                colorTodayBg, colorOnToday, colorTodayRing);
        cell.setContentDescription(
                describe(key, today, total, backfill, adjacent, key == selectedDayKey, future));
    }

    private void refreshAllCells() {
        for (int i = 0; i < dayKeys.length; i++) {
            bindCell(i);
        }
    }

    /** 只重绑这几个 dayKey 所在的格子；不在本月的（邻月补齐格之外的日期）直接跳过 */
    private void refreshCells(int[] keys) {
        for (int key : keys) {
            for (int i = 0; i < dayKeys.length; i++) {
                if (dayKeys[i] == key) {
                    bindCell(i);
                    break;
                }
            }
        }
    }

    /** 两个 map 里取值有差异的 dayKey（含只在一边出现的） */
    private static int[] diffKeys(Map<Integer, int[]> a, Map<Integer, int[]> b) {
        Set<Integer> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        int[] out = new int[keys.size()];
        int n = 0;
        for (Integer key : keys) {
            if (!same(CheckInDots.total(a, key), CheckInDots.backfill(a, key),
                    CheckInDots.total(b, key), CheckInDots.backfill(b, key))) {
                out[n++] = key;
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static boolean same(int totalA, int backfillA, int totalB, int backfillB) {
        return totalA == totalB && backfillA == backfillB;
    }

    private void onCellTouched(int index) {
        if (scrolling || index >= dayKeys.length) {
            return;
        }
        int key = dayKeys[index];
        boolean adjacent = CheckInMonthGrid.isAdjacent(key,
                CheckInMonthGrid.yearOf(yearMonth), CheckInMonthGrid.monthOf(yearMonth));
        if (role == ROLE_PICKER) {
            onPickTouched(key, adjacent);
            return;
        }
        if (adjacent) {
            if (listener != null) {
                listener.onMoveToDay(key);
            }
        } else if (key > todayKey) {
            // 未来日允许选中查看（列表会显示但按钮禁用），只是不能提交打卡
            setSelectedDay(key);
            if (listener != null) {
                listener.onSelectDay(key);
                listener.onFutureDay(key); // 只用于无障碍播报，不再弹拒绝 Toast
            }
        } else {
            setSelectedDay(key);
            if (listener != null) {
                listener.onSelectDay(key);
            }
        }
    }

    /** 选择器角色下的单击：规则全在 DayPickerState 里，这里只派发回调与重绘 */
    private void onPickTouched(int key, boolean adjacent) {
        if (adjacent) {
            return; // 邻月格在选择器里不响应（画成不可选）
        }
        int action = picker.tap(key, todayKey);
        if (action == DayPickerState.ACTION_NONE) {
            refreshAllCells(); // 落锚 / 越界：集合没变但锚点或提示变了
            updatePickerHeading();
            return;
        }
        refreshAllCells();
        updatePickerHeading();
        if (pickerListener == null) {
            return;
        }
        if (action == DayPickerState.ACTION_TOGGLE) {
            pickerListener.onToggleDay(key);
        } else if (action == DayPickerState.ACTION_FILL) {
            pickerListener.onRangeFill(picker.lastFrom(), picker.lastTo());
        } else {
            pickerListener.onRangeCancel(picker.lastFrom(), picker.lastTo());
        }
    }

    /** 悬停预览：框选模式下锚点已落、终点未定时，把锚点到悬停日画成 outline 底 */
    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        if (role == ROLE_PICKER && picker.rangeMode() && picker.anchor() != 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                picker.clearPreview();
                refreshAllCells();
                return true;
            }
            int index = indexAt(ev.getX(), ev.getY());
            if (index >= 0) {
                picker.setPreview(picker.anchor(), dayKeys[index]);
                refreshAllCells();
                return true;
            }
        }
        return super.onHoverEvent(ev);
    }

    /** 坐标落在哪个格子（仅本月格）；落空返回 -1 */
    private int indexAt(float x, float y) {
        for (int i = 0; i < dayKeys.length; i++) {
            DayCellView cell = cells[i];
            if (cell == null) {
                continue;
            }
            int[] loc = new int[2];
            cell.getLocationInWindow(loc);
            int[] self = new int[2];
            getLocationInWindow(self);
            float lx = loc[0] - self[0];
            float ly = loc[1] - self[1];
            if (x >= lx && x <= lx + cell.getWidth() && y >= ly && y <= ly + cell.getHeight()) {
                return i;
            }
        }
        return -1;
    }

    /** 选择器朗读：「11月5日，已选 / 参考区间 / 不可选」，今天与框选起点一起念 */
    private String describePick(int dayKey, boolean today, boolean blocked) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayKey / 100 % 100).append("月").append(dayKey % 100).append("日");
        if (today) {
            sb.append("，今天");
        }
        if (dayKey == picker.anchor()) {
            sb.append("，框选起点");
        }
        if (blocked) {
            sb.append("，不可选");
        } else if (picker.isPicked(dayKey)) {
            sb.append("，已选");
        } else if (picker.isReference(dayKey)) {
            sb.append("，参考区间");
        } else {
            sb.append("，未选");
        }
        return sb.toString();
    }

    /** 一轮渲染解析一次格子用色（切主题会重建视图，不必管热更新） */
    private void resolveCellColors() {
        colorPrimary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        colorSelectedBg = ThemeManager.resolveColor(getContext(), R.attr.checkin_selected_bg);
        colorSelectedStroke =
                ThemeManager.resolveColor(getContext(), R.attr.checkin_selected_stroke);
        colorOnSurface = ThemeManager.resolveColor(getContext(), R.attr.on_surface);
        colorVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        colorPickedBg = ThemeManager.resolveColor(getContext(), R.attr.primary_container);
        colorReferenceBg = ThemeManager.resolveColor(getContext(), R.attr.surface_dim);
        colorPreviewBg = ThemeManager.resolveColor(getContext(), R.attr.outline);
        // 主色实心圆上的文字走 colorOnPrimary（material attr，nonTransitiveRClass 下要全限定）
        colorOnPrimary = ThemeManager.resolveColor(getContext(),
                com.google.android.material.R.attr.colorOnPrimary);
        colorRing = ThemeManager.resolveColor(getContext(), R.attr.checkin_day_ring);
        colorLunar = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        colorHoliday = ThemeManager.resolveColor(getContext(), R.attr.checkin_holiday_text);
        colorAdjacentText = ThemeManager.resolveColor(getContext(), R.attr.checkin_adjacent_text);
        colorTodayBg = ThemeManager.resolveColor(getContext(), R.attr.checkin_today_bg);
        colorOnToday = ThemeManager.resolveColor(getContext(), R.attr.checkin_on_today);
        colorTodayRing = ThemeManager.resolveColor(getContext(), R.attr.checkin_today_ring);
        float scale = getResources().getConfiguration().fontScale;
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        // 大字模式/窄屏把农历整行收掉，公历数字与打卡点不裁
        lunarTextSize = scale < 1.3f && widthDp >= 360f
                ? getResources().getDimension(R.dimen.checkin_lunar_text) : 0f;
    }

    /** 「11月5日，已打卡（3 本 · 含 1 笔补卡）」/「11月20日，未来日期，未打卡」 */
    private String describe(int dayKey, boolean today, int total, int backfill,
                            boolean adjacent, boolean selected, boolean future) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayKey / 100 % 100).append("月").append(dayKey % 100).append("日");
        if (adjacent) {
            sb.append("，邻月");
        }
        if (selected) {
            sb.append("，已选中");
        }
        if (today) {
            sb.append("，今天");
        }
        if (future) {
            sb.append("，未来日期");
        }
        String holiday = adjacent || holidays == null ? null : holidays.nameOf(dayKey);
        if (holiday != null) {
            sb.append("，").append(holiday);
        }
        if (total > 0) {
            sb.append("，已打卡").append("（").append(total).append(" 本");
            if (backfill > 0) {
                sb.append(" · 含 ").append(backfill).append(" 笔补卡");
            }
            sb.append("）");
        } else {
            sb.append("，未打卡");
        }
        return sb.toString();
    }

    // ==================== 切月动画与手势 ====================

    private void animateToMonth(int target, boolean forward) {
        float dir = forward ? -1f : 1f; // 旧网格退场方向
        float distance = getResources().getDimensionPixelSize(R.dimen.checkin_swipe_distance);
        ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(ANIM_HALF_MS);
        out.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            grid.setAlpha(1f - f);
            grid.setTranslationX(dir * distance * f);
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                yearMonth = target;
                selectedDayKey = CheckInMonthGrid.clampDayKey(selectedDayKey, target);
                rebuildStructure();
                refreshAllCells();
                grid.setAlpha(0f);
                grid.setTranslationX(-dir * distance);
                ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
                in.setDuration(ANIM_HALF_MS);
                in.addUpdateListener(b -> {
                    float g = (float) b.getAnimatedValue();
                    grid.setAlpha(g);
                    grid.setTranslationX(-dir * distance * (1f - g));
                });
                in.start();
                announceMonth();
                fireMonthChanged();
            }
        });
        out.start();
    }

    private void fireMonthChanged() {
        if (listener != null) {
            listener.onMonthChanged(yearMonth, selectedDayKey);
        }
    }

    private void announceMonth() {
        announceForAccessibility(CheckInMonthGrid.titleOf(yearMonth));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        detector.onTouchEvent(ev); // 只喂一次，避免 onFling 触发两遍
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 纵向收周/展开归 CheckInSwipeRoot；这里只拦横向切月，避免滑完误点格子
        return scrolling || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scrolling = false;
        }
        return scrolling || super.onTouchEvent(ev);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        scrolling = false;
    }

    /**
     * 打卡/撤销后只重绑受影响的那一格（点与朗读文案），不重建整张网格。
     *
     * @param total    当天打卡的本数（0 = 清空该格）
     * @param backfill 其中补卡的本数
     */
    public void setDayMarks(int dayKey, int total, int backfill) {
        Map<Integer, int[]> copy = new HashMap<>(marks);
        if (total > 0) {
            copy.put(dayKey, new int[]{total, backfill});
        } else {
            copy.remove(dayKey);
        }
        marks = copy;
        resolveCellColors();
        refreshCells(new int[]{dayKey});
    }

    private float dp(float value) {
        return value * density;
    }
}
