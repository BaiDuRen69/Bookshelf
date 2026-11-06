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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final long ANIM_HALF_MS = 110; // 两段共 220ms
    private static final float SWIPE_DISTANCE_DP = 60f;
    private static final float MIN_FLING_VELOCITY = 200f;

    private final LinearLayout grid;
    private final GestureDetector detector;
    private final float density;

    private int yearMonth;                 // yyyymm
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
        render();
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

    /** @param dayMarks dayKey → {打卡本数, 其中补卡本数}，由 CheckInDots.marks 组装 */
    public void setCheckInMarks(@Nullable Map<Integer, int[]> dayMarks) {
        marks = dayMarks == null ? Collections.emptyMap() : new HashMap<>(dayMarks);
        render();
    }

    public void setSelectedDay(int dayKey) {
        selectedDayKey = dayKey;
        render();
    }

    /** 无动画定位（「今」按钮与年视图点月回来用） */
    public void setYearMonth(int ym) {
        if (ym == yearMonth) {
            return;
        }
        yearMonth = ym;
        render();
        announceMonth();
        fireMonthChanged();
    }

    public void moveToToday() {
        int today = DateKeys.todayKey();
        todayKey = today;
        selectedDayKey = today;
        setYearMonth(CheckInMonthGrid.yearMonthOf(today));
    }

    public void shiftMonth(int delta) {
        int target = CheckInMonthGrid.shiftMonth(yearMonth, delta);
        animateToMonth(target, delta > 0);
    }

    // ==================== 渲染 ====================

    private void render() {
        grid.removeAllViews();
        java.util.Arrays.fill(cells, null);
        int y = CheckInMonthGrid.yearOf(yearMonth);
        int m = CheckInMonthGrid.monthOf(yearMonth);
        dayKeys = CheckInMonthGrid.dayKeys(y, m);
        int[] weeks = CheckInMonthGrid.weekNumbers(dayKeys);
        int rowCount = weeks.length;

        // nonTransitiveRClass=true：项目 R.attr 不含 appcompat 的 colorPrimary，
        // 走 ThemeManager 暴露的 androidx.appcompat.R.attr.colorPrimary
        resolveCellColors();
        int variant = colorVariant;

        for (int r = 0; r < rowCount; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            TextView week = weekLabel(String.valueOf(weeks[r]));
            week.setTextColor(variant);
            week.setContentDescription(String.format(Locale.ROOT, "第 %d 周", weeks[r]));
            row.addView(week, weekColumnLp());

            for (int c = 0; c < CheckInMonthGrid.DAYS_PER_WEEK; c++) {
                int index = r * CheckInMonthGrid.DAYS_PER_WEEK + c;
                int key = dayKeys[index];
                boolean adjacent = CheckInMonthGrid.isAdjacent(key, y, m);
                boolean today = key == todayKey;
                boolean selected = key == selectedDayKey;
                boolean future = key > todayKey;
                int total = CheckInDots.total(marks, key);
                // 邻月永远不画点：不然会被读成「这个月打过卡」
                int backfill = adjacent ? 0 : CheckInDots.backfill(marks, key);

                DayCellView cell = new DayCellView(getContext());
                cell.bindCheckIn(key % 100, colorPrimary, colorSelectedBg, colorSelectedStroke,
                        colorOnSurface, variant, selected, today, adjacent, future,
                        adjacent ? 0 : total, backfill);
                cell.setContentDescription(
                        describe(key, today, total, backfill, adjacent, selected, future));
                cell.setOnClickListener(v -> {
                    if (scrolling) {
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
                });
                cells[index] = cell;
                row.addView(cell, new LinearLayout.LayoutParams(0,
                        getResources().getDimensionPixelSize(R.dimen.checkin_cell_h), 1f));
            }
            grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }
        setContentDescription(CheckInMonthGrid.titleOf(yearMonth) + "日历");
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
                render();
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
        int y = CheckInMonthGrid.yearOf(yearMonth);
        int m = CheckInMonthGrid.monthOf(yearMonth);
        for (int i = 0; i < dayKeys.length; i++) {
            if (dayKeys[i] != dayKey) {
                continue;
            }
            DayCellView cell = cells[i];
            if (cell == null) {
                return;
            }
            boolean adjacent = CheckInMonthGrid.isAdjacent(dayKey, y, m);
            boolean today = dayKey == todayKey;
            boolean selected = dayKey == selectedDayKey;
            boolean future = dayKey > todayKey;
            int shownTotal = adjacent ? 0 : total;
            int shownBackfill = adjacent ? 0 : backfill;
            cell.bindCheckIn(dayKey % 100, colorPrimary, colorSelectedBg, colorSelectedStroke,
                    colorOnSurface, colorVariant, selected, today, adjacent, future,
                    shownTotal, shownBackfill);
            cell.setContentDescription(
                    describe(dayKey, today, total, backfill, adjacent, selected, future));
            return;
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
