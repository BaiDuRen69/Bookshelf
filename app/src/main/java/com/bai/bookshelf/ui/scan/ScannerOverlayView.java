package com.bai.bookshelf.ui.scan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.bai.bookshelf.util.ThemeManager;

/**
 * 扫码取景覆盖层（v4.25）：圆角黄铜取景框 + 四角描边 + 缓慢扫过的光带。
 * 相机预览仍在底层，本层只负责压暗框外区域与绘制装饰。
 */
public class ScannerOverlayView extends View {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path outside = new Path();
    private LinearGradient lineGradient;
    private float progress;
    private ValueAnimator scanAnimator;

    public ScannerOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        int brass = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_SECONDARY);
        scrimPaint.setColor(0x4D000000);
        scrimPaint.setStyle(Paint.Style.FILL);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(3f));
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setColor(brass);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(10f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(withAlpha(brass, 0x33));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float frameW = Math.min(w * 0.78f, dp(300));
        float frameH = frameW * 0.62f;
        float left = (w - frameW) / 2f;
        float top = (h - frameH) / 2f - dp(14);
        frame.set(left, top, left + frameW, top + frameH);

        int brass = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_SECONDARY);
        lineGradient = new LinearGradient(frame.left + dp(18), 0,
                frame.right - dp(18), 0,
                new int[]{0x00FFFFFF, brass, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        linePaint.setShader(lineGradient);

        outside.reset();
        outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(0, 0, w, h, Path.Direction.CW);
        outside.addRoundRect(frame, dp(14), dp(14), Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.isEmpty()) {
            return;
        }
        canvas.drawPath(outside, scrimPaint);
        drawCorners(canvas);

        if (lineGradient != null) {
            float y = frame.top + dp(16) + (frame.height() - dp(32)) * progress;
            canvas.drawLine(frame.left + dp(18), y, frame.right - dp(18), y, glowPaint);
            canvas.drawLine(frame.left + dp(18), y, frame.right - dp(18), y, linePaint);
        }
    }

    private void drawCorners(Canvas canvas) {
        float len = dp(22);
        float inset = dp(2);
        float l = frame.left + inset;
        float t = frame.top + inset;
        float r = frame.right - inset;
        float b = frame.bottom - inset;

        // 左上
        canvas.drawLine(l, t, l, t + len, cornerPaint);
        canvas.drawLine(l, t, l + len, t, cornerPaint);
        // 右上
        canvas.drawLine(r, t, r, t + len, cornerPaint);
        canvas.drawLine(r, t, r - len, t, cornerPaint);
        // 左下
        canvas.drawLine(l, b - len, l, b, cornerPaint);
        canvas.drawLine(l, b, l + len, b, cornerPaint);
        // 右下
        canvas.drawLine(r, b - len, r, b, cornerPaint);
        canvas.drawLine(r, b, r - len, b, cornerPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scanAnimator == null) {
            scanAnimator = ValueAnimator.ofFloat(0f, 1f);
            scanAnimator.setDuration(2200);
            scanAnimator.setRepeatMode(ValueAnimator.REVERSE);
            scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
            scanAnimator.setInterpolator(new LinearInterpolator());
            scanAnimator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                postInvalidateOnAnimation();
            });
        }
        scanAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
