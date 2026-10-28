package com.bai.bookshelf.ui.series;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivitySeriesListBinding;
import com.bai.bookshelf.databinding.ItemSeriesGroupBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookSeries;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全部系列（v4.53）：按藏书量列出所有系列，点开进入系列详情。
 */
public class SeriesListActivity extends BaseActivity {

    private ActivitySeriesListBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySeriesListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsHelper.applyBottomInset(b.llSeriesList);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> all = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            final Map<String, List<Book>> groups = BookSeries.group(all);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                render(groups);
            });
        });
    }

    private void render(Map<String, List<Book>> groups) {
        b.llSeriesList.removeAllViews();
        b.llSeriesListEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, List<Book>> entry : groups.entrySet()) {
            b.llSeriesList.addView(buildGroupCard(entry.getKey(), entry.getValue()));
        }
    }

    private View buildGroupCard(String name, List<Book> books) {
        ItemSeriesGroupBinding card = ItemSeriesGroupBinding.inflate(getLayoutInflater(),
                b.llSeriesList, false);
        card.tvGroupName.setText(name);
        card.tvGroupCount.setText(books.size() + " 本");

        int readDone = 0;
        for (Book book : books) {
            if (book.readStatus == Book.READ_DONE) {
                readDone++;
            }
        }
        StringBuilder meta = new StringBuilder("已读 ").append(readDone)
                .append(" / ").append(books.size());
        List<String> missing = BookSeries.missingVolumes(books);
        if (!missing.isEmpty()) {
            meta.append(" · 缺第 ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    meta.append("、");
                }
                meta.append(missing.get(i));
            }
            meta.append(" 卷");
        }
        card.tvGroupMeta.setText(meta);

        int preview = Math.min(3, books.size());
        for (int i = 0; i < preview; i++) {
            card.llGroupCovers.addView(buildMiniCover(books.get(i)));
        }
        if (books.size() > preview) {
            TextView more = new TextView(this);
            more.setText("+" + (books.size() - preview));
            more.setTextSize(11);
            more.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
            more.setBackgroundResource(R.drawable.bg_filter_pill);
            more.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            card.llGroupCovers.addView(more, lp);
        }

        card.getRoot().setOnClickListener(v -> {
            Intent intent = new Intent(this, SeriesActivity.class);
            intent.putExtra(SeriesActivity.EXTRA_SERIES_NAME, name);
            startActivity(intent);
        });
        return card.getRoot();
    }

    private View buildMiniCover(Book book) {
        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
        card.setRadius(dp(4));
        card.setCardElevation(0);
        card.setCardBackgroundColor(ThemeManager.resolveColor(this, R.attr.surface_dim));
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(cover, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        CoverLoader.load(cover, book);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(48));
        lp.setMarginEnd(dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
