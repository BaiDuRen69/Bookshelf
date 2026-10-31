package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.util.Typo;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.BorrowSourceRecords;
import com.bai.bookshelf.util.ReadingRecords;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 详情页的「阅读记录 / 借书记录」列表（v5.7 从 BookDetailActivity 抽出）：
 * 两个卡片结构相同，只差每条记录的文案。
 */
public final class DetailRecordLists {

    private DetailRecordLists() {
    }

    public static void bindReading(Activity activity, ActivityBookDetailBinding b, Book book) {
        java.util.List<ReadingRecords.Record> records = ReadingRecords.load(book);
        b.containerReadingRecordsDetail.removeAllViews();
        b.cardReadingRecords.setVisibility(records.isEmpty()
                ? android.view.View.GONE : android.view.View.VISIBLE);
        for (ReadingRecords.Record r : records) {
            addRow(activity, b.containerReadingRecordsDetail, readingLine(r));
        }
    }

    public static void bindBorrow(Activity activity, ActivityBookDetailBinding b, Book book) {
        java.util.List<BorrowSourceRecords.Record> records = BorrowSourceRecords.load(book);
        boolean show = book.ownType == Book.OWN_BORROW && !records.isEmpty();
        b.cardBorrowRecords.setVisibility(show
                ? android.view.View.VISIBLE : android.view.View.GONE);
        b.containerBorrowRecordsDetail.removeAllViews();
        if (!show) {
            return;
        }
        for (BorrowSourceRecords.Record r : records) {
            addRow(activity, b.containerBorrowRecordsDetail, borrowLine(r));
        }
    }

    private static String readingLine(ReadingRecords.Record r) {
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次 · ")
                .append(StatusText.read(r.status));
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
            if (!r.endDate.isEmpty()) {
                sb.append(" — ").append(r.endDate);
            }
        }
        int days = ReadingRecords.days(r);
        if (days > 0) {
            sb.append(" · ").append(days).append(" 天");
        }
        if (r.rating > 0) {
            sb.append(" · ★").append(ReadingRecords.formatRating(r.rating));
        }
        return sb.toString();
    }

    private static String borrowLine(BorrowSourceRecords.Record r) {
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次");
        if (!r.source.isEmpty()) {
            sb.append(" · ").append(r.source);
        }
        sb.append(" · ").append(Math.max(1, r.quantity)).append(" 册");
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
        }
        if (!r.dueDate.isEmpty()) {
            sb.append(" — 应还 ").append(r.dueDate);
        }
        sb.append(r.returnDate.isEmpty()
                ? " · 未归还" : " · 已归还 " + r.returnDate);
        return sb.toString();
    }

    private static void addRow(Activity activity, LinearLayout container, String text) {
        int pad = (int) (10 * activity.getResources().getDisplayMetrics().density);
        TextView tv = new TextView(activity);
        tv.setText(text);
        Typo.text(tv, R.dimen.text_body_small);
        tv.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface));
        tv.setBackgroundResource(R.drawable.bg_dialog_row);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        tv.setLayoutParams(lp);
        container.addView(tv);
    }
}
