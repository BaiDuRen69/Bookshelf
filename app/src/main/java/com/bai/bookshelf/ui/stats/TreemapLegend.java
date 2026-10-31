package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.List;

/**
 * 树状图图例（v5.8.15）：色块小于标签尺寸时读不出「这块是哪类」，
 * 用两列一行的色点图例兜住；配色与块内同源，避免图例和图对不上。
 */
final class TreemapLegend {

    /** 一次最多列几类，剩下的用一行「另有 N 类」收口 */
    private static final int MAX_ITEMS = 8;

    interface ColorSource {
        int colorAt(int index);
    }

    private TreemapLegend() {
    }

    static void fill(LinearLayout container, List<StatsHoldings.Group> groups, ColorSource colors) {
        container.removeAllViews();
        Context ctx = container.getContext();
        if (groups == null || groups.size() < 2) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        float density = ctx.getResources().getDisplayMetrics().density;
        int dot = Math.round(10 * density);
        int shown = Math.min(groups.size(), MAX_ITEMS);
        LinearLayout row = null;
        for (int i = 0; i < shown; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                container.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            StatsHoldings.Group g = groups.get(i);
            View swatch = new View(ctx);
            swatch.setBackgroundResource(R.drawable.bg_legend_dot);
            swatch.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(colors.colorAt(i)));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dot, dot);
            dotLp.setMarginEnd(Math.round(5 * density));
            row.addView(swatch, dotLp);

            TextView label = new TextView(ctx);
            label.setText(g.name + " · " + g.titles + " 种");
            Typo.text(label, R.dimen.text_caption);
            label.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMarginEnd(Math.round(10 * density));
            row.addView(label, labelLp);
        }
        if (groups.size() > shown) {
            TextView more = new TextView(ctx);
            more.setText("另有 " + (groups.size() - shown) + " 类未在图例列出");
            Typo.text(more, R.dimen.text_caption);
            more.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            moreLp.topMargin = Math.round(4 * density);
            container.addView(more, moreLp);
        }
    }
}
