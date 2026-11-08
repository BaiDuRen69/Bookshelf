package com.bai.bookshelf.ui.checkin;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.CheckInHabit;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 年视图：一屏 12 个月的网格（3 列 × 4 行）。
 * 每格 = 可点可读屏的月份标题 + 精简小日历；左右滑切年份（左=下一年、右=上一年）。
 *
 * <p>两层卡片复用 + TransitionManager(Fade + Slide) 做切年：换年只 bind 数据，
 * 不再 removeAllViews 重建 12 张卡，动画期间不触发整表 measure/layout，帧率才稳。
 * 当前月用主题主色描边，其它月份标题走 on_surface_variant 灰字。
 */
public class CheckInYearView extends FrameLayout {

    public interface Listener {
        /** dayKey = 0 表示只定位月份；>0 同时选中该日 */
        void onPick(int yearMonth, int dayKey);

        void onYearChanged(int year);
    }

    /** 节日表来源：切年时新层同步取该年，网络结果回来后 refreshHolidays() 重绘 */
    public interface HolidaySource {
        HolidayCalendar forYear(int year);
    }

    private static final float SWIPE_DISTANCE_DP = 60f;
    private static final float MIN_FLING_VELOCITY = 200f;

    private final FrameLayout switcher;
    private final LinearLayout[] layers = new LinearLayout[2];
    private final View[][] cards = new View[2][13];
    private final TextView[][] titles = new TextView[2][13];
    private final MiniCalendarView[][] minis = new MiniCalendarView[2][13];
    private final GestureDetector detector;
    private final float density;

    private int activeLayer;
    private int year;
    private int minYear;
    private int maxYear;
    private int todayKey;
    private List<CheckIn> all = Collections.emptyList();
    @Nullable
    private HolidaySource holidaySource;
    @Nullable
    private Listener listener;
    @Nullable
    private CheckInViewSwitcher yearSwitcher;
    private boolean horizontal;

    public CheckInYearView(Context ctx) {
        this(ctx, null);
    }

    public CheckInYearView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        switcher = new FrameLayout(ctx);
        addView(switcher, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        for (int layer = 0; layer < 2; layer++) {
            layers[layer] = createLayer(layer);
            layers[layer].setVisibility(layer == 0 ? View.VISIBLE : View.GONE);
            switcher.addView(layers[layer], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        detector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (Math.abs(e2.getX() - e1.getX()) > dp(SWIPE_DISTANCE_DP)
                        && Math.abs(e2.getX() - e1.getX()) > Math.abs(e2.getY() - e1.getY())) {
                    horizontal = true;
                }
                return horizontal;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < dp(SWIPE_DISTANCE_DP)
                        || Math.abs(vx) < MIN_FLING_VELOCITY
                        || Math.abs(dx) < Math.abs(e2.getY() - e1.getY())) {
                    return false;
                }
                // 用户指定：左滑 = 年份 +1（下一年），右滑 = 年份 −1（上一年）
                shift(CheckInYearGrid.deltaForSwipe(dx));
                return true;
            }
        });
        todayKey = DateKeys.todayKey();
    }

    /** 一次建好 12 张卡片（4 行 × 3 列），之后换年只 bind，不重建 View */
    private LinearLayout createLayer(int layer) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int month1 = r * 3 + c + 1;
                LinearLayout card = new LinearLayout(getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(4), dp(4), dp(4), dp(4));
                card.setClickable(true);
                card.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onPick(year * 100 + month1, 0);
                    }
                });

                TextView title = new TextView(getContext());
                Typo.text(title, R.dimen.text_caption_large);
                title.setGravity(Gravity.START);
                title.setPadding(0, 0, 0, dp(2));
                card.addView(title, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                MiniCalendarView mini = new MiniCalendarView(getContext());
                mini.setOnDayClick(dayKey -> {
                    if (listener != null) {
                        // 小日历只有本月日号可点；回传的 key 就是该日真正所属的月份
                        listener.onPick(CheckInMonthGrid.yearMonthOf(dayKey), dayKey);
                    }
                });
                card.addView(mini, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

                cards[layer][month1] = card;
                titles[layer][month1] = title;
                minis[layer][month1] = mini;
                row.addView(card, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return root;
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    /** 切年动画由 CheckInViewSwitcher 用 TransitionManager 统一编排 */
    public void setYearSwitcher(@Nullable CheckInViewSwitcher switcher) {
        this.yearSwitcher = switcher;
    }

    public int getYear() {
        return year;
    }

    /** 当前激活层里某个月的卡片（年视图点月放大动画要拿它当缩放主体） */
    @Nullable
    public View monthCardAt(int month1) {
        return month1 >= 1 && month1 <= 12 ? cards[activeLayer][month1] : null;
    }

    public void bind(int year, int minYear, int maxYear,
                     List<CheckIn> checkIns, int todayKey) {
        this.year = year;
        this.minYear = minYear;
        this.maxYear = maxYear;
        this.all = checkIns == null ? Collections.emptyList() : new ArrayList<>(checkIns);
        this.todayKey = todayKey;
        // 上次转场被取消时卡片可能停在放大/半透明状态，重进年视图前先归位
        resetCardTransforms();
        renderLayer(activeLayer, year);
    }

    /** 12 个月卡片缩放/透明度/位移归位（两层都清，防止非激活层的残留带到下一次） */
    public void resetCardTransforms() {
        for (int layer = 0; layer < 2; layer++) {
            for (int month1 = 1; month1 <= 12; month1++) {
                View card = cards[layer][month1];
                if (card == null) {
                    continue;
                }
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setAlpha(1f);
                card.setTranslationX(0f);
                card.setTranslationY(0f);
            }
        }
    }

    public void setHolidaySource(@Nullable HolidaySource source) {
        this.holidaySource = source;
        if (year > 0) {
            renderLayer(activeLayer, year);
        }
    }

    /** 网络结果已写回 HolidayStore 内存后调用：只重绘当前激活层，不重建 View */
    public void refreshHolidays() {
        if (year > 0) {
            renderLayer(activeLayer, year);
        }
    }

    private void renderLayer(int layer, int forYear) {
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int variant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        Set<Integer> checked = new HashSet<>(CheckInHabit.dayKeys(all));
        HolidayCalendar cal = holidaySource == null
                ? HolidayCalendar.empty() : holidaySource.forYear(forYear);
        for (int month1 = 1; month1 <= 12; month1++) {
            boolean current = CheckInYearGrid.isCurrentMonth(forYear, month1, todayKey);
            View card = cards[layer][month1];
            TextView title = titles[layer][month1];
            card.setBackground(current ? currentCardBackground() : null);
            title.setText(month1 + "月");
            title.setTextColor(current ? primary : variant);
            title.setContentDescription(String.format(Locale.ROOT,
                    "%d年%d月，查看这个月", forYear, month1));
            minis[layer][month1].bind(forYear, month1, checked, todayKey, cal);
        }
    }

    /** 当前月：透明底 + 1dp 主题主色描边；其它月份不画底、标题走灰色 */
    private GradientDrawable currentCardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(getContext(), android.R.color.transparent));
        bg.setCornerRadius(getResources().getDimension(R.dimen.radius_sm));
        bg.setStroke(getResources().getDimensionPixelSize(R.dimen.checkin_year_card_stroke),
                ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY));
        return bg;
    }

    private void shift(int delta) {
        // 切年动画期间不再接第二次切年：两层还在路上时换年会把 activeLayer 与可见层拆开
        if (yearSwitcher != null && yearSwitcher.isAnimating()) {
            return;
        }
        if (CheckInYearGrid.boundaryHit(year, delta, minYear, maxYear)) {
            rubberBand(delta);
            return;
        }
        final int nextYear = CheckInYearGrid.shiftYear(year, delta, minYear, maxYear);
        final int nextLayer = 1 - activeLayer;
        // 隐藏层先填好新一年的数据，再交给 ViewSwitcher 做水平位移 + 淡入淡出
        renderLayer(nextLayer, nextYear);
        final View oldLayer = layers[activeLayer];
        final View newLayer = layers[nextLayer];
        activeLayer = nextLayer;
        year = nextYear;
        if (yearSwitcher != null) {
            yearSwitcher.switchYear(delta, oldLayer, newLayer,
                    () -> {
                        if (listener != null) {
                            listener.onYearChanged(year);
                        }
                    });
        } else {
            oldLayer.setVisibility(View.GONE);
            newLayer.setVisibility(View.VISIBLE);
            if (listener != null) {
                listener.onYearChanged(year);
            }
        }
    }

    /**
     * 直接跳到目标年（年视图标题快选用）：与左右滑共用同一套两层缓冲与转场。
     *
     * <p>动画期间不排队、不打断（与 {@link #shift(int)} 一致）；目标年夹在
     * 同一条 [minYear, maxYear] 区间内，已在该年则不动。
     */
    public void jumpToYear(int target) {
        if (yearSwitcher != null && yearSwitcher.isAnimating()) {
            return;
        }
        final int nextYear = Math.max(minYear, Math.min(maxYear, target));
        if (nextYear == year) {
            return;
        }
        final int delta = Integer.compare(nextYear, year);
        final int nextLayer = 1 - activeLayer;
        renderLayer(nextLayer, nextYear);
        final View oldLayer = layers[activeLayer];
        final View newLayer = layers[nextLayer];
        activeLayer = nextLayer;
        year = nextYear;
        if (yearSwitcher != null) {
            yearSwitcher.switchYear(delta, oldLayer, newLayer,
                    () -> {
                        if (listener != null) {
                            listener.onYearChanged(nextYear);
                        }
                    });
        } else {
            oldLayer.setVisibility(View.GONE);
            newLayer.setVisibility(View.VISIBLE);
            if (listener != null) {
                listener.onYearChanged(nextYear);
            }
        }
    }

    /** 越界时不换年，做一次 24dp 回弹（Decelerate）：左滑往左弹、右滑往右弹 */
    private void rubberBand(int delta) {
        float distance = dp(CheckInYearGrid.RUBBER_BAND_DP) * (delta < 0 ? 1f : -1f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, distance, 0f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> switcher.setTranslationX((float) a.getAnimatedValue()));
        anim.start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 上一轮的手势标记可能因漏掉 UP/CANCEL 残留：新手势从按下开始重新判定
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            horizontal = false;
        }
        detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return horizontal || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            horizontal = false;
        }
        return horizontal || super.onTouchEvent(ev);
    }

    private float dp(float value) {
        return value * density;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
