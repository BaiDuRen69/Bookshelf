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
        bindReading(activity, b, book, null, null, null);
    }

    /**
     * @param loans 该书当前的借出记录（详情页已查过，直接递进来）；
     *              给了就把每场阅读天数里「书在外面」的日子扣掉，并在行尾标一句扣了几天（v5.8.33）
     * @param copies 该书的副本行，用来判断是不是「整本都在外面」；只借走一册时不扣
     * @param dayKeysBySeq 各轮阅读的日集合（seq → 升序日键）；精确轮的天数从这里数，
     *                     传 null 时精确轮按 0 天显示「还没记日子」（v5.9.1）
     */
    public static void bindReading(Activity activity, ActivityBookDetailBinding b, Book book,
                                   java.util.List<com.bai.bookshelf.data.entity.BorrowRecord> loans,
                                   java.util.List<com.bai.bookshelf.data.entity.BookCopy> copies,
                                   java.util.Map<Integer, java.util.List<Integer>> dayKeysBySeq) {
        java.util.List<ReadingRecords.Record> records = ReadingRecords.load(book);
        b.containerReadingRecordsDetail.removeAllViews();
        b.cardReadingRecords.setVisibility(records.isEmpty()
                ? android.view.View.GONE : android.view.View.VISIBLE);
        // 与统计同口径：只有整本书都借出去了才扣，只借走一册时家里那册照样能读
        java.util.List<int[]> spans = com.bai.bookshelf.util.LendSpans
                .ofIfFullyOut(book, copies, loans);
        for (ReadingRecords.Record r : records) {
            java.util.List<Integer> keys = dayKeysBySeq == null ? null : dayKeysBySeq.get(r.seq);
            addRow(activity, b.containerReadingRecordsDetail, readingLine(r, spans, keys));
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

    private static String readingLine(ReadingRecords.Record r, java.util.List<int[]> spans,
                                      java.util.List<Integer> dayKeys) {
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次 · ")
                .append(StatusText.read(r.status));
        boolean precise = r.mode == ReadingRecords.MODE_PRECISE;
        if (precise) {
            // 精确轮标出来，免得和模糊轮的「区间天数」混着读；模糊是默认档，不加标签免噪
            sb.append(" · 精确");
        }
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
            if (!r.endDate.isEmpty()) {
                sb.append(" — ").append(r.endDate);
            }
        }
        if (precise) {
            int days = com.bai.bookshelf.util.ReadDays.precise(dayKeys);
            sb.append(days > 0 ? " · " + days + " 天" : " · 还没记日子");
        } else {
            int days = ReadingRecords.days(r, spans);
            if (days > 0) {
                sb.append(" · ").append(days).append(" 天");
                int raw = ReadingRecords.days(r);
                if (raw > days) {
                    sb.append("（扣掉借出 ").append(raw - days).append(" 天）");
                }
            }
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
