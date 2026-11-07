package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.view.Gravity;
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
 */
public final class CheckInLegendView {

    private CheckInLegendView() {
    }

    /** 项间 space_md；整行给读屏一句「图例：今天、已打卡…」 */
    public static void fill(LinearLayout container, CheckInLegend.Item[] items) {
        Context ctx = container.getContext();
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int outline = ThemeManager.resolveColor(ctx, R.attr.outline_strong);
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        int today = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
        container.removeAllViews();
        StringBuilder reading = new StringBuilder("图例：");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                reading.append("、");
            }
            reading.append(items[i].label);
            container.addView(cell(ctx, items[i], primary, outline, variant, today),
                    itemLp(ctx, i));
        }
        container.setContentDescription(reading.toString());
    }

    private static LinearLayout cell(Context ctx, CheckInLegend.Item item,
                                     int primary, int outline, int variant, int today) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        int glyphColor;
        if (item.tone == CheckInLegend.TONE_OUTLINE) {
            glyphColor = outline;
        } else if (item.tone == CheckInLegend.TONE_TODAY) {
            glyphColor = today;
        } else {
            glyphColor = primary;
        }
        cell.addView(text(ctx, item.glyph, glyphColor), wrap(ctx, 0));
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
        return wrap(ctx, index > 0 ? px(ctx, R.dimen.space_md) : 0);
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
}
