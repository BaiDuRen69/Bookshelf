package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.CheckInLegend;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

/**
 * 把 {@link CheckInLegend} 的数据排成一行小图例（月视图与年视图共用一套语言）。
 *
 * <p>符号直接用几何字符画，颜色取现有语义色，不新增 drawable、也不自绘 View。
 * 「今天」例外：日历里已经是实心圆盘，图例改画同样的小实心圆（含 1dp 高光内环）。
 */
public final class CheckInLegendView {

    private CheckInLegendView() {
    }

    /** 项间 space_lg（16dp）；整行给读屏一句「图例：今天、已打卡…」 */
    public static void fill(LinearLayout container, CheckInLegend.Item[] items) {
        Context ctx = container.getContext();
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int selected = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke);
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        int today = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
        int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
        container.removeAllViews();
        StringBuilder reading = new StringBuilder("图例：");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                reading.append("、");
            }
            reading.append(items[i].label);
            container.addView(cell(ctx, items[i], primary, selected, variant, today, onToday),
                    itemLp(ctx, i));
        }
        container.setContentDescription(reading.toString());
    }

    private static LinearLayout cell(Context ctx, CheckInLegend.Item item, int primary,
                                     int selected, int variant, int today, int onToday) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        if (item.tone == CheckInLegend.TONE_TODAY) {
            int size = px(ctx, R.dimen.checkin_legend_dot);
            cell.addView(new TodayDot(ctx, today, onToday),
                    new LinearLayout.LayoutParams(size, size));
        } else {
            int glyphColor;
            if (item.tone == CheckInLegend.TONE_OUTLINE) {
                glyphColor = selected;
            } else {
                glyphColor = primary;
            }
            cell.addView(text(ctx, item.glyph, glyphColor), wrap(ctx, 0));
        }
        cell.addView(text(ctx, item.label, variant),
                wrap(ctx, px(ctx, R.dimen.space_xs)));
        return cell;
    }

    private static TextView text(Context ctx, String value, int color) {
        TextView tv = new TextView(ctx);
        Typo.text(tv, R.dimen.text_caption);
        tv.setText(value);
        tv.setTextColor(color);
        return tv;
    }

    private static LinearLayout.LayoutParams itemLp(Context ctx, int index) {
        return wrap(ctx, index > 0 ? px(ctx, R.dimen.space_lg) : 0);
    }

    private static LinearLayout.LayoutParams wrap(Context ctx, int marginStart) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (marginStart > 0) {
            lp.setMarginStart(marginStart);
        }
        return lp;
    }

    private static int px(Context ctx, int dimenRes) {
        return ctx.getResources().getDimensionPixelSize(dimenRes);
    }

    /** 图例里的「今天」：与日历同款实心圆，内圈 1dp onPrimary 18% 高光 */
    private static final class TodayDot extends View {

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float ringInset;
        private final float ringWidth;

        TodayDot(Context ctx, int fillColor, int ringColor) {
            super(ctx);
            ringInset = ctx.getResources().getDisplayMetrics().density * 2f;
            ringWidth = px(ctx, R.dimen.checkin_selected_stroke_w);
            fillPaint.setColor(fillColor);
            ringPaint.setColor(ringColor);
            ringPaint.setAlpha(Math.round(255 * 0.18f));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(ringWidth);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy);
            canvas.drawCircle(cx, cy, radius, fillPaint);
            // 半径 -2dp，和日历里今天圆盘的高光内环一致
            float inner = radius - ringInset - ringWidth / 2f;
            if (inner > 0) {
                canvas.drawCircle(cx, cy, inner, ringPaint);
            }
        }
    }
}
