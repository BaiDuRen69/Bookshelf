package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.StatusText;

import java.util.List;
import java.util.Map;

/**
 * 详情页逐本信息（v5.7，从 BookDetailActivity 抽出）：
 * 第 N 本的实体兜底、未归还记录、备注、摘要行与库存汇总。
 */
public final class DetailCopyInfo {

    private DetailCopyInfo() {
    }

    /** 第 index 本的实体数据（第 1 本取主记录；第 2 本起取副本表，缺失则默认值） */
    public static BookCopy copyByIndex(Book book, List<BookCopy> copies, int index) {
        if (index == 1) {
            BookCopy c = new BookCopy();
            c.bookId = book.id;
            c.copyIndex = 1;
            c.stockStatus = book.stockStatus;
            c.locationId = book.locationId;
            c.ownType = book.ownType;
            c.sourceChannel = book.sourceChannel;
            c.purchaseDate = book.purchaseDate;
            c.purchasePrice = book.purchasePrice;
            return c;
        }
        for (BookCopy c : copies) {
            if (c.copyIndex == index) {
                return c;
            }
        }
        BookCopy empty = new BookCopy();
        empty.bookId = book.id;
        empty.copyIndex = index;
        empty.stockStatus = Book.STOCK_IN;
        empty.ownType = Book.OWN_BUY;
        return empty;
    }

    /** 第 index 本当前未归还的借出记录（没有则 null） */
    public static BorrowRecord openRecordForCopy(List<BorrowRecord> openRecords, int index) {
        for (BorrowRecord record : openRecords) {
            if (record.copyIndex == index) {
                return record;
            }
        }
        return null;
    }

    /** 第 index 本的备注（第 1 本沿用书级备注，此处返回空；第 2 本起取副本备注） */
    public static String copyNoteText(Book book, List<BookCopy> copies, int index) {
        if (index == 1) {
            return "";
        }
        BookCopy copy = copyByIndex(book, copies, index);
        return copy.note == null ? "" : copy.note.trim();
    }

    /** 第 index 本的一行摘要：状态｜地点｜来源｜日期｜实付｜借给｜借出备注 */
    public static String copyLine(Book book, List<BookCopy> copies,
                                  List<BorrowRecord> openRecords,
                                  Map<Long, String> copyLocNames,
                                  String locationName,
                                  int index, BorrowRecord open) {
        BookCopy copy = copyByIndex(book, copies, index);
        StringBuilder line = new StringBuilder("第 " + index + " 册：");
        boolean sourceReturned = com.bai.bookshelf.util.BookFlags.isReturnedBorrow(book);
        if (sourceReturned) {
            // v5.7：已归还给借阅来源 → 状态归入电子书签，不再显示地点 / 来源 / 价格
            line.append(StatusText.stock(Book.STOCK_BOOKMARK)).append("｜已归还给借阅来源");
        } else {
            line.append(StatusText.stock(copy.stockStatus));
            if (index == 1) {
                if (book.locationId != null && openRecords.isEmpty()) {
                    line.append("｜").append(locationName);
                }
            } else if (copy.locationId != null) {
                line.append("｜").append(copyLocNames.get(copy.locationId));
            }
            String source = copy.sourceChannel.isEmpty()
                    ? StatusText.own(copy.ownType) + "（" + channelHint(copy.ownType) + "）"
                    : StatusText.own(copy.ownType) + " · " + copy.sourceChannel;
            line.append("｜").append(source);
            // v5.7：借阅 / 赠书不展示购买日期与实付
            if (copy.ownType == Book.OWN_BUY) {
                if (!copy.purchaseDate.isEmpty()) {
                    line.append("｜").append(copy.purchaseDate);
                }
                if (copy.purchasePrice > 0) {
                    line.append("｜实付 ¥").append(copy.purchasePrice);
                }
            }
        }
        if (open != null) {
            line.append("｜借给 ").append(open.borrower)
                    .append("（").append(open.lendDate).append("）");
            int due = BorrowReminder.parseKey(open.dueDate);
            if (due > 0) {
                line.append("｜应还 ").append(open.dueDate);
                if (due < BorrowReminder.todayKey()) {
                    line.append("（逾期）");
                }
            }
        }
        return line.toString();
    }

    /** 未填渠道时的类型化提示 */
    public static String channelHint(int ownType) {
        if (ownType == Book.OWN_BORROW) {
            return "未填借阅来源";
        }
        if (ownType == Book.OWN_GIFT) {
            return "未填赠送来源";
        }
        return "未填购买渠道";
    }

    /** 多本时的藏书状态汇总：共 N 本（在库 x、借出 y、遗失 z） */
    public static String multiCopyStockSummary(Book book, List<BookCopy> copies) {
        int in = 0;
        int lent = 0;
        int lost = 0;
        for (int i = 1; i <= book.quantity; i++) {
            int status = copyByIndex(book, copies, i).stockStatus;
            if (status == Book.STOCK_LENT) {
                lent++;
            } else if (status == Book.STOCK_LOST) {
                lost++;
            } else {
                in++;
            }
        }
        StringBuilder sb = new StringBuilder("共 " + book.quantity + " 册（");
        sb.append("在库 ").append(in);
        if (lent > 0) {
            sb.append("、借出 ").append(lent);
        }
        if (lost > 0) {
            sb.append("、遗失 ").append(lost);
        }
        return sb.append("）").toString();
    }
}
