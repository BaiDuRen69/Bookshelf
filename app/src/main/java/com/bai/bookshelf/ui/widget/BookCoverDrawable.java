package com.bai.bookshelf.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bai.bookshelf.R;

/**
 * 无封面时的布面书封：六种布色 + 居中书名首字（衬线白字）+ 左侧书脊。
 * 直接绘制，不占 Bitmap 内存；尺寸比例与真实封面一致（3:4）。
 */
public class BookCoverDrawable extends Drawable {

    private final int clothColor;
    private final String initial;
    private final Paint clothPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint initialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient spineGradient;

    public BookCoverDrawable(@NonNull android.content.Context context, @Nullable String title) {
        int[] palette = {
                context.getColor(R.color.book_cloth_green),
                context.getColor(R.color.book_cloth_indigo),
                context.getColor(R.color.book_cloth_rust),
                context.getColor(R.color.book_cloth_ochre),
                context.getColor(R.color.book_cloth_plum),
                context.getColor(R.color.book_cloth_charcoal)
        };
        String trimmed = visibleText(title);
        int hash = trimmed.isEmpty() ? 0 : Math.abs(trimmed.hashCode());
        clothColor = palette[hash % palette.length];
        initial = firstCodePoint(trimmed.isEmpty() ? "书" : trimmed);

        clothPaint.setColor(clothColor);

        initialPaint.setColor(Color.WHITE);
        initialPaint.setAlpha(236);
        initialPaint.setTextAlign(Paint.Align.CENTER);
        initialPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));

        spinePaint.setColor(0x33000000);
        highlightPaint.setColor(0x40FFFFFF);
        rulePaint.setColor(0x55FFFFFF);
        rulePaint.setStrokeWidth(1f);
    }

    private static String firstCodePoint(String text) {
        // 跳过《》、【】、空白等前导标点/括号，避免把符号放大成封面字
        int i = 0;
        while (i < text.length()) {
            int cp = Character.codePointAt(text, i);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_SYMBOL) {
                return new String(Character.toChars(cp));
            }
            i += Character.charCount(cp);
        }
        return "书";
    }

    /** 去掉控制符/零宽字符/空白后的可见文本（用于选布色与封面首字） */
    private static String visibleText(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.CONTROL && type != Character.FORMAT
                    && type != Character.UNASSIGNED && !Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        int spineWidth = Math.max(2, Math.round(bounds.width() * 0.075f));
        spineGradient = new LinearGradient(bounds.left, 0, bounds.left + spineWidth, 0,
                new int[]{0x40000000, 0x1A000000, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        initialPaint.setTextSize(bounds.height() * 0.32f);
        rulePaint.setStrokeWidth(Math.max(1f, bounds.height() * 0.004f));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        canvas.drawRect(b, clothPaint);

        int spineWidth = Math.max(2, Math.round(b.width() * 0.075f));
        if (spineGradient != null) {
            spinePaint.setShader(spineGradient);
            canvas.drawRect(b.left, b.top, b.left + spineWidth, b.bottom, spinePaint);
            spinePaint.setShader(null);
        }
        canvas.drawRect(b.left + spineWidth, b.top, b.left + spineWidth + 1, b.bottom, highlightPaint);

        Paint.FontMetrics fm = initialPaint.getFontMetrics();
        float baseline = b.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(initial, b.centerX(), baseline, initialPaint);

        float ruleHalf = b.width() * 0.14f;
        float ruleY = baseline + b.height() * 0.075f;
        canvas.drawLine(b.centerX() - ruleHalf, ruleY, b.centerX() + ruleHalf, ruleY, rulePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        clothPaint.setAlpha(alpha);
        initialPaint.setAlpha(Math.round(236 * alpha / 255f));
        spinePaint.setAlpha(alpha);
        highlightPaint.setAlpha(alpha);
        rulePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        clothPaint.setColorFilter(colorFilter);
        initialPaint.setColorFilter(colorFilter);
        spinePaint.setColorFilter(colorFilter);
        highlightPaint.setColorFilter(colorFilter);
        rulePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    public int getIntrinsicWidth() {
        return 240;
    }

    @Override
    public int getIntrinsicHeight() {
        return 320;
    }
}
