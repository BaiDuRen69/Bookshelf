package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.ui.search.GlobalSearchActivity;
import com.bai.bookshelf.util.RecentSearches;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;

import java.util.List;

/**
 * 最近搜索（v5.8.1）：搜索框聚焦且为空时浮出一排历史词，点一条直接回填搜索。
 *
 * <p>v5.9.0 从 ShelfFragment 抽出。「全局搜索」那枚胶囊和历史词胶囊长得一样，
 * 以前是两份几乎重复的构造代码，这里合成一个 {@link #makeChip}。
 */
final class ShelfRecentSearch {

    /** 回填与提交都要走书架的筛选，交给宿主做 */
    interface Host {
        Context context();

        FragmentShelfBinding binding();

        ShelfSearchBar searchBar();

        /** 回车或点历史词：立即按新关键词筛一次 */
        void applyQuery();
    }

    private ShelfRecentSearch() {
    }

    static void setup(final Host host) {
        host.binding().etSearch.setOnFocusChangeListener((v, hasFocus) -> refresh(host));
        host.binding().etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh(host);
            }
        });
        host.binding().etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String q = host.searchBar() == null ? "" : host.searchBar().text();
            if (!q.isEmpty()) {
                RecentSearches.add(host.context(), q);
                host.searchBar().cancelPending();
                host.applyQuery();
                refresh(host);
            }
            return false;
        });
    }

    /** 只有「聚焦 + 没输入」时才铺出历史词，其余时间整块收起 */
    static void refresh(Host host) {
        FragmentShelfBinding b = host.binding();
        if (b == null || host.searchBar() == null) {
            return;
        }
        List<String> recent = RecentSearches.list(host.context());
        boolean show = b.etSearch.hasFocus() && host.searchBar().text().isEmpty();
        b.hsvRecent.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        b.llRecentSearches.removeAllViews();
        b.llRecentSearches.addView(makeChip(host.context(), "全局搜索 ›", true,
                v -> openGlobalSearch(host.context(), "")));
        for (String q : recent) {
            b.llRecentSearches.addView(makeChip(host.context(), q, false, v -> {
                host.searchBar().setText(q);
                host.searchBar().cancelPending();
                host.applyQuery();
                b.etSearch.clearFocus();
                refresh(host);
            }));
        }
        if (!recent.isEmpty()) {
            b.llRecentSearches.addView(makeChip(host.context(), "清除", false, v -> {
                RecentSearches.clear(host.context());
                refresh(host);
            }));
        }
    }

    private static TextView makeChip(Context ctx, String label, boolean accent,
                                     View.OnClickListener onClick) {
        TextView chip = new TextView(ctx);
        chip.setText(label);
        if (accent) {
            Typo.text(chip, R.dimen.text_body_small);
            chip.setTextColor(ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY));
        } else {
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimension(R.dimen.text_body_small));
            chip.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
        }
        chip.setBackgroundResource(R.drawable.bg_filter_pill);
        int padH = (int) ctx.getResources().getDimension(R.dimen.space_md);
        int padV = (int) ctx.getResources().getDimension(R.dimen.space_xs);
        chip.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) ctx.getResources().getDimension(R.dimen.space_xs));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private static void openGlobalSearch(Context ctx, String keyword) {
        Intent intent = new Intent(ctx, GlobalSearchActivity.class);
        intent.putExtra(GlobalSearchActivity.EXTRA_QUERY, keyword);
        ctx.startActivity(intent);
    }
}
