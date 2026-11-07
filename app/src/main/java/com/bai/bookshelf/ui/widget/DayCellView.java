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
    /** 选择器模式的框选锚点（打卡页恒为 false） */
    private boolean anchor;
    // ===== 精确日期选择器（v5.9.1 修订第三步）：复用 drawCheckIn 的分支，不另起绘制函数 =====
    private boolean pickerMode;
    private boolean picked;
    private boolean reference;
    private boolean preview;
    private boolean blocked;
    private int pickedBg;
    private int referenceBg;
    private int previewBg;
    private int dotTotal;
    private int dotBackfill;
    private int selectedBg;
    private int selectedStroke;
    private int onSurfaceVariant;
    private int adjacentText;
    // ===== 打卡日历的新视觉：今日实心圆、非今日选中圆底、农历小字 =====
    private String lunarText = "";
    private int ringColor;
    private int lunarColor;
    private int todayBg;
    private int onToday;
    private int todayRing;
    private float lunarTextSize;
    /** 视图切换动画期间暂停打卡点入场，避免两个属性动画抢同一帧 */
    private boolean dotSuspended;
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
     * <p>今天 = {@code checkin_today_bg} 实心圆 + {@code checkin_on_today} 字
     * （常驻高亮，选中时外圈改 {@code checkin_today_ring}）；
     * 非今天选中 = {@code checkin_selected_bg} 实心圆 + 正圆描边（夜读才可见）；
     * 未来日 = 文字压到 120 alpha 且不画点；
     * 邻月 = 只用 {@code checkin_adjacent_text} 一档灰（不叠 alpha）、不画农历与打卡点；
     * 打卡点按本数排，补卡的那几本画空心点、实心在左。
     *
     * <p>{@code anchor} 是选择器模式（v5.9.1 第三步）才用的框选锚点：只描边不填底，
     * 和「已选入」的实心胶囊一眼分得开。打卡页永远传 false，观感与之前逐像素一致。
     */
    public void bindCheckIn(int day, int primary, int selectedBg, int selectedStroke,
                            int onSurface, int onSurfaceVariant,
                            boolean selected, boolean today, boolean adjacent, boolean future,
                            int dotTotal, int dotBackfill, boolean anchor) {
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
        this.anchor = anchor;
        this.dotTotal = dotTotal;
        this.dotBackfill = dotBackfill;
        this.checkInMode = true;
        this.pickerMode = false;
        // 只有「从无到有」才弹一下：撤销再打、切月重建都会重新走这里
        if (dotTotal > 0 && before == 0) {
            startDotEntrance();
        }
        invalidate();
    }

    /**
     * 精确日期选择器的格子绑定（v5.9.1 修订第三步）：复用 {@link #drawCheckIn} 的绘制分支。
     *
     * <p>已选 = primary_container 胶囊 + 主色字 + 右下 4dp 实心点；参考底纹 = surface_dim + 次级字；
     * 框选起点 = 胶囊底 + 1.5dp 主色描边；区间预览 = outline 底；越界日 alpha 110 且不响应点选。
     * 打卡页不走这里，观感不变。
     */
    public void bindPicker(int day, int primary, int pickedBg, int referenceBg, int previewBg,
                           int onSurface, int onSurfaceVariant,
                           boolean picked, boolean reference, boolean anchor, boolean preview,
                           boolean today, boolean blocked) {
        this.day = day;
        this.primary = primary;
        this.pickedBg = pickedBg;
        this.referenceBg = referenceBg;
        this.previewBg = previewBg;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.picked = picked;
        this.reference = reference;
        this.anchor = anchor;
        this.preview = preview;
        this.today = today;
        this.blocked = blocked;
        this.selected = false;
        this.adjacent = false;
        this.future = blocked;
        this.dotTotal = 0;
        this.dotBackfill = 0;
        this.pickerMode = true;
        this.checkInMode = true;
        invalidate();
    }

    /**
     * 打卡月视图的状态绑定（新增重载，不改原 13 参签名）：额外带今天圆内的
     * colorOnPrimary 反色字、今天选中外圈、农历小字。
     */
    public void bindCheckIn(int day, int primary, int selectedBg, int selectedStroke,
                            int onSurface, int onSurfaceVariant,
                            boolean selected, boolean today, boolean adjacent, boolean future,
                            int dotTotal, int dotBackfill, boolean anchor,
                            String lunar, int onPrimary, int ringColor,
                            int lunarColor, float lunarTextSize, int adjacentText) {
        bindCheckIn(day, primary, selectedBg, selectedStroke, onSurface, onSurfaceVariant,
                selected, today, adjacent, future, dotTotal, dotBackfill, anchor);
        this.lunarText = lunar == null ? "" : lunar;
        this.onPrimary = onPrimary;
        this.ringColor = ringColor;
        this.lunarColor = lunarColor;
        this.lunarTextSize = lunarTextSize;
        this.adjacentText = adjacentText;
        // 21 参重载会覆盖这三项；旧调用点保持原来的主色观感
        this.todayBg = primary;
        this.onToday = onPrimary;
        this.todayRing = ringColor;
        invalidate();
    }

    /**
     * 今天改用独立语义色（{@code checkin_today_bg / checkin_on_today / checkin_today_ring}）：
     * 今天的圆、字数、打卡点、农历都用 onToday，选中外圈用 todayRing。
     */
    public void bindCheckIn(int day, int primary, int selectedBg, int selectedStroke,
                            int onSurface, int onSurfaceVariant,
                            boolean selected, boolean today, boolean adjacent, boolean future,
                            int dotTotal, int dotBackfill, boolean anchor,
                            String lunar, int onPrimary, int ringColor,
                            int lunarColor, float lunarTextSize, int adjacentText,
                            int todayBg, int onToday, int todayRing) {
        bindCheckIn(day, primary, selectedBg, selectedStroke, onSurface, onSurfaceVariant,
                selected, today, adjacent, future, dotTotal, dotBackfill, anchor,
                lunar, onPrimary, ringColor, lunarColor, lunarTextSize, adjacentText);
        this.todayBg = todayBg;
        this.onToday = onToday;
        this.todayRing = todayRing;
        invalidate();
    }

    /** 视图切换动画期间挂起 120ms 的打卡点入场，动画结束再恢复 */
    public void setDotAnimationSuspended(boolean suspended) {
        dotSuspended = suspended;
        if (dotAnim != null) {
            dotAnim.cancel();
            dotAnim = null;
        }
        dotProgress = 1f;
        invalidate();
    }

    /** 打卡点入场：120ms 内半径 0.6→1、透明度 0→255，只改一个 progress 字段，不新建 View */
    private void startDotEntrance() {
        if (dotSuspended) {
            dotProgress = 1f;
            return;
        }
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

        if (adjacent && !pickerMode) {
            // 邻月优先级最高：只用这一档灰，不叠 alpha，也不参与今天/选中的任何底色
            textPaint.setColor(adjacentText);
            textPaint.setFakeBoldText(false);
        } else if (pickerMode) {
            // 选择器与打卡页共用这一条绘制链，只是底的取法不同，不再另起第三个绘制函数
            rect.set(dp(2), cy - radius, w - dp(2), cy + radius);
            if (picked) {
                bandPaint.setColor(pickedBg);
                canvas.drawRoundRect(rect, radius, radius, bandPaint);
                textPaint.setColor(primary);
                // 右下 4dp 实心点：和打卡页底部居中的点区分开，一眼是「选进了集合」
                bandPaint.setColor(primary);
                canvas.drawCircle(w - dp(6), h - dp(6),
                        px(R.dimen.checkin_dot_size) / 2f, bandPaint);
            } else if (anchor) {
                bandPaint.setColor(pickedBg);
                canvas.drawRoundRect(rect, radius, radius, bandPaint);
                float stroke = px(R.dimen.checkin_ring_stroke); // 1.5dp 主色描边
                ringPaint.setColor(primary);
                ringPaint.setStrokeWidth(stroke);
                rect.inset(stroke / 2f, stroke / 2f);
                canvas.drawRoundRect(rect, radius, radius, ringPaint);
                ringPaint.setStrokeWidth(ringStroke);
                textPaint.setColor(primary);
            } else if (preview) {
                bandPaint.setColor(previewBg);
                canvas.drawRoundRect(rect, radius, radius, bandPaint);
                textPaint.setColor(onSurface);
            } else if (reference) {
                bandPaint.setColor(referenceBg);
                canvas.drawRoundRect(rect, radius, radius, bandPaint);
                textPaint.setColor(onSurfaceVariant);
            } else {
                textPaint.setColor(onSurface);
            }
            if (today) {
                ringPaint.setColor(primary);
                ringPaint.setStrokeWidth(ringStroke);
                canvas.drawCircle(cx, cy, radius, ringPaint);
                if (!picked && !anchor) {
                    textPaint.setColor(primary);
                }
            }
            textPaint.setFakeBoldText(picked);
        } else if (today) {
            // 今天常驻实心圆：直径 36dp，窄格按格宽收；选中时外圈留 2dp 间隙再描一圈
            float todayRadius = Math.min(w - px(R.dimen.checkin_today_margin),
                    px(R.dimen.checkin_today_diameter)) / 2f;
            bandPaint.setColor(todayBg);
            canvas.drawCircle(cx, cy, todayRadius, bandPaint);
            if (selected) {
                float stroke = px(R.dimen.checkin_ring_stroke);
                ringPaint.setColor(todayRing);
                ringPaint.setStrokeWidth(stroke);
                canvas.drawCircle(cx, cy,
                        todayRadius + px(R.dimen.checkin_today_ring_gap) + stroke / 2f, ringPaint);
                ringPaint.setStrokeWidth(ringStroke);
            }
            textPaint.setColor(onToday);
            textPaint.setFakeBoldText(true);
        } else if (selected) {
            // 非今天选中：独立底色的实心圆 + 1dp 描边（浅色主题描边透明）
            bandPaint.setColor(selectedBg);
            canvas.drawCircle(cx, cy, radius, bandPaint);
            ringPaint.setColor(selectedStroke);
            ringPaint.setStrokeWidth(px(R.dimen.checkin_selected_stroke_w));
            canvas.drawCircle(cx, cy, radius, ringPaint);
            ringPaint.setStrokeWidth(ringStroke);
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(true);
        } else if (anchor) {
            // 框选锚点：只描边不填底，和「已选入」的实心胶囊一眼分得开
            float stroke = px(R.dimen.checkin_selected_stroke_w);
            rect.set(dp(2), cy - radius, w - dp(2), cy + radius);
            rect.inset(stroke / 2f, stroke / 2f);
            ringPaint.setColor(primary);
            ringPaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, radius, radius, ringPaint);
            ringPaint.setStrokeWidth(ringStroke);
            textPaint.setColor(primary);
            textPaint.setFakeBoldText(false);
        } else {
            textPaint.setColor(onSurface);
            textPaint.setFakeBoldText(false);
        }
        textPaint.setAlpha(pickerMode ? (blocked ? 110 : 255)
                : (adjacent ? 255 : (future ? 120 : 255)));
        drawCheckInDots(canvas, cx, h);

        // 邻月不画农历，但仍预留农历行：日号基线与本月一致，整行不会错开 5dp
        boolean lunarRow = !pickerMode && !lunarText.isEmpty() && lunarTextSize > 0;
        boolean showLunar = lunarRow && !adjacent;
        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        if (lunarRow) {
            baseline -= dp(5);
        }
        canvas.drawText(String.valueOf(day), cx, baseline, textPaint);
        if (showLunar) {
            float savedSize = textPaint.getTextSize();
            int savedColor = textPaint.getColor();
            int savedAlpha = textPaint.getAlpha();
            boolean savedBold = textPaint.isFakeBoldText();
            textPaint.setTextSize(lunarTextSize);
            // 今天圆内的小字跟着反色，70% alpha；其它日仍是次级色
            textPaint.setColor(today ? onToday : lunarColor);
            textPaint.setFakeBoldText(false);
            textPaint.setAlpha(today ? 179 : 160);
            canvas.drawText(lunarText, cx, cy + lunarTextSize + dp(3), textPaint);
            textPaint.setTextSize(savedSize);
            textPaint.setColor(savedColor);
            textPaint.setAlpha(savedAlpha);
            textPaint.setFakeBoldText(savedBold);
        }
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
        // 今天格内的点用 onToday，和今天圆的底色/反色字同一套
        int dotColor = today ? onToday : primary;
        bandPaint.setColor(dotColor);
        bandPaint.setAlpha(dotAlpha);
        float x = left;
        for (int i = 0; i < fh[0]; i++) {
            canvas.drawCircle(x, bottom, r, bandPaint);
            x += step;
        }
        ringPaint.setColor(dotColor);
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
