package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.List;

/**
 * 借阅来源相关的书籍状态（v5.7）：
 * - 借阅中的书不参与价格 / 购买统计，但字数与阅读状态照常统计；
 * - 已归还给借阅来源的书归入「电子书签」，不再算藏书总量与购买统计。
 */
public final class BookFlags {

    private BookFlags() {
    }

    /** 来源为借阅（无论是否已归还） */
    public static boolean isBorrowSource(Book book) {
        return book != null && book.ownType == Book.OWN_BORROW;
    }

    /** 来源为借阅且藏书状态已是「电子书签」 */
    public static boolean isReturnedBorrow(Book book) {
        return isBorrowSource(book) && book.stockStatus == Book.STOCK_BOOKMARK;
    }

    /** 仍算作当前藏书（已归还的电子书签不计入） */
    public static boolean countsAsOwned(Book book) {
        return !isReturnedBorrow(book);
    }

    /** 当前藏书的数量（用于书架 meta / 统计总册数） */
    public static int countOwned(List<Book> books) {
        int count = 0;
        if (books == null) {
            return 0;
        }
        for (Book book : books) {
            if (countsAsOwned(book)) {
                count++;
            }
        }
        return count;
    }
}
