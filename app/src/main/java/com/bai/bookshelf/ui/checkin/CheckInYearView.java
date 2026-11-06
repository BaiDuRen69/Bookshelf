package com.bai.bookshelf.ui.checkin;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 年视图：垂直滚动的 12 个月网格（2 列 × 6 行）。
 * 每格 = 可点可读屏的月份标题 + 精简小日历；左右滑切年份（左=上一年、右=下一年）。
 * 越界用 24dp 橡皮筋回弹，不换年。不引入 ViewPager2 / 日历库。
 */
public class CheckInYearView extends ScrollView {

    public interface Listener {
        /** dayKey = 0 表示只定位月份；>0 同时选中该日 */
        void onPick(int yearMonth, int dayKey);

        void onYearChanged(int year);
    }

    private static final float SWIPE_DISTANCE_DP = 60f;
    private static final float MIN_FLING_VELOCITY = 200f;

    private final LinearLayout rows;
    private final GestureDetector detector;
    private final float density;

    private int year;
    private int minYear;
    private int maxYear;
    private int todayKey;
    private List<CheckIn> all = Collections.emptyList();
    @Nullable
    private Listener listener;
    private boolean horizontal;

    public CheckInYearView(Context ctx) {
        this(ctx, null);
    }

    public CheckInYearView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
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
                // 用户指定：左滑 = 年份 −1，右滑 = 年份 +1（与常见日历方向相反）
                shift(CheckInYearGrid.deltaForSwipe(dx));
                return true;
            }
        });
        todayKey = DateKeys.todayKey();
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    public int getYear() {
        return year;
    }

    public void bind(int year, int minYear, int maxYear,
                     List<CheckIn> checkIns, int todayKey) {
        this.year = year;
        this.minYear = minYear;
        this.maxYear = maxYear;
        this.all = checkIns == null ? Collections.emptyList() : new ArrayList<>(checkIns);
        this.todayKey = todayKey;
        render();
    }

    private void render() {
        rows.removeAllViews();
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int container = ThemeManager.resolveColor(getContext(), R.attr.primary_container);
        int onSurface = ThemeManager.resolveColor(getContext(), R.attr.on_surface);
        Set<Integer> checked = new HashSet<>(com.bai.bookshelf.util.CheckInHabit.dayKeys(all));

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                final int month1 = r * 2 + c + 1;
                final int ym = year * 100 + month1;
                boolean current = CheckInYearGrid.isCurrentMonth(year, month1, todayKey);

                LinearLayout card = new LinearLayout(getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(8), dp(8), dp(8), dp(8));
                card.setBackgroundColor(current ? container : 0);
                card.setClickable(true);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cardLp.setMargins(dp(4), dp(4), dp(4), dp(4));

                TextView title = new TextView(getContext());
                title.setText(month1 + "月");
                Typo.text(title, R.dimen.text_body_small);
                title.setTextColor(current ? primary : onSurface);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, 0, 0, dp(4));
                title.setContentDescription(String.format(Locale.ROOT,
                        "%d年%d月，查看这个月", year, month1));
                title.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onPick(ym, 0);
                    }
                });
                card.addView(title, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                MiniCalendarView mini = new MiniCalendarView(getContext());
                mini.bind(year, month1, checked, todayKey);
                mini.setOnDayClick(dayKey -> {
                    if (listener != null) {
                        // 小日历含邻月补齐格：点到邻月就定位到那一天真正所属的月份
                        listener.onPick(CheckInMonthGrid.yearMonthOf(dayKey), dayKey);
                    }
                });
                card.addView(mini, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
                row.addView(card, cardLp);
            }
            rows.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void shift(int delta) {
        if (CheckInYearGrid.boundaryHit(year, delta, minYear, maxYear)) {
            rubberBand(delta);
            return;
        }
        year = CheckInYearGrid.shiftYear(year, delta, minYear, maxYear);
        render();
        scrollTo(0, 0);
        if (listener != null) {
            listener.onYearChanged(year);
        }
    }

    /** 越界时不换年，做一次 24dp 回弹 */
    private void rubberBand(int delta) {
        float distance = dp(CheckInYearGrid.RUBBER_BAND_DP) * (delta < 0 ? -1f : 1f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, distance, 0f);
        anim.setDuration(220);
        anim.addUpdateListener(a -> rows.setTranslationX((float) a.getAnimatedValue()));
        anim.start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
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
