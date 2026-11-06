package com.bai.bookshelf.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.CheckInDots;
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

    /** 打卡点入场时长（比切月的 110ms 略长，落点要看得见） */
    private static final long DOT_ENTRANCE_MS = 120;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;
    private final float textSize;
    private final float ringStroke;

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
    private boolean future;
    private int dotTotal;
    private int dotBackfill;
    private int selectedBg;
    private int selectedStroke;
    private int onSurfaceVariant;
    /** 打卡点入场进度：1 = 已落定；<1 时点半径按 0.6→1 放大、透明度 0→255 */
    private float dotProgress = 1f;
    @Nullable
    private ValueAnimator dotAnim;

    public DayCellView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        // 日号走字阶令牌（L3 = 15sp），与布局侧同一档；CalendarPicker 的观感不变
        textSize = ctx.getResources().getDimension(R.dimen.text_body);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);
        bandPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        // 描边统一走这一档：今天的圈、夜读选中的外描边、空心打卡点都靠它
        ringStroke = dp(1.2f);
        ringPaint.setStrokeWidth(ringStroke);
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
     *
     * <p>选中 = {@code checkin_selected_bg} 胶囊（夜读再补一圈描边，淡底本身不够可读）；
     * 今天 = 主色描边圆，今天且选中时两者叠加，不再因为胶囊把「今天」盖掉；
     * 未来日 = 文字压到 120 alpha 且不画点；邻月 = 更弱的 110 alpha 且永不画点；
     * 打卡点按本数排，补卡的那几本画空心点、实心在左。
     */
    public void bindCheckIn(int day, int primary, int selectedBg, int selectedStroke,
                            int onSurface, int onSurfaceVariant,
                            boolean selected, boolean today, boolean adjacent, boolean future,
                            int dotTotal, int dotBackfill) {
        int before = this.dotTotal;
        this.day = day;
        this.primary = primary;
        this.selectedBg = selectedBg;
        this.selectedStroke = selectedStroke;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.selected = selected;
        this.today = today;
        this.adjacent = adjacent;
        this.future = future;
        this.dotTotal = dotTotal;
        this.dotBackfill = dotBackfill;
        this.checkInMode = true;
        // 只有「从无到有」才弹一下：撤销再打、切月重建都会重新走这里
        if (dotTotal > 0 && before == 0) {
            startDotEntrance();
        }
        invalidate();
    }

    /** 打卡点入场：120ms 内半径 0.6→1、透明度 0→255，只改一个 progress 字段，不新建 View */
    private void startDotEntrance() {
        if (dotAnim != null) {
            dotAnim.cancel();
        }
        dotProgress = 0f;
        dotAnim = ValueAnimator.ofFloat(0f, 1f);
        dotAnim.setDuration(DOT_ENTRANCE_MS);
        dotAnim.addUpdateListener(a -> {
            dotProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        dotAnim.start();
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
        float radius = Math.min(w, h) / 2f - px(R.dimen.checkin_ring_stroke);
        float cx = w / 2f;
        float cy = h / 2f;

        if (selected) {
            bandPaint.setColor(selectedBg);
            rect.set(dp(2), cy - radius, w - dp(2), cy + radius);
            canvas.drawRoundRect(rect, radius, radius, bandPaint);
            if (selectedStroke != 0) {
                // 夜读的胶囊底与卡面差值不够，靠这一圈描边把「选中」读出来
                float stroke = px(R.dimen.checkin_selected_stroke_w);
                ringPaint.setColor(selectedStroke);
                ringPaint.setStrokeWidth(stroke);
                rect.inset(stroke / 2f, stroke / 2f);
                canvas.drawRoundRect(rect, radius, radius, ringPaint);
            }
            if (today) {
                // 今天且选中：胶囊之外仍画主色圈，「今天」不再被盖掉
                ringPaint.setColor(primary);
                ringPaint.setStrokeWidth(ringStroke);
                canvas.drawCircle(cx, cy, radius, ringPaint);
            }
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(true);
        } else if (today) {
            ringPaint.setColor(primary);
            ringPaint.setStrokeWidth(ringStroke);
            canvas.drawCircle(cx, cy, radius, ringPaint);
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(false);
        } else {
            textPaint.setColor(adjacent ? onSurfaceVariant : onSurface);
            textPaint.setFakeBoldText(false);
        }
        textPaint.setAlpha(adjacent ? 110 : (future ? 120 : 255));
        drawCheckInDots(canvas, cx, h);

        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(day), cx, baseline, textPaint);
        textPaint.setAlpha(255);
    }

    /**
     * 底部打卡点：点数 = 当天打卡的本数（1 本大点、2 本起小点、≥3 本固定三个），
     * 补卡的那几本画空心、实心排左边。未来日与邻月一律不画——否则会读成「这个月打过」。
     */
    private void drawCheckInDots(Canvas canvas, float cx, float h) {
        int count = CheckInDots.dotCount(dotTotal);
        if (count == 0 || future || adjacent) {
            return;
        }
        float size = px(CheckInDots.useLargeDot(dotTotal)
                ? R.dimen.checkin_dot_size : R.dimen.checkin_dot_size_multi);
        float gap = px(R.dimen.checkin_dot_gap);
        // 入场：半径 0.6→1、alpha 0→255；落定后 scale=1、alpha=255 与原来完全一致
        float scale = dotProgress >= 1f ? 1f : 0.6f + 0.4f * dotProgress;
        int dotAlpha = dotProgress >= 1f ? 255 : Math.round(255 * dotProgress);
        float r = size / 2f * scale;
        float step = size + gap;
        float left = cx - (count * size + (count - 1) * gap) / 2f + r;
        float bottom = h - px(R.dimen.checkin_dot_bottom);
        int[] fh = CheckInDots.filledHollow(dotTotal, dotBackfill);
        bandPaint.setColor(primary);
        bandPaint.setAlpha(dotAlpha);
        float x = left;
        for (int i = 0; i < fh[0]; i++) {
            canvas.drawCircle(x, bottom, r, bandPaint);
            x += step;
        }
        ringPaint.setColor(primary);
        ringPaint.setAlpha(dotAlpha);
        ringPaint.setStrokeWidth(ringStroke);
        for (int i = 0; i < fh[1]; i++) {
            canvas.drawCircle(x, bottom, r, ringPaint);
            x += step;
        }
        bandPaint.setAlpha(255);
        ringPaint.setAlpha(255);
    }

    private float dp(float value) {
        return value * density;
    }

    /** 打卡页尺寸令牌（px）：规格把点、描边这些尺寸收进了 dimens，这里按资源取 */
    private float px(int dimenRes) {
        return getContext().getResources().getDimension(dimenRes);
    }
}
