package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Typeface;
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
 * 「名称 + 权重条形 + 数量」分布行渲染器（v3.0 抽出）。
 *
 * <p>此前统计页 Top8 与国家/出版社/作者三个二级页各有一份几乎完全相同的 buildBarRow（4 × 约 40 行），
 * 样式与点击行为容易走偏；现统一收敛到这里，四个页面共用同一套渲染逻辑与可点击行为。
 */
public final class DistRowBuilder {

    /** 名称列固定宽度（dp） */
    private static final int NAME_WIDTH_DP = 96;
    /** 数量列固定宽度（dp） */
    private static final int COUNT_WIDTH_DP = 36;
    /** 条形高度（dp） */
    private static final int BAR_HEIGHT_DP = 14;

    /** 行点击回调（v3.0：统计与二级页点击跳书架搜索） */
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
     *
     * @param container 目标容器（会先清空）
     * @param entries   「名称 → 数量」列表（调用方已按数量倒序）
     * @param emptyText 数据为空时的提示文案
     */
    public void fill(LinearLayout container, List<Map.Entry<String, Integer>> entries, String emptyText) {
        container.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(emptyText);
            empty.setTextSize(14);
            empty.setTextColor(ContextCompat.getColor(ctx, R.color.text_sub));
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            container.addView(empty);
            return;
        }
        int max = entries.get(0).getValue();
        for (Map.Entry<String, Integer> e : entries) {
            container.addView(buildRow(e.getKey(), e.getValue(), max));
        }
    }

    /**
     * 统一空状态文案（v3.0：统计页与三个二级页共用同一句式，避免各写各的）。
     *
     * @param field 字段名，如「作者」「出版社」「国家/地区」
     * @param extra 补充说明，可为 null
     */
    public static String emptyHint(String field, String extra) {
        String base = "暂无" + field + "数据：给书籍填写「" + field + "」后这里就有内容了";
        return (extra == null || extra.isEmpty()) ? base : base + "\n" + extra;
    }

    /** 单行：名称（定宽）+ 权重条形 + 数量；整行可点击（卡片化提示可点） */
    public View buildRow(String name, int count, int max) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_chip);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (onRowClick != null) {
            final String keyword = name;
            row.setOnClickListener(v -> onRowClick.onRowClick(keyword));
        }

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tvName, new LinearLayout.LayoutParams(dp(NAME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 条形区：bar 与 spacer 按 count : (max-count) 分配宽度
        LinearLayout barArea = new LinearLayout(ctx);
        barArea.setOrientation(LinearLayout.HORIZONTAL);
        barArea.setWeightSum(max);
        View bar = new View(ctx);
        bar.setBackgroundResource(R.drawable.bg_stat_bar);
        bar.getBackground().setTint(ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY));
        barArea.addView(bar, new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), count));
        if (max > count) {
            barArea.addView(new View(ctx),
                    new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), max - count));
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        barLp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(barArea, barLp);

        TextView tvCount = new TextView(ctx);
        tvCount.setText(String.valueOf(count));
        tvCount.setTextSize(13);
        tvCount.setTypeface(Typeface.DEFAULT_BOLD);
        tvCount.setGravity(Gravity.END);
        row.addView(tvCount, new LinearLayout.LayoutParams(dp(COUNT_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}