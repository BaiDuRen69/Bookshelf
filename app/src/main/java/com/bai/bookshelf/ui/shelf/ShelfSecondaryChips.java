package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.util.ThemeManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 书架的二级筛选 chips（v5.6 从 ShelfFragment 抽出）：
 * 选来源大类后展开该组渠道，选分类一级后展开二级栏目；
 * 子筛选值（channelSub / categorySub）由本类持有，筛选条件从这里读取。
 */
public class ShelfSecondaryChips {

    public interface Host {
        List<SourceChannel> channelRows();

        List<Book> allBooks();

        List<Category> categories();

        void applyFilters();
    }

    private final FragmentShelfBinding b;
    private final Host host;

    private String categorySub = "";
    private String channelSub = "";

    public ShelfSecondaryChips(FragmentShelfBinding b, Host host) {
        this.b = b;
        this.host = host;
    }

    public String categorySub() {
        return categorySub;
    }

    public String channelSub() {
        return channelSub;
    }

    public void clearCategorySub() {
        categorySub = "";
    }

    public void clearChannelSub() {
        channelSub = "";
    }

    /** 来源二级渠道 chips 行内展开：type<0 时收起；渠道=渠道库该组 + 藏书里出现过的旧渠道名 */
    public void showChannel(int type) {
        if (type < 0 || b == null) {
            b.scrollChannelChips.setVisibility(View.GONE);
            b.llChannelChips.removeAllViews();
            return;
        }
        String[] typeNames = b.getRoot().getResources().getStringArray(R.array.own_type_entries);
        b.llChannelChips.removeAllViews();
        b.llChannelChips.addView(buildChip("全部「" + typeNames[type] + "」",
                channelSub.isEmpty(), v -> {
                    channelSub = "";
                    showChannel(type);
                    host.applyFilters();
                }));
        // 渠道库该组的渠道 + 藏书里出现过的该大类旧渠道名（老数据可能不在库里）
        Set<String> names = new LinkedHashSet<>();
        for (SourceChannel ch : host.channelRows()) {
            if (ch.ownType == type) {
                names.add(ch.name);
            }
        }
        for (Book bk : host.allBooks()) {
            String cn = bk.sourceChannel == null ? "" : bk.sourceChannel.trim();
            if (bk.ownType == type && !cn.isEmpty()) {
                names.add(cn);
            }
        }
        for (String name : names) {
            b.llChannelChips.addView(buildChip(name, name.equals(channelSub), v -> {
                channelSub = name;
                showChannel(type);
                host.applyFilters();
            }));
        }
        b.scrollChannelChips.setVisibility(View.VISIBLE);
    }

    /** 分类二级 chips 行内展开：top 为 null 时收起 */
    public void showCategory(Category top) {
        if (top == null || b == null) {
            b.scrollCategoryChips.setVisibility(View.GONE);
            b.llCategoryChips.removeAllViews();
            return;
        }
        b.llCategoryChips.removeAllViews();
        b.llCategoryChips.addView(buildChip("全部「" + top.name + "」",
                categorySub.isEmpty(), v -> {
                    categorySub = "";
                    showCategory(top);
                    host.applyFilters();
                }));
        for (Category c : host.categories()) {
            if (c.parentId != null && c.parentId == top.id) {
                b.llCategoryChips.addView(buildChip(c.name, c.name.equals(categorySub), v -> {
                    categorySub = c.name;
                    showCategory(top);
                    host.applyFilters();
                }));
            }
        }
        b.scrollCategoryChips.setVisibility(View.VISIBLE);
    }

    /** 行内筛选 chip（v4.10 小号圆角标签；v4.24 与筛选胶囊统一形状） */
    private TextView buildChip(String text, boolean selected, View.OnClickListener listener) {
        Context ctx = b.getRoot().getContext();
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackgroundResource(selected
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
        // v4.16：选中态文字用当前主题主色（原先写死 R.color.primary，换肤后不跟随）
        tv.setTextColor(selected
                ? ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(ctx, R.attr.on_surface));
        tv.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * b.getRoot().getResources().getDisplayMetrics().density);
    }
}
