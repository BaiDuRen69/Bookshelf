package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.List;
import java.util.Map;

/**
 * 「排名 + 名称 + 渐变条 + 数量」分布榜（v3.0 抽出，v4.25 改为横向条形榜）。
 *
 * <p>统计页与作者/出版社/国家三个二级页共用；条形用主色 10% → 主色的渐变，
 * 点击整行跳到书架按名称搜索。
 */
public final class DistRowBuilder {

    private static final int RANK_WIDTH_DP = 20;
    private static final int NAME_WIDTH_DP = 92;
    private static final int COUNT_WIDTH_DP = 38;
    private static final int BAR_HEIGHT_DP = 8;

    public interface OnRowClickListener {
        void onRowClick(String name);
    }

    private final Context ctx;
    private final OnRowClickListener onRowClick;

    public DistRowBuilder(Context ctx, OnRowClickListener onRowClick) {
        this.ctx = ctx;
        this.onRowClick = onRowClick;
    }

    /**
     * 往容器里填充一组分布行（自动按最大值为基准归一化条形长度）。
     */
    public void fill(LinearLayout container, List<Map.Entry<String, Integer>> entries, String emptyText) {
        container.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(emptyText);
            empty.setTextSize(13);
            empty.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            empty.setLineSpacing(dp(2), 1f);
            container.addView(empty);
            return;
        }
        int max = entries.get(0).getValue();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            container.addView(buildRow(e.getKey(), e.getValue(), max, i + 1));
        }
    }

    public static String emptyHint(String field, String extra) {
        String base = "暂无" + field + "数据：给书籍填写「" + field + "」后这里就有内容了";
        return (extra == null || extra.isEmpty()) ? base : base + "\n" + extra;
    }

    /** 单行：排名 + 名称 + 渐变条 + 数量；整行可点 */
    public View buildRow(String name, int count, int max, int rank) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(selectableBackground());
        if (onRowClick != null) {
            final String keyword = name;
            row.setOnClickListener(v -> onRowClick.onRowClick(keyword));
        }

        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);

        TextView tvRank = new TextView(ctx);
        tvRank.setText(String.valueOf(rank));
        tvRank.setTextSize(11);
        tvRank.setGravity(Gravity.CENTER);
        tvRank.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tvRank.setTextColor(rank <= 3 ? primary : variant);
        row.addView(tvRank, new LinearLayout.LayoutParams(dp(RANK_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvName.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface));
        row.addView(tvName, new LinearLayout.LayoutParams(dp(NAME_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout barArea = new LinearLayout(ctx);
        barArea.setOrientation(LinearLayout.HORIZONTAL);
        barArea.setBackgroundResource(R.drawable.bg_bar_track);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), 1f);
        barLp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(barArea, barLp);

        View fill = new View(ctx);
        fill.setBackground(barGradient(primary));
        barArea.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, count));
        // v4.31：条形从 0 生长（500ms），数据刷新时会重播
        fill.setPivotX(0f);
        fill.setScaleX(0f);
        fill.animate().scaleX(1f).setDuration(500)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (max > count) {
            barArea.addView(new View(ctx),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, max - count));
        }

        TextView tvCount = new TextView(ctx);
        tvCount.setText(String.valueOf(count));
        tvCount.setTextSize(13);
        tvCount.setGravity(Gravity.END);
        tvCount.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tvCount.setTextColor(primary);
        row.addView(tvCount, new LinearLayout.LayoutParams(dp(COUNT_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    /** 主色 10% → 主色的横向渐变条 */
    private GradientDrawable barGradient(int primary) {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ThemeManager.primaryAlpha(ctx, 0x1A), primary});
        gradient.setCornerRadius(dp(4));
        return gradient;
    }

    private Drawable selectableBackground() {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return ContextCompat.getDrawable(ctx, out.resourceId);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
