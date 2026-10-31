package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.util.Typo;
import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 统计页的购买排行：日/月排行条目的构建与「名次 + 名称 + 权重条 + 数量」渲染。
 * 没有数据时整块隐藏（标题 + 列表都不显示），符合「没有就不入排行」的要求。
 */
public class StatsRankView {

    /** 排行条目 */
    public static final class Entry {
        public final String label;
        public final int count;

        public Entry(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    /** 月排行：把有购买的日子按数量降序排列（没有购买的天不入榜） */
    public static List<Entry> dayRank(int month0, int[] daily) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < daily.length; i++) {
            if (daily[i] > 0) {
                list.add(new Entry(String.format(Locale.getDefault(),
                        "%d月%d日", month0 + 1, i + 1), daily[i]));
            }
        }
        list.sort((a, b) -> b.count - a.count);
        return list;
    }

    /** 年排行：把有购买的月份按数量降序排列（没有购买的月份不入榜） */
    public static List<Entry> monthRank(int[] monthly) {
        List<Entry> list = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            if (monthly[m] > 0) {
                list.add(new Entry((m + 1) + "月", monthly[m]));
            }
        }
        list.sort((a, b) -> b.count - a.count);
        return list;
    }

    private final Activity activity;

    public StatsRankView(Activity activity) {
        this.activity = activity;
    }

    public void render(LinearLayout container, TextView title, List<Entry> entries) {
        container.removeAllViews();
        if (entries.isEmpty()) {
            title.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        title.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);
        int max = entries.get(0).count;
        int primary = ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY);
        int rank = 1;
        for (Entry e : entries) {
            container.addView(buildRow(rank++, e, max, primary));
        }
    }

    /** 排行行：名次 + 名称 + 权重条 + 数量 */
    private View buildRow(int rank, Entry e, int max, int primary) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(R.drawable.bg_chip);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));

        TextView tvRank = new TextView(activity);
        tvRank.setText(String.valueOf(rank));
        Typo.text(tvRank, R.dimen.text_caption_large);
        tvRank.setGravity(Gravity.CENTER);
        tvRank.setTextColor(primary);
        row.addView(tvRank, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvName = new TextView(activity);
        tvName.setText(e.label);
        Typo.text(tvName, R.dimen.text_body_small);
        tvName.setSingleLine(true);
        row.addView(tvName, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 权重条
        LinearLayout barArea = new LinearLayout(activity);
        barArea.setOrientation(LinearLayout.HORIZONTAL);
        barArea.setWeightSum(max);
        View bar = new View(activity);
        bar.setBackgroundResource(R.drawable.bg_stat_bar);
        bar.getBackground().setTint(primary);
        barArea.addView(bar, new LinearLayout.LayoutParams(0, dp(14), e.count));
        if (max > e.count) {
            barArea.addView(new View(activity),
                    new LinearLayout.LayoutParams(0, dp(14), max - e.count));
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        barLp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(barArea, barLp);

        TextView tvCount = new TextView(activity);
        tvCount.setText(e.count + " 种");
        Typo.text(tvCount, R.dimen.text_body_small);
        tvCount.setTypeface(Typeface.DEFAULT_BOLD);
        tvCount.setGravity(Gravity.END);
        row.addView(tvCount, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
