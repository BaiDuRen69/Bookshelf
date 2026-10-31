package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;

import java.util.List;

/**
 * 想读清单查重（v5.1）：
 * - 两边都有 ISBN 时按 ISBN 判断（忽略连字符 / 大小写），不同 ISBN 视为不同版本；
 * - 有一边缺 ISBN 时退回「书名 + 作者」，作者任意一边为空则只看书名。
 *
 * <p>编辑已有条目时按 id 排除自身，避免「改自己也被判重复」。
 */
public final class WishBookMatcher {

    private WishBookMatcher() {
    }

    /** 找到与 candidate 重复的已有条目；没有返回 null */
    public static WishBook findDuplicate(List<WishBook> items, WishBook candidate) {
        if (items == null || candidate == null) {
            return null;
        }
        String title = normalize(candidate.title);
        if (title.isEmpty()) {
            return null;
        }
        String author = normalize(candidate.author);
        String isbn = normalizeIsbn(candidate.isbn);
        for (WishBook item : items) {
            if (item == null) {
                continue;
            }
            if (candidate.id > 0 && item.id == candidate.id) {
                continue;
            }
            String itemIsbn = normalizeIsbn(item.isbn);
            if (!isbn.isEmpty() && !itemIsbn.isEmpty()) {
                if (isbn.equals(itemIsbn)) {
                    return item;
                }
                continue; // 两个 ISBN 都存在且不同：不同版本，允许
            }
            if (!title.equals(normalize(item.title))) {
                continue;
            }
            String itemAuthor = normalize(item.author);
            if (author.isEmpty() || itemAuthor.isEmpty() || author.equals(itemAuthor)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 想读候选是否已在书架里（仅提示，不阻止添加）。
     * 规则与想读查重一致：两边有 ISBN 按 ISBN；否则书名 + 作者（作者任意一边为空只看书名）。
     * 已归还给借阅来源的电子书签不算「在书架」。
     */
    public static Book findInLibrary(List<Book> library, WishBook candidate) {
        if (library == null || candidate == null) {
            return null;
        }
        String title = normalize(candidate.title);
        if (title.isEmpty()) {
            return null;
        }
        String author = normalize(candidate.author);
        String isbn = normalizeIsbn(candidate.isbn);
        for (Book book : library) {
            if (book == null || BookFlags.isReturnedBorrow(book)) {
                continue;
            }
            String bookIsbn = normalizeIsbn(book.isbn);
            if (!isbn.isEmpty() && !bookIsbn.isEmpty()) {
                if (isbn.equals(bookIsbn)) {
                    return book;
                }
                continue; // 两个 ISBN 都存在且不同：不同版本，仍允许
            }
            if (!title.equals(normalize(book.title))) {
                continue;
            }
            String bookAuthor = normalize(book.author);
            if (author.isEmpty() || bookAuthor.isEmpty() || author.equals(bookAuthor)) {
                return book;
            }
        }
        return null;
    }

    /** ISBN 归一化：只保留数字与 X，并转大写 */
    public static String normalizeIsbn(String isbn) {
        if (isbn == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(isbn.length());
        for (int i = 0; i < isbn.length(); i++) {
            char c = Character.toUpperCase(isbn.charAt(i));
            if (Character.isDigit(c) || c == 'X') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 书名 / 作者归一化：忽略大小写、空格与标点 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
