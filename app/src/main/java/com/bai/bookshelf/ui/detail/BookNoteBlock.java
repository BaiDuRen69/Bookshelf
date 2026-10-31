package com.bai.bookshelf.ui.detail;

import android.text.TextUtils;
import android.view.View;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;

/**
 * 详情页书籍备注块（v5.5，从 BookDetailActivity 抽出）：
 * 最多 3 行，点击展开/收起。
 */
public final class BookNoteBlock {

    private static final int MAX_LINES = 3;
    private boolean expanded;

    public void bind(ActivityBookDetailBinding b, Book book) {
        b.tvNote.setOnClickListener(v -> {
            expanded = !expanded;
            render(b, book);
        });
        render(b, book);
    }

    private void render(ActivityBookDetailBinding b, Book book) {
        String note = book.note == null ? "" : book.note.trim();
        if (note.isEmpty()) {
            b.tvNote.setVisibility(View.GONE);
            return;
        }
        b.tvNote.setVisibility(View.VISIBLE);
        b.tvNote.setText("备注：" + note);
        b.tvNote.setMaxLines(expanded ? Integer.MAX_VALUE : MAX_LINES);
        b.tvNote.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
    }
}
