package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.util.Money;
import com.bai.bookshelf.util.DateKeys;
import android.view.View;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.ui.widget.InfoCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 详情页的三块「书目信息 / 藏书信息 / 我的书房」（v5.6 从 BookDetailActivity 抽出）。
 */
public final class DetailInfoBlocks {

    private DetailInfoBlocks() {
    }

    public static void bind(ActivityBookDetailBinding b, Book book, boolean multi, boolean hasLent,
                            String locationName, String categoryDisplay,
                            List<BookCopy> copies, List<BorrowRecord> openRecords) {
        // ===== 书目信息（两列 label/value） =====
        List<String[]> info = new ArrayList<>();
        add(info, "出版社", book.publisher);
        add(info, "出版年", book.publishDate);
        add(info, "印次", printingText(book.printing));
        if (book.pages > 0) {
            add(info, "页数", book.pages + " 页");
        }
        add(info, "字数", book.wordCount);
        if (book.listPrice > 0) {
            add(info, "定价", Money.fixed(book.listPrice));
        }
        if (multi) {
            add(info, book.ownType == Book.OWN_BORROW ? "借阅数量" : "拥有数量",
                    book.quantity + " 册");
        }
        add(info, "豆瓣评分", book.doubanRating);
        add(info, "分类", categoryDisplay);
        add(info, "ISBN", com.bai.bookshelf.util.BookIsbn.format(book.isbn));
        InfoCell.fill(b.llInfoGrid, info);
        b.cardInfo.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

        // ===== 藏书信息 =====
        List<String[]> core = new ArrayList<>();
        // v5.7：来源借阅且已归还的书，状态显示「电子书签」，但不再展示存放地点 / 来源
        boolean borrowReturned = com.bai.bookshelf.util.BookFlags.isReturnedBorrow(book);
        // 书籍被借出 / 电子书签时隐藏存放地点（v2.1、v5.7）
        if (!hasLent && book.stockStatus != Book.STOCK_BOOKMARK) {
            add(core, "存放地点", locationName);
        }
        // 来源：单本时展示；多本时不展示（逐本信息里各自有来源）
        if (!multi && !borrowReturned) {
            String channel = book.sourceChannel;
            if (book.ownType == Book.OWN_BORROW && channel.trim().isEmpty()) {
                com.bai.bookshelf.util.BorrowSourceRecords.Record latest =
                        com.bai.bookshelf.util.BorrowSourceRecords.latest(book);
                if (latest != null && !latest.source.trim().isEmpty()) {
                    channel = latest.source.trim();
                }
            }
            String source = channel.isEmpty()
                    ? com.bai.bookshelf.util.StatusText.own(book.ownType)
                    + "（" + DetailCopyInfo.channelHint(book.ownType) + "）"
                    : com.bai.bookshelf.util.StatusText.own(book.ownType)
                    + " · " + channel;
            add(core, "来源", source);
        }
        if (multi) {
            add(core, "藏书状态", DetailCopyInfo.multiCopyStockSummary(book, copies));
        } else {
            add(core, "藏书状态", com.bai.bookshelf.util.StatusText.stock(book.stockStatus));
            BorrowRecord open1 = DetailCopyInfo.openRecordForCopy(openRecords, 1);
            if (book.stockStatus == Book.STOCK_LENT && open1 != null) {
                add(core, "借给", open1.borrower + "（" + open1.lendDate + "）");
            }
        }
        add(core, "录入时间", com.bai.bookshelf.util.StatusText.formatTime(book.createdAt));
        InfoCell.fill(b.llCoreGrid, core);
        b.cardCore.setVisibility(core.isEmpty() ? View.GONE : View.VISIBLE);

        // ===== 我的书房 =====
        List<String[]> manage = new ArrayList<>();
        // v5.8.33：在读同时又借在外面时合成一行「在读 · 借给 张三」，进度那行照旧不受影响
        java.util.List<String> outNames = new ArrayList<>();
        if (openRecords != null) {
            for (BorrowRecord r : openRecords) {
                if (r != null && r.borrower != null && !r.borrower.trim().isEmpty()
                        && !outNames.contains(r.borrower.trim())) {
                    outNames.add(r.borrower.trim());
                }
            }
        }
        String readLine = com.bai.bookshelf.util.StatusText.read(book.readStatus);
        String lentPart = book.readStatus == Book.READING
                ? com.bai.bookshelf.util.StatusText.lentTo(outNames) : null;
        add(manage, "阅读状态", lentPart == null ? readLine : readLine + " · " + lentPart);
        if (!book.readingStartDate.isEmpty()) {
            add(manage, "开始阅读", book.readingStartDate);
        }
        if (!book.readingFinishDate.isEmpty()) {
            String finish = book.readingFinishDate;
            int startKey = DateKeys.parseKey(book.readingStartDate);
            int finishKey = DateKeys.parseKey(book.readingFinishDate);
            if (startKey > 0 && finishKey >= startKey) {
                finish += "（" + DateKeys.daysBetween(startKey, finishKey) + " 天）";
            }
            add(manage, book.readStatus == Book.READ_DROPPED ? "弃读日期" : "读完日期", finish);
        }
        // v5.7：来源为借阅时展示借阅来源与借阅时间段
        if (book.ownType == Book.OWN_BORROW) {
            com.bai.bookshelf.util.BorrowSourceRecords.Record latest =
                    com.bai.bookshelf.util.BorrowSourceRecords.latest(book);
            String latestSource = latest == null ? "" : latest.source.trim();
            String borrowSource = book.sourceChannel.trim().isEmpty()
                    ? latestSource : book.sourceChannel.trim();
            if (!borrowSource.isEmpty()) {
                add(manage, "借阅来源", borrowSource);
            }
            if (book.borrowStartDate != null && !book.borrowStartDate.trim().isEmpty()) {
                add(manage, "借阅开始", book.borrowStartDate.trim());
            }
            if (book.borrowDueDate != null && !book.borrowDueDate.trim().isEmpty()) {
                String due = book.borrowDueDate.trim();
                int dueKey = com.bai.bookshelf.util.DateKeys.parseKey(due);
                if (dueKey > 0) {
                    if (borrowReturned) {
                        due += "（已归还）";
                    } else {
                        int left = DateKeys.daysBetween(com.bai.bookshelf.util.DateKeys.todayKey(), dueKey);
                        if (left > 0) {
                            due += "（还剩 " + left + " 天）";
                        } else if (left == 0) {
                            due += "（今天到期）";
                        } else {
                            due += "（已逾期 " + (-left) + " 天）";
                        }
                    }
                }
                add(manage, "应还日期", due);
            }
            if (borrowReturned) {
                add(manage, "归还时间", book.borrowReturnDate.trim());
            }
        }
        // 自购才展示购买日期 / 购入价（借阅、赠书不展示）
        if (book.ownType == Book.OWN_BUY) {
            add(manage, "购买日期", book.purchaseDate);
            if (book.purchasePrice > 0) {
                String paid = Money.fixed(book.purchasePrice);
                if (book.listPrice > 0) {
                    paid += String.format(Locale.getDefault(), "（%.2f折）",
                            book.purchasePrice / book.listPrice * 10);
                }
                add(manage, "购入价", paid);
            }
        }
        InfoCell.fill(b.llManageGrid, manage);
        b.cardManage.setVisibility(manage.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static void add(List<String[]> items, String label, String value) {
        InfoCell.add(items, label, value);
    }

    /** 印次存储格式 yyyy-mm|N：能解析出 N 时展示「第 N 次印刷」，否则原样显示 */
    private static String printingText(String printing) {
        int count = com.bai.bookshelf.util.BookSanitizer.parsePrintingCount(printing);
        return count > 0 ? "第 " + count + " 次印刷" : printing;
    }
}
