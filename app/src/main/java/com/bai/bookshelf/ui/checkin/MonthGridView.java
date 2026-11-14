package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInCalendarState;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.LunarCalendar;
import com.bai.bookshelf.util.LunarCalendarIcu;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打卡月视图：周一开头、左侧 ISO 周数列、7 列日期格，全部是真实 View
 * （TalkBack 能逐格朗读），不是一张自绘 bitmap。
 *
 * <p>颜色只取 ?attr 语义色（primary / primary_container / on_surface /
 * on_surface_variant），沿圆角与字阶令牌，不新增硬编码色值。
 * 切月用自定义 VelocityTracker 手势 + 双层 ValueAnimator，不引入 ViewPager2 或日历库。
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
        /**
         * @param live true = 手指正在拖，页头据此决定哪些属性每帧改、哪些只在端点改
         */
        void onCollapseProgress(float progress, boolean live);
    }

    /**
     * 邻月数据供给（v5.9.5 第 3 项）：横向拖动时邻页要立刻有内容，
     * 拿不到就只铺日期，提交后由 {@code loadMonth} 补全。
     */
    public interface NeighborDataProvider {
        @Nullable
        MonthData dataFor(int yearMonth);
    }

    /**
     * 选择器角色的回调（v5.9.1 修订第三步）：按动作通知，集合自己去 {@link #pickedKeys()} 取。
     *
     * <p>与 {@link Listener} 完全分开——打卡页的四个回调一个都没动，
     * {@link #setRole(int)} 切到 MonthRole.PICKER 后走这套，切回 MonthRole.CHECKIN 回到原来那套。
     */
    public interface PickerListener {
        /** 普通模式下单点勾/取消了一天 */
        void onToggleDay(int dayKey);

        /** 框选填充了一段（含两端） */
        void onRangeFill(int fromKey, int toKey);

        /** 对已填充的段再点两端，整段取消 */
        void onRangeCancel(int fromKey, int toKey);
    }

    /** 表头下方那层裁剪容器：当前页与邻页都挂在它上面 */
    private final FrameLayout clip;
    /** 当前页：格子、日期表、月份与它自己的 marks/holidays 全在这里，不再有散装副本 */
    private MonthPage cur;
    /** 横向翻月：位移、邻页、动画与测速全在它手里，门面只管建页/摘页/转正 */
    private final MonthPager pager;
    /** 纵向折叠：进度、目标态与收尾动画在它手里，测量与平移回到门面 */
    private final CollapseController collapse = new CollapseController(this::applyCollapseProgress);
    private final float density;
    private final int touchSlopPx;
    @Nullable
    private CollapseProgressListener collapseProgressListener;

    private int yearMonth;                 // yyyymm
    private int selectedDayKey;
    private int todayKey;
    /** 已经播过入场动画的那个「今天」；切月回来、每次打卡刷新都不再重播 */
    private int animatedTodayKey;
    /** 今天是否已有任意一本打卡（第 9 项）：顶部点实心 / 空心由它决定 */
    private boolean todayChecked;
    private final MonthPalette palette;
    private final LunarCalendar lunar;
    @Nullable
    private Listener listener;
    // ===== 选择器角色（默认 MonthRole.CHECKIN；切回去后与打卡页一字不差）=====
    private int role = MonthRole.CHECKIN;
    @Nullable
    private PickerListener pickerListener;
    private final DayPickerState picker = new DayPickerState();
    @Nullable
    private NeighborDataProvider neighborData;
    /** 切月转场期间为 true：重建出来的格子也直接跳过打卡点入场动画 */
    private boolean dotSuspended;
    // ===== 纵向实时折叠（v5.9.5 第 3 项）：进度与动画在 CollapseController，这里只留测量缓存 =====
    /** 上一次真正下发的测量高度，相同就别再 requestLayout */
    private int lastCollapseHeight = -1;
    private int cachedCellH = -1;
    private int cachedHeaderH = -1;
    /** 结构脏了才重测 42 个格子；拖动期间只改自身高度 */
    private boolean structureDirty = true;
    private int lastMeasuredWidth = -1;

    public MonthGridView(Context ctx) {
        this(ctx, null);
    }

    public MonthGridView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        setOrientation(VERTICAL);
        density = ctx.getResources().getDisplayMetrics().density;
        touchSlopPx = ViewConfiguration.get(ctx).getScaledTouchSlop();
        pager = new MonthPager(new PagerHost(), density, touchSlopPx);
        palette = MonthPalette.resolve(ctx);
        lunar = new LunarCalendar(new LunarCalendarIcu());
        ViewCompat.setAccessibilityHeading(this, true);

        addView(buildHeader(), new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        // 收周时格子容器整体上移，必须由「表头正下方」这层裁剪：
        // 只裁 MonthGridView 自身的话，上移的旧周会从星期表头那一带透出来
        clip = new FrameLayout(ctx);
        clip.setId(R.id.month_grid_clip);
        clip.setClipChildren(true);
        clip.setClipToPadding(true);
        addView(clip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setClipChildren(true);
        setClipToPadding(true);

        yearMonth = CheckInMonthGrid.yearMonthOf(DateKeys.todayKey());
        todayKey = DateKeys.todayKey();
        selectedDayKey = todayKey;
        cur = newPage(yearMonth, null);
        clip.addView(cur.root, pageLp());
        updatePickerHeading();
    }

    private FrameLayout.LayoutParams pageLp() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        row.addView(MonthPage.weekLabel(getContext(), ""), MonthPage.weekColumnLp(getContext()));
        for (String name : names) {
            TextView tv = MonthPage.weekLabel(getContext(), name);
            tv.setTextColor(palette.variant);
            row.addView(tv, dayColumnLp());
        }
        return row;
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
     * 不 removeAllViews、不改格子的可见性。
     */
    public void setWeekMode(boolean week, boolean animate) {
        collapse.setWeekMode(week, animate, 0L);
    }

    /**
     * v5.9.5 第 3 项：拖动收尾时按剩余距离给时长，{@code durationMs <= 0} 用默认 220ms。
     * 手指还在拖（{@link #setDragging(boolean)} 为 true）时动画一律不接管，避免和手指抢进度。
     */
    public void setWeekMode(boolean week, boolean animate, long durationMs) {
        collapse.setWeekMode(week, animate, durationMs);
    }

    public boolean isWeekMode() {
        return collapse.weekMode();
    }

    /** 当前折叠进度：拖动起点要从这里取，不能假设从 0 开始 */
    public float getCollapseProgress() {
        return collapse.progress();
    }

    /** 手指接管期间动画让位；置 true 顺手打断正在跑的收起动画 */
    public void setDragging(boolean dragging) {
        collapse.setDragging(dragging);
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

    /**
     * 折叠进度变化时同步自身高度与选中周的位移，并通知页头。
     *
     * <p>高度没变就不 {@code requestLayout()}：一格 8dp 的抖动不值得重排整页。
     */
    public void setCollapseProgress(float p, boolean live) {
        collapse.setProgress(p, live);
    }

    /** {@link CollapseController} 每推进一帧就回到这里：门面对应做的高度、位移与页头同步 */
    private void applyCollapseProgress(float p, boolean live) {
        int newH = CheckInCalendarState.heightAt(getFullHeightPx(), getWeekHeightPx(), p);
        if (newH != lastCollapseHeight) {
            lastCollapseHeight = newH;
            requestLayout();
        }
        updateCollapseTranslation();
        if (collapseProgressListener != null) {
            collapseProgressListener.onCollapseProgress(p, live);
        }
    }

    /** 选中那一周在收起后停在星期表头正下方；拖动中的邻页按它自己的选中行 */
    private void updateCollapseTranslation() {
        int cellH = currentCellHeightPx();
        float p = collapse.progress();
        cur.root.setTranslationY(-selectedRow(cur) * cellH * p);
        MonthPage nb = pager.neighborPage();
        if (nb != null) {
            nb.root.setTranslationY(-selectedRow(nb) * cellH * p);
        }
    }

    private int selectedRow(MonthPage page) {
        int index = page.indexOf(CheckInMonthGrid.clampDayKey(selectedDayKey, page.yearMonth));
        return index < 0 ? 0 : index / CheckInMonthGrid.DAYS_PER_WEEK;
    }

    /** 格高只在角色/配置变化时重取，拖动期间每帧都查资源是白给 */
    private int currentCellHeightPx() {
        if (cachedCellH < 0) {
            cachedCellH = getResources().getDimensionPixelSize(role == MonthRole.PICKER
                    ? R.dimen.checkin_cell_h_picker : R.dimen.checkin_cell_h);
        }
        return cachedCellH;
    }

    private int headerHeightPx() {
        if (cachedHeaderH > 0) {
            return cachedHeaderH;
        }
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

    /** 手指接管前打断收尾动画：进度停在哪儿就从哪儿继续跟手 */
    public void cancelCollapseAnim() {
        collapse.cancel();
    }

    public int getYearMonth() {
        return yearMonth;
    }

    public int getSelectedDayKey() {
        return selectedDayKey;
    }

    public int getDayCellCount() {
        return cur.count();
    }

    @Nullable
    public DayCellView getCellAt(int index) {
        return cur.cellAt(index);
    }

    public int getDayKeyAt(int index) {
        return cur.dayKeyAt(index);
    }

    /** 跨天/从详情页返回时只更新「今天 / 未来」分界，不抢走用户选中的日期 */
    public void refreshTodayKey() {
        final int prev = todayKey;
        final int now = DateKeys.todayKey();
        if (prev == now) {
            return;
        }
        todayKey = now;
        // 通常只差相邻两天：旧「今天」圈与新「今天」圈各重绑一次即可；
        // 跨月/跨年时旧日期不在本月，退回整表
        if (CheckInMonthGrid.yearMonthOf(prev) == yearMonth
                && CheckInMonthGrid.yearMonthOf(now) == yearMonth) {
            refreshCells(new int[]{prev, now});
        } else {
            refreshAllCells();
        }
    }

    /** 月↔年切换/切月动画期间暂停格子的打卡点入场动画，保持帧率 */
    public void suspendDotAnimations(boolean suspended) {
        dotSuspended = suspended;
        cur.setDotSuspended(suspended);
        MonthPage nb = pager.neighborPage();
        if (nb != null) {
            nb.setDotSuspended(suspended);
        }
    }

    /**
     * 选中日期格中心相对动画容器的坐标（px），月↔年切换拿它当缩放中心，
     * 不是屏幕中心。找不到格子时退回容器中心。
     */
    public int[] selectedCellCenterIn(View host) {
        int[] hostLoc = new int[2];
        host.getLocationInWindow(hostLoc);
        DayCellView cell = cur.cellAt(cur.indexOf(selectedDayKey));
        if (cell != null) {
            int[] loc = new int[2];
            cell.getLocationInWindow(loc);
            return new int[]{loc[0] + cell.getWidth() / 2 - hostLoc[0],
                    loc[1] + cell.getHeight() / 2 - hostLoc[1]};
        }
        return new int[]{host.getWidth() / 2, host.getHeight() / 2};
    }

    // ==================== 选择器模式（v5.9.1 第三步）====================

    /**
     * 切角色（v5.9.1 修订第三步）。MonthRole.PICKER 时格子改成勾选/框选，
     * 打卡页的 onSelectDay 等回调不再触发；切回 MonthRole.CHECKIN 行为回到原样。
     */
    public void setRole(int role) {
        this.role = role == MonthRole.PICKER ? MonthRole.PICKER : MonthRole.CHECKIN;
        if (this.role == MonthRole.CHECKIN) {
            picker.setRangeMode(false);
        }
        cachedCellH = -1; // 角色对应不同格高（选择器 48dp / 打卡 56dp）
        rebuildStructure();
        updatePickerHeading();
    }

    public void setPickerListener(@Nullable PickerListener l) {
        this.pickerListener = l;
    }

    /** 已选集合（实心胶囊 + 右下点） */
    public void setPickedDays(@Nullable Set<Integer> keys) {
        picker.setPicked(keys);
        if (role == MonthRole.PICKER) {
            refreshAllCells();
            updatePickerHeading();
        }
    }

    /** 原模糊区间的参考底纹：浅底、不算已选，点它一下才变已选 */
    public void setReferenceDays(@Nullable Set<Integer> keys) {
        picker.setReference(keys);
        if (role == MonthRole.PICKER) {
            refreshAllCells();
        }
    }

    /** 框选起点（描边 + 主色底）；0 = 取消 */
    public void setRangeAnchor(int dayKey) {
        picker.setAnchor(dayKey);
        if (role == MonthRole.PICKER) {
            refreshAllCells();
        }
    }

    /** 「连续区间」开关：开着时第一点落锚、第二点填充/取消整段 */
    public void setRangeMode(boolean on) {
        picker.setRangeMode(on);
        if (role == MonthRole.PICKER) {
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
        if (role == MonthRole.PICKER) {
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
        if (role == MonthRole.PICKER) {
            setContentDescription("精确记录选择器，已选 " + picker.pickedKeys().size() + " 天");
        } else {
            setContentDescription(CheckInMonthGrid.titleOf(yearMonth) + "日历");
        }
    }

    /**
     * 节日表与打卡点一次合并：打卡点只重绑差异格；节日表内容变了才整表重绑
     * （节日名顶替农历小字，影响的是当日格里的另一行字）。
     */
    public void setMonthData(@Nullable HolidayCalendar cal, @Nullable Map<Integer, int[]> dayMarks) {
        final Map<Integer, int[]> old = cur.marks;
        final boolean holidayChanged = cur.holidays == null ? cal != null : !cur.holidays.equals(cal);
        cur.holidays = cal;
        cur.marks = dayMarks == null ? Collections.<Integer, int[]>emptyMap()
                : new HashMap<>(dayMarks);
        if (holidayChanged) {
            refreshAllCells();
        } else {
            refreshCells(MonthPage.diffKeys(old, cur.marks));
        }
    }

    /**
     * @param dayMarks dayKey → {打卡本数, 其中补卡本数}，由 CheckInDots.marks 组装
     */
    public void setCheckInMarks(@Nullable Map<Integer, int[]> dayMarks) {
        setMonthData(cur.holidays, dayMarks);
    }

    /** 年度节日表；null = 只显示农历。内容没变（网络结果与缓存一致）时不动格子 */
    public void setHolidays(@Nullable HolidayCalendar cal) {
        setMonthData(cal, cur.marks);
    }

    /** 邻月数据供给；打卡页拿它把 prefetch 好的快照直接铺到滑进来的那一页 */
    public void setNeighborDataProvider(@Nullable NeighborDataProvider provider) {
        neighborData = provider;
    }

    public void setSelectedDay(int dayKey) {
        int prev = selectedDayKey;
        selectedDayKey = dayKey;
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

    /**
     * v5.9.5 第 3 项：程序化切月（工具栏、年视图返回）走和手指一样的双层转场：
     * 建邻页 → 一整段滑到位 → 提交。旧的两段 110ms + 中点重建已经删掉。
     */
    public void shiftMonth(int delta) {
        if (delta == 0) {
            return;
        }
        pager.shiftMonth(delta);
    }

    /**
     * 固定高度 = 星期表头 + 6 行格高 + 内边距。切月只换格子内容，不再让
     * 5 行月份的月历比 6 行月份矮一截，列表起始位置也就不会跟着跳。
     *
     * <p>收周时按折叠进度 p 把自身高度在「整月」与「单周」之间插值，
     * 格子容器仍按整月高度测量并整体上移，多出来的行由父容器的裁剪去掉。
     *
     * <p>v5.9.5：结构没脏且宽度没变就不再 {@code super.onMeasure}——拖动时每帧
     * 只重算这一个插值高度，42 个格子的测量留给真正变了的那几帧。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (structureDirty || width != lastMeasuredWidth) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            structureDirty = false;
            lastMeasuredWidth = width;
            if (getChildCount() > 0) {
                cachedHeaderH = getChildAt(0).getMeasuredHeight();
            }
        }
        int height = CheckInCalendarState.heightAt(
                getFullHeightPx(), getWeekHeightPx(), collapse.progress());
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        lastCollapseHeight = height;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedHeaderH = -1; // 宽度变了表头高度可能跟着变，缓存作废
    }

    // ==================== 渲染 ====================

    /** 换掉当前页：建好新页再摘旧页，选一天、打一次卡都不会走这里 */
    private void rebuildStructure() {
        MonthPage old = cur;
        cur = newPage(yearMonth, null);
        clip.addView(cur.root, pageLp());
        if (old != null) {
            clip.removeView(old.root);
        }
        structureDirty = true;
        cachedCellH = -1;
        cachedHeaderH = -1;
        lastCollapseHeight = -1;
        updatePickerHeading();
        updateCollapseTranslation(); // 切月后选中行可能变了，周模式下同步平移
        requestLayout();
    }

    /**
     * 建一页并把状态贴好。
     *
     * @param data null = 当前页，沿用视图现在这份打卡点与节日表；邻页由
     *             {@link NeighborDataProvider} 给，缓存没命中就传空集合只铺日期
     */
    private MonthPage newPage(int ym, @Nullable MonthData data) {
        MonthData md = data != null ? data
                : new MonthData(cur == null ? null : cur.holidays,
                        cur == null ? null : cur.marks);
        MonthPage page = MonthPage.build(getContext(), ym, md,
                state(selectedKeyFor(ym), 0), this::onCellTouched, currentCellHeightPx());
        bindAll(page);
        return page;
    }

    /** 一轮重绑的输入快照；入场动画的判定不在这里 */
    private MonthRenderState state(int selectedKey, int entranceKey) {
        return new MonthRenderState(palette, lunar, role, todayKey, selectedKey, picker,
                dotSuspended, todayChecked, entranceKey);
    }

    /** 当前页用选中原值，邻页夹到它自己那个月 */
    private int selectedKeyOf(MonthPage page) {
        return selectedKeyFor(page.yearMonth);
    }

    private int selectedKeyFor(int ym) {
        return ym == yearMonth ? selectedDayKey : CheckInMonthGrid.clampDayKey(selectedDayKey, ym);
    }

    /**
     * 今天入场该不该播：这个「今天」已经播过就不再播；本页没有这一格、
     * 或者它在页面里是邻月灰显格，都不播（留给真正的那个月播）。
     */
    private int entranceFor(MonthPage page) {
        if (animatedTodayKey == todayKey || page.indexOf(todayKey) < 0
                || page.isAdjacent(todayKey)) {
            return 0;
        }
        return todayKey;
    }

    /** 整表重绑（节日表变了、选择器状态变了、跨月换了今天） */
    private void refreshAllCells() {
        bindAll(cur);
    }

    private void bindAll(MonthPage page) {
        int played = page.refreshAll(state(selectedKeyOf(page), entranceFor(page)));
        if (played != 0) {
            animatedTodayKey = played;
        }
    }

    /** 只重绑这几天所在的格子；不在本月的（邻月补齐格之外的日期）直接跳过 */
    private void refreshCells(int[] keys) {
        int played = cur.refreshCells(keys, state(selectedKeyOf(cur), entranceFor(cur)));
        if (played != 0) {
            animatedTodayKey = played;
        }
    }

    private void onCellTouched(MonthPage p, int index) {
        if (pager.isDragging() || index >= p.count()) {
            return; // 翻月拖动期间不落点击：手势接管时格子已经收到 CANCEL，这里是双保险
        }
        int key = p.dayKeyAt(index);
        boolean adjacent = p.isAdjacent(key);
        if (role == MonthRole.PICKER) {
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
        if (role == MonthRole.PICKER && picker.rangeMode() && picker.anchor() != 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                picker.clearPreview();
                refreshAllCells();
                return true;
            }
            int index = indexAt(ev.getX(), ev.getY());
            if (index >= 0) {
                picker.setPreview(picker.anchor(), cur.dayKeyAt(index));
                refreshAllCells();
                return true;
            }
        }
        return super.onHoverEvent(ev);
    }

    /** 坐标落在哪个格子（仅本月格）；落空返回 -1 */
    private int indexAt(float x, float y) {
        for (int i = 0; i < cur.count(); i++) {
            DayCellView cell = cur.cellAt(i);
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

    // ==================== 切月动画与手势 ====================

    /**
     * 分页器的另一面：pager 不认识角色、数据与测量，页的建/摘/转正都回到这里。
     * 邻页的根视图和当前页一样挂在 {@link #clip} 上，最多同时两页。
     */
    private final class PagerHost implements MonthPager.Host {

        @Override
        public MonthPage current() {
            return cur;
        }

        /** 邻页数据从供给方要，没命中就只铺日期，提交后由 fragment 补全 */
        @Override
        public MonthPage buildNeighbor(int targetYearMonth) {
            MonthData data = neighborData == null ? null : neighborData.dataFor(targetYearMonth);
            if (data == null) {
                data = new MonthData(cur.holidays, null);
            }
            MonthPage page = newPage(targetYearMonth, data);
            clip.addView(page.root, pageLp());
            structureDirty = true; // 新页要跟着下一次测量走一遍，否则尺寸是 0
            requestLayout();
            return page;
        }

        @Override
        public void discardPage(MonthPage page) {
            clip.removeView(page.root);
            structureDirty = true;
            requestLayout();
        }

        /** 邻页转正：夹选中日、重绑整页、摘掉旧页（新页本来就在 clip 上） */
        @Override
        public void adopt(MonthPage page) {
            MonthPage old = cur;
            cur = page;
            yearMonth = page.yearMonth;
            selectedDayKey = CheckInMonthGrid.clampDayKey(selectedDayKey, yearMonth);
            clip.removeView(old.root);
            structureDirty = true;
            refreshAllCells();
            updateCollapseTranslation();
            updatePickerHeading();
            requestLayout();
        }

        @Override
        public void setDotAnimationsSuspended(boolean suspended) {
            suspendDotAnimations(suspended);
        }

        @Override
        public boolean canPage() {
            return MonthGridView.this.canPage();
        }

        @Override
        public int yearMonth() {
            return yearMonth;
        }

        @Override
        public float pageWidthPx() {
            return pageWidth();
        }

        /** 提交之后才播报与回调：拖动过程中一次都不发 */
        @Override
        public void onMonthSettled() {
            fireMonthChanged();
            announceMonth();
        }
    }

    private int pageWidth() {
        int w = clip.getWidth() > 0 ? clip.getWidth()
                : getWidth() - getPaddingLeft() - getPaddingRight();
        return Math.max(1, w);
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
        // 测速表在派发前入表、派发后再收：抬手帧得先进去，pager 在派发里才算得出速度
        pager.track(ev);
        boolean handled = super.dispatchTouchEvent(ev);
        pager.finishTracking(ev);
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return pager.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return pager.onTouchEvent(ev);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pager.cancelTransition();
    }

    /** 选择器角色自己带月份按钮，不参与翻月拖动 */
    private boolean canPage() {
        return role == MonthRole.CHECKIN;
    }

    /**
     * 今天有没有打卡（第 9 项）：只重绑今天所在格，点由实心/空心切换。
     * 跨天、切月重建后的全部格子由 {@link #bindCell} 重新取值。
     */
    public void setTodayChecked(boolean checked) {
        if (todayChecked == checked) {
            return;
        }
        todayChecked = checked;
        refreshCells(new int[]{todayKey});
    }

    /**
     * 打卡/撤销后只重绑受影响的那一格（点与朗读文案），不重建整张网格。
     *
     * @param total    当天打卡的本数（0 = 清空该格）
     * @param backfill 其中补卡的本数
     */
    public void setDayMarks(int dayKey, int total, int backfill) {
        Map<Integer, int[]> copy = new HashMap<>(cur.marks);
        if (total > 0) {
            copy.put(dayKey, new int[]{total, backfill});
        } else {
            copy.remove(dayKey);
        }
        cur.marks = copy;
        refreshCells(new int[]{dayKey});
    }

    // ==================== 测试钩子 ====================

    /**
     * 把「今天」按到指定月份重建一遍（邻月今天朗读用例）。
     *
     * <p>这条用例原先用反射写私有 {@code todayKey}/{@code yearMonth} 再调私有
     * {@code rebuildStructure}/{@code refreshAllCells}；P2 把绑定搬进 {@link MonthPage}
     * 之后反射会指空，所以换成显式钩子。重建本身已经整页贴过一遍状态，
     * 原先补的那次 refreshAllCells 是多余的。
     */
    @VisibleForTesting
    public void rebuildWithTodayForTest(int today, int yearMonth) {
        this.todayKey = today;
        this.yearMonth = yearMonth;
        rebuildStructure();
    }

    @VisibleForTesting
    public boolean hasNeighborPage() {
        return pager.hasNeighbor();
    }

    @VisibleForTesting
    public float pagerOffsetPx() {
        return pager.offsetPx();
    }

    @VisibleForTesting
    public float neighborPageTranslationXPx() {
        return pager.neighborTranslationXPx();
    }

    /** 视图没挂窗口时 Choreographer 可能不给帧，直接把提交/回弹动画走完 */
    @VisibleForTesting
    public void finishPagerAnimation() {
        pager.finishAnimation();
    }
}
