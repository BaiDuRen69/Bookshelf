package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 书架的字母索引（v5.8.16）：把「按书名 / 按作者」两种排序下的结果映射到 A–Z 桶，
 * 供右侧悬浮索引条拖动跳转。纯计算，不碰视图，可直接单测。
 *
 * <p>字母取自 {@link SearchText#initialLetter}（汉字转拼音取首字母），
 * 数字/符号开头或系统转写不出来时归「#」——与排序键的字符序一致，
 * 所以「#」这一桶本来就排在字母前面。
 */
public final class ShelfIndex {

    public static final String HASH = "#";

    /** 索引条的固定刻度：A–Z + # */
    public static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", HASH
    };

    /** 排序方式：1=按书名、11=按作者（与 arrays.xml 的 sort_entries 下标一致） */
    public static final int SORT_TITLE = 1;
    public static final int SORT_AUTHOR = 11;

    /** 少于此条数不值得出现索引条（小库直接滚更快） */
    public static final int MIN_ITEMS = 20;

    private ShelfIndex() {
    }

    public static boolean alphabetical(int sortType) {
        return sortType == SORT_TITLE || sortType == SORT_AUTHOR;
    }

    /** 每条结果对应的字母桶；非字母序排序返回空列表 */
    public static List<String> letters(List<Book> sorted, int sortType) {
        List<String> out = new ArrayList<>();
        if (sorted == null || !alphabetical(sortType)) {
            return out;
        }
        for (Book book : sorted) {
            String text = sortType == SORT_AUTHOR
                    ? (book.author == null || book.author.trim().isEmpty()
                    ? book.title : book.author)
                    : book.title;
            out.add(SearchText.initialLetter(text));
        }
        return out;
    }

    /** 刻度顺序：# 在最前，然后 A–Z；认不出的返回 -1 */
    public static int rank(String letter) {
        if (letter == null || letter.isEmpty()) {
            return -1;
        }
        if (HASH.equals(letter)) {
            return 0;
        }
        char c = letter.charAt(0);
        return c >= 'A' && c <= 'Z' ? c - 'A' + 1 : -1;
    }

    /**
     * 目标字母（或下一个有内容的字母）在列表里的位置。
     * 列表可能是倒序（开了倒序），所以先判方向再找；全都没有时给最后一格。
     */
    public static int firstPositionAtOrAfter(List<String> letters, String target) {
        if (letters == null || letters.isEmpty()) {
            return -1;
        }
        int want = rank(target);
        if (want < 0) {
            return -1;
        }
        boolean descending = rank(letters.get(letters.size() - 1)) < rank(letters.get(0));
        if (!descending) {
            for (int i = 0; i < letters.size(); i++) {
                if (rank(letters.get(i)) >= want) {
                    return i;
                }
            }
            return letters.size() - 1;
        }
        // 倒序：从上往下找第一个「不比目标字母大」的位置，才是该字母那一组的开头
        for (int i = 0; i < letters.size(); i++) {
            if (rank(letters.get(i)) <= want) {
                return i;
            }
        }
        return letters.size() - 1;
    }

    /** 列表里实际出现过的字母（索引条据此把空刻度画淡） */
    public static Set<String> available(List<String> letters) {
        Set<String> set = new HashSet<>();
        if (letters != null) {
            set.addAll(letters);
        }
        return set;
    }
}
