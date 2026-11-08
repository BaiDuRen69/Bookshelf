package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.stats.StatsText;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 摘要行单元的搭法：上 Overline 标签、下「数字 + 单位」（StatsText.metric 的 span 方案）。
 * 月视图三单元与年视图两单元共用这一套版式，差别只在传进来的 Unit[]。
 * 单元之间用 24dp 高的 1dp 竖线分隔，单元内左对齐。
 */
final class CheckInSummaryRow {

    private CheckInSummaryRow() {
    }

    /** 单元等分（weight 1），单元间 1dp 竖分隔 */
    static void fill(LinearLayout row, CheckInUiState.Unit[] units) {
        Context ctx = row.getContext();
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        int outline = ThemeManager.resolveColor(ctx, R.attr.outline);
        row.removeAllViews();
        for (int i = 0; i < units.length; i++) {
            if (i > 0) {
                row.addView(divider(ctx, outline), dividerLp(ctx));
            }
            row.addView(unitView(ctx, units[i], variant), unitLp());
        }
    }

    private static View divider(Context ctx, int color) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        return v;
    }

    private static LinearLayout.LayoutParams dividerLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                px(ctx, R.dimen.checkin_summary_divider_w),
                px(ctx, R.dimen.checkin_summary_divider_h));
        lp.gravity = Gravity.CENTER_VERTICAL;
        int gap = px(ctx, R.dimen.space_md);
        lp.setMarginStart(gap);
        lp.setMarginEnd(gap);
        return lp;
    }

    private static View unitView(Context ctx, CheckInUiState.Unit u, int variant) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.addView(overline(ctx, u.label), wrap());
        TextView value = new TextView(ctx);
        value.setTextAppearance(ctx, R.style.Text_Bookshelf_Metric_Small);
        value.setText(StatsText.metric(u.value, u.unit, variant));
        if (!u.accent) {
            // 0 不该用主色喊
            value.setTextColor(variant);
        }
        cell.addView(value, wrap());
        if (u.note != null) {
            LinearLayout.LayoutParams noteLp = wrap();
            noteLp.topMargin = ctx.getResources().getDimensionPixelSize(R.dimen.space_sm);
            cell.addView(overline(ctx, u.note), noteLp);
        }
        return cell;
    }

    private static TextView overline(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setTextAppearance(ctx, R.style.Text_Bookshelf_Overline);
        tv.setText(text);
        return tv;
    }

    private static LinearLayout.LayoutParams unitLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int px(Context ctx, int dimenRes) {
        return ctx.getResources().getDimensionPixelSize(dimenRes);
    }
}
