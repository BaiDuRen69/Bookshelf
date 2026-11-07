package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.MiniMonthLayout;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * 年视图里的精简小日历：顶部星期行 + 6×7 日号网格 + 打卡点。
 * 邻月不画数字（空白格），只有本月日号响应点击。
 *
 * <p>颜色优先级：今天（主色圆圈 + 主色字）＞ 节日（checkin_holiday_text）＞
 * 本月（主色）＞ 其他月（on_surface_variant）。主题色与节假日都在 bind 时
 * 算一次，onDraw 只做绘制，切年动画不掉帧。周末不再自动着色。
 */
public class MiniCalendarView extends View {

    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};

    public interface OnDayClick {
        void onDay(int dayKey);
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int todayKey;
    // 只有今天所在的那张卡用主色，其他月份退到 variant（验收：其他月退后）
    private boolean currentMonth;
    private Set<Integer> checked = Collections.emptySet();
    private int[] keys = new int[MiniMonthLayout.CELLS];
    private final boolean[] holiday = new boolean[MiniMonthLayout.CELLS];
    private float cell;
    private float offsetX;
    private float gridTop;
    // 主题色只在 bind 时解析一次：onDraw 每帧重解会拖慢年视图切换动画
    private int cachedPrimary;
    private int cachedVariant;
    private int cachedHoliday;
    private int cachedTodayBg;
    @Nullable
    private OnDayClick listener;

    public MiniCalendarView(Context ctx) {
        this(ctx, null);
    }

    public MiniCalendarView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        dotPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        setClickable(true);
    }

    public void setOnDayClick(@Nullable OnDayClick l) {
        this.listener = l;
    }

    /** holidays 为 null 时不做节日着色；周末不在节日表里，因此不再自动变色 */
    public void bind(int year, int month1, Set<Integer> checkedDays,
                     int todayKey, @Nullable HolidayCalendar holidays) {
        this.todayKey = todayKey;
        this.currentMonth = todayKey / 100 == year * 100 + month1;
        this.checked = checkedDays == null ? Collections.emptySet() : checkedDays;
        this.keys = MiniMonthLayout.cells(year, month1);
        Arrays.fill(holiday, false);
        int days = 0;
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            if (key == 0) {
                continue;
            }
            holiday[i] = holidays != null && holidays.isHoliday(key);
            if (this.checked.contains(key)) {
                days++;
            }
        }
        setContentDescription(String.format(Locale.ROOT, "%d年%d月，打卡 %d 天",
                year, month1, days));
        cachedPrimary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        cachedVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        cachedHoliday = ThemeManager.resolveColor(getContext(), R.attr.checkin_holiday_text);
        cachedTodayBg = ThemeManager.resolveColor(getContext(), R.attr.checkin_today_bg);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float labelSize = Typo.paintPx(getContext(), R.dimen.text_mini);
        float daySize = Typo.paintPx(getContext(), R.dimen.text_micro);
        gridTop = labelSize * 1.4f;
        cell = Math.min(w / 7f, (h - gridTop) / 6f);
        if (cell <= 0) {
            return;
        }
        offsetX = (w - cell * 7f) / 2f;

        textPaint.setTextSize(labelSize);
        textPaint.setColor(cachedVariant);
        float labelBaseline = gridTop - labelSize * 0.3f;
        for (int c = 0; c < 7; c++) {
            canvas.drawText(WEEKDAYS[c], offsetX + cell * (c + 0.5f), labelBaseline, textPaint);
        }

        textPaint.setTextSize(daySize);
        float numberBaseline = daySize * 0.35f;
        float dotOffset = daySize * 0.75f;
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            if (key == 0) {
                continue;
            }
            float cx = offsetX + cell * ((i % 7) + 0.5f);
            float cy = gridTop + cell * ((i / 7) + 0.5f);
            boolean today = key == todayKey;
            int color;
            if (today) {
                // 年视图没有实心圆，只画圈：圈与数字都取「今天」语义色，和月视图一致
                color = cachedTodayBg;
            } else if (holiday[i]) {
                color = cachedHoliday;
            } else if (currentMonth) {
                color = cachedPrimary;
            } else {
                color = cachedVariant;
            }
            textPaint.setColor(color);
            canvas.drawText(String.valueOf(key % 100), cx, cy + numberBaseline, textPaint);
            float ringRadius = cell / 2f - dp(1.5f);
            if (checked.contains(key)) {
                dotPaint.setColor(cachedPrimary);
                // 今天有外环，点画在环外下方，否则会被环的描边盖住
                float dotY = today ? cy + ringRadius + dp(1.8f) : cy + dotOffset;
                canvas.drawCircle(cx, dotY, dp(1f), dotPaint);
            }
            if (today) {
                ringPaint.setColor(cachedTodayBg);
                if (ringRadius > 0) {
                    canvas.drawCircle(cx, cy, ringRadius, ringPaint);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && cell > 0) {
            int col = (int) Math.floor((event.getX() - offsetX) / cell);
            int row = (int) Math.floor((event.getY() - gridTop) / cell);
            if (col >= 0 && col < 7 && row >= 0 && row < 6) {
                int index = row * 7 + col;
                // 空白格（邻月）不映射点击
                if (index < keys.length && keys[index] != 0 && listener != null) {
                    listener.onDay(keys[index]);
                }
                performClick();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float dp(float value) {
        return value * density;
    }
}
