package com.bai.bookshelf.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import com.bai.bookshelf.util.ThemeManager;

/**
 * 日历里的单个日期格（v4.40）：自绘「连续区间色带 + 正圆端点」。
 *
 * <p>为什么不用 TextView + 背景 drawable：格子比高度宽（约 51×38dp），
 * {@code oval} 会被拉成椭圆，圆角块之间还留着 1dp 的缝——区间看起来是
 * 一颗颗断开的方块。自绘可以让色带整行连续、只在两端收圆角，
 * 端点则是正圆，视觉上更接近系统日历。
 *
 * <p>打卡页一期：新增 {@link #bindCheckIn} 独立绘制模式，原来的 {@link #bind}
 * 一行未改，CalendarPicker 的调用点与观感完全不变；打卡页跨包复用本类，
 * 因此可见性从包内提升为 public（不改变任何现有行为）。
 */
public final class DayCellView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;
    private final float textSize;

    private int day;
    private int primary;
    private int onPrimary;
    private int onSurface;
    private boolean start;
    private boolean end;
    private boolean middle;
    private boolean inBand;
    private boolean today;

    // ===== 打卡页专用状态（原 bind 不碰这些字段）=====
    private boolean checkInMode;
    private boolean selected;
    private boolean adjacent;
    private boolean dot;
    private int primaryContainer;
    private int onSurfaceVariant;

    public DayCellView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15,
                ctx.getResources().getDisplayMetrics());
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);
        bandPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.2f));
        setClickable(true);
    }

    void bind(int day, int primary, int onPrimary, int onSurface,
              boolean start, boolean end, boolean middle, boolean inBand, boolean today) {
        this.day = day;
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.onSurface = onSurface;
        this.start = start;
        this.end = end;
        this.middle = middle;
        this.inBand = inBand;
        this.today = today;
        this.checkInMode = false;
        bandPaint.setColor(ThemeManager.primaryAlpha(getContext(), 0x24));
        ringPaint.setColor(primary);
        invalidate();
    }

    /**
     * 打卡月视图的状态绑定（新增重载，不改原 bind 签名）。
     * 选中 = primary_container 胶囊底 + 主色文字；今天 = 主色描边；
     * 邻月 = on_surface_variant 弱化；有打卡 = 底部 4dp 实心点（1 天多本也只 1 个点）。
     */
    public void bindCheckIn(int day, int primary, int primaryContainer,
                            int onSurface, int onSurfaceVariant,
                            boolean selected, boolean today, boolean adjacent, boolean dot) {
        this.day = day;
        this.primary = primary;
        this.primaryContainer = primaryContainer;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.selected = selected;
        this.today = today;
        this.adjacent = adjacent;
        this.dot = dot;
        this.checkInMode = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (checkInMode) {
            drawCheckIn(canvas);
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float radius = Math.min(w, h) / 2f - dp(1.5f);
        float cx = w / 2f;
        float cy = h / 2f;

        // 区间色带：单日区间只画圆点；跨天时整行连成一条带，只在首尾收圆角
        if (inBand && !(start && end)) {
            float inset = h * 0.1f;
            rect.set(0, inset, w, h - inset);
            float corner = dp(9);
            float tl = start ? corner : 0;
            float bl = start ? corner : 0;
            float tr = end ? corner : 0;
            float br = end ? corner : 0;
            path.reset();
            path.addRoundRect(rect, new float[]{tl, tl, tr, tr, br, br, bl, bl},
                    Path.Direction.CW);
            canvas.drawPath(path, bandPaint);
        }
        if (start || end) {
            int bandColor = bandPaint.getColor();
            bandPaint.setColor(primary);
            canvas.drawCircle(cx, cy, radius, bandPaint);
            bandPaint.setColor(bandColor);
        } else if (today) {
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }

        boolean solid = start || end;
        textPaint.setColor(solid ? onPrimary : (middle || today ? primary : onSurface));
        textPaint.setFakeBoldText(solid);
        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(day), cx, baseline, textPaint);
    }

    private void drawCheckIn(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float radius = Math.min(w, h) / 2f - dp(1.5f);
        float cx = w / 2f;
        float cy = h / 2f;

        if (selected) {
            bandPaint.setColor(primaryContainer);
            rect.set(dp(2), cy - radius, w - dp(2), cy + radius);
            canvas.drawRoundRect(rect, radius, radius, bandPaint);
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(true);
        } else if (today) {
            ringPaint.setColor(primary);
            canvas.drawCircle(cx, cy, radius, ringPaint);
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(false);
        } else {
            textPaint.setColor(adjacent ? onSurfaceVariant : onSurface);
            textPaint.setFakeBoldText(false);
        }
        textPaint.setAlpha(adjacent && !selected ? 160 : 255);

        // 有打卡：底部一个 4dp 实心点，颜色跟主色；一天多本也只画一点
        if (dot) {
            bandPaint.setColor(primary);
            canvas.drawCircle(cx, h - dp(5), dp(2), bandPaint);
        }

        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(day), cx, baseline, textPaint);
        textPaint.setAlpha(255);
    }

    private float dp(float value) {
        return value * density;
    }
}
