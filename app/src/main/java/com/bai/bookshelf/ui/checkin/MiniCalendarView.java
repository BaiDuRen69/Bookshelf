package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.ThemeManager;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * 年视图里的精简小日历：7×6 方格 + 打卡点，不画数字。
 * 独立实现（不复用也不改 PurchaseHeatmapView），今天用主色描边。
 * 每个格子都能点，点击坐标换算成真实 yyyyMMdd 回传给上层。
 */
public class MiniCalendarView extends View {

    public interface OnDayClick {
        void onDay(int dayKey);
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int year;
    private int month1;
    private int todayKey;
    private Set<Integer> checked = Collections.emptySet();
    private int[] keys = new int[0];
    private float cell;
    private float gap;
    private float offsetX;
    private float offsetY;
    // 主题色只在 bind 时解析一次：onDraw 每帧重解会拖慢年视图切换动画
    private int cachedOutline;
    private int cachedVariant;
    private int cachedPrimary;
    @Nullable
    private OnDayClick listener;

    public MiniCalendarView(Context ctx) {
        this(ctx, null);
    }

    public MiniCalendarView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        boxPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        setClickable(true);
    }

    public void setOnDayClick(@Nullable OnDayClick l) {
        this.listener = l;
    }

    public void bind(int year, int month1, Set<Integer> checkedDays, int todayKey) {
        this.year = year;
        this.month1 = month1;
        this.todayKey = todayKey;
        this.checked = checkedDays == null ? Collections.emptySet() : checkedDays;
        this.keys = CheckInMonthGrid.dayKeys(year, month1);
        int days = 0;
        for (int key : keys) {
            if (this.checked.contains(key)) {
                days++;
            }
        }
        setContentDescription(String.format(Locale.ROOT, "%d年%d月，打卡 %d 天",
                year, month1, days));
        cachedOutline = ThemeManager.resolveColor(getContext(), R.attr.outline);
        cachedVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        cachedPrimary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        gap = dp(2);
        cell = Math.min((w - gap * 6) / 7f, (h - gap * 5) / 6f);
        if (cell <= 0) {
            return;
        }
        offsetX = (w - (cell * 7 + gap * 6)) / 2f;
        offsetY = (h - (cell * 6 + gap * 5)) / 2f;

        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            float x = offsetX + (i % 7) * (cell + gap);
            float y = offsetY + (i / 7) * (cell + gap);
            boolean adjacent = CheckInMonthGrid.isAdjacent(key, year, month1);

            boxPaint.setColor(adjacent ? cachedVariant : cachedOutline);
            boxPaint.setAlpha(adjacent ? 40 : 70);
            canvas.drawRoundRect(x, y, x + cell, y + cell, dp(2), dp(2), boxPaint);
            boxPaint.setAlpha(255);

            if (checked.contains(key)) {
                dotPaint.setColor(cachedPrimary);
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * 0.30f, dotPaint);
            }
            if (key == todayKey) {
                ringPaint.setColor(cachedPrimary);
                canvas.drawRoundRect(x, y, x + cell, y + cell, dp(2), dp(2), ringPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && cell > 0) {
            int col = (int) Math.floor((event.getX() - offsetX) / (cell + gap));
            int row = (int) Math.floor((event.getY() - offsetY) / (cell + gap));
            if (col >= 0 && col < 7 && row >= 0 && row < 6) {
                int index = row * 7 + col;
                if (index < keys.length && listener != null) {
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
