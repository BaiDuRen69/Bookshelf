package com.bai.bookshelf.ui.checkin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInDots;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

        /** 月份变了：外部更新标题 / 重新取数 */
        void onMonthChanged(int yearMonth);

        /** 点未来日：只说明原因，选中态不动 */
        void onFutureDay(int dayKey);
    }

    /**
     * 选择器模式（v5.9.1 第三步）：给编辑页框选「哪几天读过」。
     *
     * <p>与 {@link Listener} 完全分开——打卡页的四个回调一个都没动，
     * 打开选择器后走这套，关掉后回到原来那套。
     */
    public interface PickListener {
        /** 每次勾选变化后回调，参数是升序去重的完整日集合 */
        void onPickChanged(List<Integer> dayKeys);

        /** 点了不该点的日子：{@code future} = 未来日（还没发生），false = 邻月（属于别的月份） */
        void onPickBlocked(int dayKey, boolean future);

        /** 长按设/取消框选起点（0 = 已取消） */
        void onPickAnchor(int dayKey);
    }

    private static final long ANIM_HALF_MS = 110; // 两段共 220ms
    private static final float SWIPE_DISTANCE_DP = 60f;
    private static final float MIN_FLING_VELOCITY = 200f;

    private final LinearLayout grid;
    private final GestureDetector detector;
    private final float density;

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
    @Nullable
    private Listener listener;
    // ===== 选择器模式（默认关；关掉后与打卡页一字不差）=====
    private boolean pickMode;
    @Nullable
    private PickListener pickListener;
    /** 已选入的阅读日（升序去重） */
    private final Set<Integer> picked = new TreeSet<>();
    /** 框选起点：0 = 没有 */
    private int anchorKey;
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
        ViewCompat.setAccessibilityHeading(this, true);

        addView(buildHeader(), new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        grid = new LinearLayout(ctx);
        grid.setOrientation(VERTICAL);
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

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

    // ==================== 选择器模式（v5.9.1 第三步）====================

    /**
     * 打开/关闭选择器模式。
     *
     * <p>打开时打卡页的「点一天看那天读了哪几本」不再触发，格子改成勾选；
     * 关闭（传 null）后行为回到原样，包括已有的选中日。
     *
     * @param initial 初始已选日集合，null = 空集合
     */
    public void setPicker(@Nullable Collection<Integer> initial, @Nullable PickListener l) {
        pickListener = l;
        pickMode = l != null;
        anchorKey = 0;
        picked.clear();
        if (initial != null) {
            for (Integer key : initial) {
                if (key != null && key > 0) {
                    picked.add(key);
                }
            }
        }
        // 开与关都要重贴一遍：锚点的空胶囊、勾选的实心胶囊，两种模式画法不同
        refreshAllCells();
    }

    /** 外部改了集合（撤销、整段沿用）后刷新显示 */
    public void setPickedDays(@Nullable Collection<Integer> keys) {
        picked.clear();
        if (keys != null) {
            for (Integer key : keys) {
                if (key != null && key > 0) {
                    picked.add(key);
                }
            }
        }
        if (pickMode) {
            refreshAllCells();
        }
    }

    /** 当前已选日（升序去重副本，调用方改不动内部集合） */
    public List<Integer> getPickedDays() {
        return new ArrayList<>(picked);
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
    }

    /** 无动画定位（「今」按钮与年视图点月回来用） */
    public void setYearMonth(int ym) {
        if (ym == yearMonth) {
            return;
        }
        yearMonth = ym;
        rebuildStructure();
        refreshAllCells();
        announceMonth();
        fireMonthChanged();
    }

    public void moveToToday() {
        int today = DateKeys.todayKey();
        todayKey = today;
        selectedDayKey = today;
        if (yearMonth == CheckInMonthGrid.yearMonthOf(today)) {
            // 月没变，但「今天/未来」的分界挪了，整表状态要重贴一遍
            refreshAllCells();
        } else {
            setYearMonth(CheckInMonthGrid.yearMonthOf(today));
        }
    }

    public void shiftMonth(int delta) {
        int target = CheckInMonthGrid.shiftMonth(yearMonth, delta);
        animateToMonth(target, delta > 0);
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
                // 长按只在选器模式下有意义；打卡页返回 false，长按行为与以前一样没有副作用
                cell.setOnLongClickListener(v -> pickMode && onCellLongTouched(cellIndex));
                cells[index] = cell;
                row.addView(cell, new LinearLayout.LayoutParams(0,
                        getResources().getDimensionPixelSize(R.dimen.checkin_cell_h), 1f));
            }
            grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }
        setContentDescription(CheckInMonthGrid.titleOf(yearMonth) + "日历");
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
        if (pickMode) {
            // 选器模式：胶囊表示「这一天读过」，锚点表示「从这天开始框」，
            // 打卡点一律不画——胶囊已经把信息说完了，再叠点只会让人以为有两套状态。
            boolean pickedNow = picked.contains(key);
            cell.bindCheckIn(key % 100, colorPrimary, colorSelectedBg, colorSelectedStroke,
                    colorOnSurface, colorVariant, pickedNow, today, adjacent, future,
                    0, 0, key == anchorKey);
            cell.setContentDescription(
                    describePick(key, today, adjacent, future, pickedNow, key == anchorKey));
            return;
        }
        cell.bindCheckIn(key % 100, colorPrimary, colorSelectedBg, colorSelectedStroke,
                colorOnSurface, colorVariant, key == selectedDayKey, today, adjacent, future,
                adjacent ? 0 : total, backfill, false);
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
        if (pickMode) {
            onPickTouched(key, adjacent);
            return;
        }
        if (adjacent) {
            if (listener != null) {
                listener.onMoveToDay(key);
            }
        } else if (key > todayKey) {
            // 未来日：只说明原因，选中态不动，别再让列表拿这一天去被拒绝
            if (listener != null) {
                listener.onFutureDay(key);
            }
        } else {
            setSelectedDay(key);
            if (listener != null) {
                listener.onSelectDay(key);
            }
        }
    }

    /** 选器模式下的单击：有锚点就框住这一段，没锚点就勾/取消这一天 */
    private void onPickTouched(int key, boolean adjacent) {
        if (adjacent || key > todayKey) {
            // 邻月属于别的月份、未来日还没发生，都不该被算成「读过」
            if (pickListener != null) {
                pickListener.onPickBlocked(key, key > todayKey);
            }
            return;
        }
        if (anchorKey != 0 && anchorKey != key) {
            // 框选：两端之间整段选入，尾巴超过今天的部分丢掉（今天之后还没发生）
            int from = Math.min(anchorKey, key);
            int to = Math.min(Math.max(anchorKey, key), todayKey);
            if (to >= from) {
                picked.addAll(DateKeys.keyRange(from, to));
            }
            clearAnchor();
            refreshAllCells();
        } else {
            if (!picked.remove(key)) {
                picked.add(key);
            }
            clearAnchor();
            refreshCells(new int[]{key});
        }
        firePickChanged();
    }

    /** 锚点用完就清，并告诉调用方——编辑页那句「再点一天就框住这段」得跟着消失 */
    private void clearAnchor() {
        if (anchorKey == 0) {
            return;
        }
        anchorKey = 0;
        if (pickListener != null) {
            pickListener.onPickAnchor(0);
        }
    }

    /** 长按设框选起点；再长按同一天取消 */
    private boolean onCellLongTouched(int index) {
        if (scrolling || index >= dayKeys.length) {
            return false;
        }
        int key = dayKeys[index];
        boolean adjacent = CheckInMonthGrid.isAdjacent(key,
                CheckInMonthGrid.yearOf(yearMonth), CheckInMonthGrid.monthOf(yearMonth));
        if (adjacent || key > todayKey) {
            if (pickListener != null) {
                pickListener.onPickBlocked(key, key > todayKey);
            }
            return true;
        }
        int prev = anchorKey;
        anchorKey = anchorKey == key ? 0 : key;
        refreshCells(new int[]{prev, key});
        if (pickListener != null) {
            pickListener.onPickAnchor(anchorKey);
        }
        return true;
    }

    private void firePickChanged() {
        if (pickListener != null) {
            pickListener.onPickChanged(new ArrayList<>(picked));
        }
    }

    /** 选器模式的朗读：「11月3日，已选入」/「11月3日，未选」，锚点与今天一起念出来 */
    private String describePick(int dayKey, boolean today, boolean adjacent, boolean future,
                                boolean pickedNow, boolean anchor) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayKey / 100 % 100).append("月").append(dayKey % 100).append("日");
        if (adjacent) {
            sb.append("，邻月");
        }
        if (today) {
            sb.append("，今天");
        }
        if (future) {
            sb.append("，未来日期");
        }
        if (anchor) {
            sb.append("，框选起点");
        }
        sb.append(pickedNow ? "，已选入" : "，未选");
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
        float lift = getResources().getDimensionPixelSize(R.dimen.checkin_swipe_lift);
        ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(ANIM_HALF_MS);
        out.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            grid.setAlpha(1f - f);
            grid.setTranslationX(dir * distance * f);
            // 横向位移之外再坠 4dp：只平移会像整块被推走，加一点纵向才像「翻过去」
            grid.setTranslationY(lift * f);
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                yearMonth = target;
                rebuildStructure();
                refreshAllCells();
                grid.setAlpha(0f);
                grid.setTranslationX(-dir * distance);
                grid.setTranslationY(-lift);
                ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
                in.setDuration(ANIM_HALF_MS);
                in.addUpdateListener(b -> {
                    float g = (float) b.getAnimatedValue();
                    grid.setAlpha(g);
                    grid.setTranslationX(-dir * distance * (1f - g));
                    grid.setTranslationY(-lift * (1f - g));
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
            listener.onMonthChanged(yearMonth);
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
