package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 迷你条形图（v2.8「我的阅读」页用）：一行等宽槽位，每槽一根柱子 +
 * 柱顶数值 + 底部标签。数据量小（≤31 根），直接 onDraw 手绘，不引第三方图表库。
 */
public class MiniBarChartView extends View {

    private List<String> labels = new ArrayList<>();
    private List<Integer> values = new ArrayList<>();

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MiniBarChartView(Context context) {
        this(context, null);
    }

    public MiniBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        textPaint.setColor(0x99000000); // 底部标签：半透明黑（浅色主题）
        textPaint.setTextSize(sp(9));
        textPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(0xCC000000);
        valuePaint.setTextSize(sp(10));
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 设置数据（labels 与 values 等长；value 为购买/录入本数） */
    public void setData(List<String> labels, List<Integer> values) {
        this.labels = labels != null ? labels : new ArrayList<>();
        this.values = values != null ? values : new ArrayList<>();
        requestLayout();
        invalidate();
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        int max = 0;
        for (int v : values) {
            if (v > max) {
                max = v;
            }
        }
        if (values.isEmpty() || max == 0) {
            // 无数据：居中提示
            textPaint.setTextSize(sp(12));
            Paint empty = new Paint(textPaint);
            empty.setColor(0x99000000);
            empty.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("暂无数据", w / 2f, h / 2f - sp(4), empty);
            return;
        }

        float padTop = sp(16);   // 留给柱顶数值
        float padBottom = sp(16); // 留给底部标签
        float chartH = h - padTop - padBottom;
        int n = values.size();
        float slot = w / (float) n;
        float barW = Math.min(slot * 0.55f, dp(20));

        barPaint.setColor(ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY));

        for (int i = 0; i < n; i++) {
            int v = values.get(i);
            float cx = slot * i + slot / 2f;
            if (v > 0) {
                float barH = chartH * v / max;
                canvas.drawRect(cx - barW / 2f, padTop + chartH - barH,
                        cx + barW / 2f, padTop + chartH, barPaint);
                canvas.drawText(String.valueOf(v), cx, padTop + chartH - barH - sp(3), valuePaint);
            }
            if (labels.size() > i && labels.get(i) != null) {
                canvas.drawText(labels.get(i), cx, h - sp(3), textPaint);
            }
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
