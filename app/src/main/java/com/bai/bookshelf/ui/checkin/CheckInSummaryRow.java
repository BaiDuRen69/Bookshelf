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
 */
final class CheckInSummaryRow {

    private CheckInSummaryRow() {
    }

    /** 单元等分（weight 1），单元间 space_md */
    static void fill(LinearLayout row, CheckInUiState.Unit[] units) {
        Context ctx = row.getContext();
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        int gap = ctx.getResources().getDimensionPixelSize(R.dimen.space_md);
        row.removeAllViews();
        for (int i = 0; i < units.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(gap);
            }
            row.addView(unitView(ctx, units[i], variant), lp);
        }
    }

    private static View unitView(Context ctx, CheckInUiState.Unit u, int variant) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(overline(ctx, u.label), wrapCenter());
        TextView value = new TextView(ctx);
        value.setTextAppearance(ctx, R.style.Text_Bookshelf_Metric_Small);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setText(StatsText.metric(u.value, u.unit, variant));
        if (!u.accent) {
            // 0 不该用主色喊
            value.setTextColor(variant);
        }
        cell.addView(value, wrapCenter());
        if (u.note != null) {
            LinearLayout.LayoutParams noteLp = wrapCenter();
            noteLp.topMargin = ctx.getResources().getDimensionPixelSize(R.dimen.space_sm);
            cell.addView(overline(ctx, u.note), noteLp);
        }
        return cell;
    }

    private static TextView overline(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setTextAppearance(ctx, R.style.Text_Bookshelf_Overline);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setText(text);
        return tv;
    }

    private static LinearLayout.LayoutParams wrapCenter() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
