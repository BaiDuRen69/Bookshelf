package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.google.android.material.chip.Chip;

import java.util.function.Consumer;

/**
 * 详情页标签区（v5.6，从 BookDetailActivity 抽出）：
 * 标签未变不重建 Chip；超长标签截断，点击查看全文。
 */
public final class DetailTags {

    private String loadedTags = "";

    public void bind(Activity activity, ActivityBookDetailBinding b,
                     Book book, Consumer<String> showFullTag) {
        String tags = book.tags == null ? "" : book.tags.trim();
        if (tags.equals(loadedTags)) {
            return;
        }
        loadedTags = tags;
        b.chipGroupTags.removeAllViews();
        if (tags.isEmpty()) {
            b.cardTags.setVisibility(View.GONE);
            b.tvTagsLabel.setVisibility(View.GONE);
            b.chipGroupTags.setVisibility(View.GONE);
            return;
        }
        b.cardTags.setVisibility(View.VISIBLE);
        b.tvTagsLabel.setVisibility(View.VISIBLE);
        b.chipGroupTags.setVisibility(View.VISIBLE);
        for (String raw : tags.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            Chip chip = new Chip(activity);
            if (name.length() > 20) {
                chip.setText("# " + name.substring(0, 20) + "…");
                chip.setOnClickListener(v -> showFullTag.accept(name));
            } else {
                chip.setText("# " + name);
                chip.setClickable(false);
            }
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setCheckable(false);
            b.chipGroupTags.addView(chip);
        }
    }
}
