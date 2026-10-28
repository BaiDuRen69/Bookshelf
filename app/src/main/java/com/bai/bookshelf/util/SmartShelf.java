package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.List;
import java.util.Map;

/**
 * 智能书架（v4.55，从 ShelfFragment 抽出）：9 个动态入口的判定与计数。
 *
 * <p>判定只依赖书籍、未归还记录与「今天」，不碰 UI，方便单测；
 * 书架页与入口数量共用同一套规则，避免数字与结果对不上。
 */
public final class SmartShelf {

    public static final String[] NAMES = {"在读", "借出中", "超期", "最近购入",
            "未评分", "无封面", "无地点", "待完善", "遗失"};

    public static final int READING = 0;
    public static final int LENT = 1;
    public static final int OVERDUE = 2;
    public static final int RECENT = 3;
    public static final int UNRATED = 4;
    public static final int NO_COVER = 5;
    public static final int NO_LOCATION = 6;
    public static final int INCOMPLETE = 7;
    public static final int LOST = 8;

    /** 「最近购入」窗口：含今天在内的 30 天 */
    private static final int RECENT_DAYS = 30;

    private SmartShelf() {
    }

    public static boolean matches(Book book, int index, BorrowRecord openLoan, int todayKey) {
        switch (index) {
            case READING:
                return book.readStatus == Book.READING;
            case LENT:
                return book.stockStatus == Book.STOCK_LENT;
            case OVERDUE: {
                int due = openLoan == null ? 0 : DateKeys.parseKey(openLoan.dueDate);
                return due > 0 && due < todayKey;
            }
            case RECENT: {
                int[] span = DateKeys.purchaseSpan(book.purchaseDate);
                if (span == null) {
                    return false;
                }
                int from = DateKeys.shiftDayKey(todayKey, -(RECENT_DAYS - 1));
                return span[1] >= from && span[0] <= todayKey;
            }
            case UNRATED:
                return book.rating == 0;
            case NO_COVER:
                return book.coverUrl.isEmpty();
            case NO_LOCATION:
                return book.locationId == null;
            case INCOMPLETE:
                return book.coverUrl.isEmpty() || book.locationId == null
                        || book.category.isEmpty();
            case LOST:
                return book.stockStatus == Book.STOCK_LOST;
            default:
                return true;
        }
    }

    /** 按入口顺序返回全库数量 */
    public static int[] counts(List<Book> books, Map<Long, BorrowRecord> openLoans, int todayKey) {
        int[] counts = new int[NAMES.length];
        if (books == null || books.isEmpty()) {
            return counts;
        }
        for (Book book : books) {
            BorrowRecord loan = openLoans == null ? null : openLoans.get(book.id);
            for (int i = 0; i < NAMES.length; i++) {
                if (matches(book, i, loan, todayKey)) {
                    counts[i]++;
                }
            }
        }
        return counts;
    }
}
