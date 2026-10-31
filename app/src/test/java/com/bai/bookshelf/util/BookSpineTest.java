package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

/** 书脊厚度规则（v5.8.14）：1 册 6dp、2–3 册 8dp、套装/4 册以上 10dp */
public class BookSpineTest {

    private Book book(int quantity, String series) {
        Book b = new Book();
        b.title = "测试";
        b.quantity = quantity;
        b.seriesName = series;
        return b;
    }

    @Test
    public void 单册最薄() {
        assertEquals(BookSpine.SINGLE_DP, BookSpine.widthDp(book(1, "")));
    }

    @Test
    public void 两到三册加厚() {
        assertEquals(BookSpine.MULTI_DP, BookSpine.widthDp(book(2, "")));
        assertEquals(BookSpine.MULTI_DP, BookSpine.widthDp(book(3, "")));
    }

    @Test
    public void 四册以上与套装最厚() {
        assertEquals(BookSpine.SET_DP, BookSpine.widthDp(book(4, "")));
        assertEquals(BookSpine.SET_DP, BookSpine.widthDp(book(1, "银河帝国")));
        // 套装优先于册数：三卷一套仍是 10dp
        assertEquals(BookSpine.SET_DP, BookSpine.widthDp(book(3, "银河帝国")));
    }

    @Test
    public void 空对象按单册处理() {
        assertEquals(BookSpine.SINGLE_DP, BookSpine.widthDp(null));
    }
}
