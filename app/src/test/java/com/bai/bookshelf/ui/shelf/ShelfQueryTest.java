package com.bai.bookshelf.ui.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.util.SmartShelf;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShelfQueryTest {

    @Test
    public void emptyCriteriaKeepsAllBooksInOrder() {
        Book a = book(1, "A");
        Book b = book(2, "B");
        List<Book> result = ShelfQuery.query(Arrays.asList(a, b),
                new ShelfQuery.Criteria(), "", null, null, 20261027);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).id);
        assertEquals(2, result.get(1).id);
    }

    @Test
    public void stockReadAndSmartFilters() {
        Book reading = book(1, "在读");
        reading.readStatus = Book.READING;
        Book lent = book(2, "借出");
        lent.stockStatus = Book.STOCK_LENT;
        Book lost = book(3, "遗失");
        lost.stockStatus = Book.STOCK_LOST;

        ShelfQuery.Criteria read = new ShelfQuery.Criteria();
        read.readFilter = Book.READING + 1;
        assertEquals(1, ShelfQuery.query(Arrays.asList(reading, lent, lost),
                read, "", null, null, 20261027).size());

        ShelfQuery.Criteria stock = new ShelfQuery.Criteria();
        stock.stockFilter = Book.STOCK_LENT + 1;
        assertEquals(1, ShelfQuery.query(Arrays.asList(reading, lent, lost),
                stock, "", null, null, 20261027).size());

        ShelfQuery.Criteria smart = new ShelfQuery.Criteria();
        smart.smartShelf = SmartShelf.READING;
        assertEquals(1, ShelfQuery.query(Arrays.asList(reading, lent, lost),
                smart, "", null, null, 20261027).size());
    }

    @Test
    public void locationTagCountrySeriesFilters() {
        Book target = book(1, "三体");
        target.locationId = 1L;
        target.tags = "科幻,小说";
        target.country = "中国";
        target.seriesName = "三体";
        Book other = book(2, "Guns");
        other.locationId = 2L;
        other.tags = "计算机";
        other.country = "美国";

        ShelfQuery.Criteria c = new ShelfQuery.Criteria();
        c.locationId = 1L;
        c.tagName = "科幻";
        c.country = "中国";
        c.series = "三体";
        List<Book> result = ShelfQuery.query(Arrays.asList(target, other),
                c, "", null, null, 20261027);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).id);
    }

    @Test
    public void priceAndDateRanges() {
        Book inRange = book(1, "在区间");
        inRange.listPrice = 23;
        inRange.purchaseDate = "2026-10-01";
        inRange.createdAt = date(2026, Calendar.OCTOBER, 1);
        Book cheap = book(2, "太便宜");
        cheap.listPrice = 9;
        cheap.purchaseDate = "2026-10-01";
        cheap.createdAt = inRange.createdAt;

        ShelfQuery.Criteria c = new ShelfQuery.Criteria();
        c.priceMin = 20.0;
        c.priceMax = 30.0;
        c.purchaseRange = new int[]{20261001, 20261031};
        c.entryRange = new int[]{20261001, 20261001};
        List<Book> result = ShelfQuery.query(Arrays.asList(inRange, cheap),
                c, "", null, null, 20261027);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).id);
    }

    @Test
    public void channelAndCategoryFilters() {
        Category parent = category(1, "文学", null);
        Category child = category(2, "小说", 1L);
        Book novel = book(1, "小说书");
        novel.category = "小说";
        novel.ownType = Book.OWN_BUY;
        novel.sourceChannel = "淘宝";
        Book history = book(2, "历史书");
        history.category = "历史";
        history.ownType = Book.OWN_BUY;
        history.sourceChannel = "京东";
        Book gift = book(3, "赠书");
        gift.category = "小说";
        gift.ownType = Book.OWN_GIFT;
        gift.sourceChannel = "淘宝";

        ShelfQuery.Criteria c = new ShelfQuery.Criteria();
        c.channelType = Book.OWN_BUY;
        c.channelSub = "淘宝";
        c.category = parent;
        c.categoryList = Arrays.asList(parent, child);
        List<Book> result = ShelfQuery.query(Arrays.asList(novel, history, gift),
                c, "", null, null, 20261027);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).id);

        c.categorySub = "小说";
        List<Book> sub = ShelfQuery.query(Arrays.asList(novel, history, gift),
                c, "", null, null, 20261027);
        assertEquals(1, sub.size());
    }

    @Test
    public void searchUsesIndexThenFallback() {
        Book indexed = book(1, "红楼梦");
        Book fallback = book(2, "Guns");
        Map<Long, String> index = new HashMap<>();
        index.put(1L, "hongloumeng\u0001hongloumeng\u0001hlm");
        List<Book> result = ShelfQuery.query(Arrays.asList(indexed, fallback),
                new ShelfQuery.Criteria(), "hlm", index, null, 20261027);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).id);

        List<Book> byFallback = ShelfQuery.query(Arrays.asList(indexed, fallback),
                new ShelfQuery.Criteria(), "guns", Collections.emptyMap(), null, 20261027);
        assertEquals(1, byFallback.size());
        assertEquals(2, byFallback.get(0).id);
    }

    @Test
    public void sortingAndReversing() {
        Book a = book(1, "B书");
        a.listPrice = 10;
        Book b = book(2, "A书");
        b.listPrice = 30;
        List<Book> books = Arrays.asList(a, b);

        ShelfQuery.Criteria byTitle = new ShelfQuery.Criteria();
        byTitle.sortType = 1;
        assertEquals(2, ShelfQuery.query(books, byTitle, "", null, null, 20261027)
                .get(0).id);

        ShelfQuery.Criteria byPrice = new ShelfQuery.Criteria();
        byPrice.sortType = 3;
        List<Book> priced = ShelfQuery.query(books, byPrice, "", null, null, 20261027);
        assertEquals(2, priced.get(0).id);
        assertEquals(1, priced.get(1).id);

        byPrice.sortReversed = true;
        List<Book> reversed = ShelfQuery.query(books, byPrice, "", null, null, 20261027);
        assertEquals(1, reversed.get(0).id);
    }

    @Test
    public void nullAllReturnsEmpty() {
        assertTrue(ShelfQuery.query(null, new ShelfQuery.Criteria(), "",
                null, null, 20261027).isEmpty());
    }

    private static Book book(long id, String title) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = "作者";
        b.publisher = "出版社";
        b.coverUrl = "covers/c.jpg";
        b.locationId = 1L;
        b.category = "小说";
        b.rating = 5;
        return b;
    }

    @Test
    public void sortByAuthorAndTitle() {
        Book zhang = book(1, "C 书");
        zhang.author = "Zhang San";
        Book li = book(2, "B 书");
        li.author = "Li Si";
        Book none = book(3, "A 书");
        none.author = "";

        ShelfQuery.Criteria byAuthor = new ShelfQuery.Criteria();
        byAuthor.sortType = 11;
        List<Book> authors = ShelfQuery.query(Arrays.asList(zhang, li, none),
                byAuthor, "", null, null, 20261027);
        // 作者字母序：Li < Zhang < 没填作者的排最后
        assertEquals(2, authors.get(0).id);
        assertEquals(1, authors.get(1).id);
        assertEquals(3, authors.get(2).id);

        ShelfQuery.Criteria byTitle = new ShelfQuery.Criteria();
        byTitle.sortType = 1;
        List<Book> titles = ShelfQuery.query(Arrays.asList(zhang, li, none),
                byTitle, "", null, null, 20261027);
        assertEquals(3, titles.get(0).id);

        ShelfQuery.Criteria reversed = new ShelfQuery.Criteria();
        reversed.sortType = 1;
        reversed.sortReversed = true;
        List<Book> desc = ShelfQuery.query(Arrays.asList(zhang, li, none),
                reversed, "", null, null, 20261027);
        assertEquals(1, desc.get(0).id);
    }

    private static Category category(long id, String name, Long parentId) {
        Category c = new Category();
        c.id = id;
        c.name = name;
        c.parentId = parentId;
        return c;
    }

    private static long date(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
