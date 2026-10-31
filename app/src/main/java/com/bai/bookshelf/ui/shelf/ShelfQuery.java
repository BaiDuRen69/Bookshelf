package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookSeries;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.SearchText;
import com.bai.bookshelf.util.SmartShelf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 书架筛选与排序（v5.1，从 ShelfFragment 抽出）：
 * 只接收「已解析好的筛选条件」，不碰 Spinner / 视图，便于单测。
 * 关键词与拼音索引仍由书架维护，这里只做 contains 匹配。
 */
public final class ShelfQuery {

    /** 已解析的筛选条件；字段为 null / 空表示不限 */
    public static final class Criteria {
        public int stockFilter;                 // 0=全部，n=stockStatus n-1
        public int readFilter;                  // 0=全部，n=readStatus n-1
        public Long locationId;                 // null=不限
        public String tagName = "";
        public Category category;               // null=不限；选中一级分类
        public String categorySub = "";         // 非空=只看该二级
        public List<Category> categoryList = Collections.emptyList();
        public int channelType = -1;            // -1=不限
        public String channelSub = "";
        public String country = "";
        public String series = "";
        public Double priceMin;
        public Double priceMax;
        public int[] purchaseRange;             // {起始日键, 结束日键}
        public int[] entryRange;
        public int smartShelf = -1;
        public int sortType;
        public boolean sortReversed;
    }

    private ShelfQuery() {
    }

    public static List<Book> query(List<Book> all, Criteria c, String queryNorm,
                                   Map<Long, String> searchIndex,
                                   Map<Long, BorrowRecord> openLoans,
                                   int todayKey) {
        List<Book> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        String stockTag = c.tagName == null ? "" : c.tagName;
        String country = c.country == null ? "" : c.country;
        String series = c.series == null ? "" : c.series;
        String channelSub = c.channelSub == null ? "" : c.channelSub;
        String categorySub = c.categorySub == null ? "" : c.categorySub;
        List<Category> categoryList = c.categoryList == null
                ? Collections.emptyList() : c.categoryList;

        for (Book book : all) {
            if (queryNorm != null && !queryNorm.isEmpty()) {
                String haystack = searchIndex == null ? null : searchIndex.get(book.id);
                boolean hit = haystack != null
                        ? haystack.contains(queryNorm)
                        : SearchText.matchesFallback(book, queryNorm);
                if (!hit) {
                    continue;
                }
            }
            if (c.smartShelf >= 0) {
                BorrowRecord loan = openLoans == null ? null : openLoans.get(book.id);
                if (!SmartShelf.matches(book, c.smartShelf, loan, todayKey)) {
                    continue;
                }
            }
            if (c.stockFilter > 0 && book.stockStatus != c.stockFilter - 1) {
                continue;
            }
            if (c.readFilter > 0 && book.readStatus != c.readFilter - 1) {
                continue;
            }
            if (c.channelType >= 0) {
                if (book.ownType != c.channelType) {
                    continue;
                }
                if (!channelSub.isEmpty() && !book.sourceChannel.equals(channelSub)) {
                    continue;
                }
            }
            if (c.locationId != null
                    && (book.locationId == null || !c.locationId.equals(book.locationId))) {
                continue;
            }
            if (!stockTag.isEmpty() && !hasTag(book, stockTag)) {
                continue;
            }
            if (c.category != null && !matchesCategory(book, c.category, categorySub, categoryList)) {
                continue;
            }
            if (!country.isEmpty() && !country.equals(
                    book.country == null ? "" : book.country.trim())) {
                continue;
            }
            if (!series.isEmpty() && !series.equalsIgnoreCase(BookSeries.name(book))) {
                continue;
            }
            if (c.priceMin != null || c.priceMax != null) {
                if (book.listPrice <= 0) {
                    continue;
                }
                if (c.priceMin != null && book.listPrice < c.priceMin - 1e-9) {
                    continue;
                }
                if (c.priceMax != null && book.listPrice > c.priceMax + 1e-9) {
                    continue;
                }
            }
            if (c.purchaseRange != null) {
                int[] span = DateKeys.purchaseSpan(book.purchaseDate);
                if (span == null || !inRange(span[0], span[1], c.purchaseRange)) {
                    continue;
                }
            }
            if (c.entryRange != null) {
                int day = DateKeys.dayKey(book.createdAt);
                if (!inRange(day, day, c.entryRange)) {
                    continue;
                }
            }
            result.add(book);
        }
        sort(result, c.sortType, c.sortReversed);
        return result;
    }

    private static boolean matchesCategory(Book book, Category selected,
                                           String sub, List<Category> categoryList) {
        if (!sub.isEmpty()) {
            return book.category.equals(sub) || book.category.equals(selected.name);
        }
        if (book.category.equals(selected.name)) {
            return true;
        }
        for (Category category : categoryList) {
            if (category.parentId != null && category.parentId == selected.id
                    && category.name.equals(book.category)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTag(Book book, String tag) {
        for (String value : book.tags.split(",")) {
            if (value.trim().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inRange(int startKey, int endKey, int[] range) {
        return endKey >= range[0] && startKey <= range[1];
    }

    private static void sort(List<Book> result, int sortType, boolean reversed) {
        switch (sortType) {
            case 1:
                result.sort((x, y) -> x.title.compareToIgnoreCase(y.title));
                break;
            case 2:
                result.sort((x, y) -> Double.compare(
                        BookSanitizer.parseDateOrder(x.publishDate),
                        BookSanitizer.parseDateOrder(y.publishDate)));
                break;
            case 3:
                result.sort((x, y) -> Double.compare(y.listPrice, x.listPrice));
                break;
            case 4:
                result.sort((x, y) -> Double.compare(
                        BookSanitizer.parseDateOrder(y.purchaseDate),
                        BookSanitizer.parseDateOrder(x.purchaseDate)));
                break;
            case 5:
                result.sort((x, y) -> Double.compare(y.purchasePrice, x.purchasePrice));
                break;
            case 6:
                result.sort((x, y) -> Double.compare(parseDouban(y), parseDouban(x)));
                break;
            case 7:
                result.sort((x, y) -> Double.compare(
                        y.ratingAvg > 0 ? y.ratingAvg : y.rating,
                        x.ratingAvg > 0 ? x.ratingAvg : x.rating));
                break;
            case 8:
                result.sort((x, y) -> Double.compare(
                        BookSanitizer.parseWords(y.wordCount),
                        BookSanitizer.parseWords(x.wordCount)));
                break;
            case 9:
                result.sort((x, y) -> Integer.compare(y.title.length(), x.title.length()));
                break;
            case 10:
                result.sort((x, y) -> Integer.compare(y.pages, x.pages));
                break;
            default:
                break;
        }
        if (reversed) {
            Collections.reverse(result);
        }
    }

    private static double parseDouban(Book book) {
        try {
            return Double.parseDouble(book.doubanRating.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
