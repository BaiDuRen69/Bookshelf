package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

public class BookTextTest {

    @Test
    public void quotedTitleStripsExistingBookMarks() {
        assertEquals("《三体》", BookText.quotedTitle("《三体》"));
        assertEquals("《三体》", BookText.quotedTitle("三体"));
    }

    @Test
    public void calendarLabelAddsAuthorWhenPresent() {
        Book book = new Book();
        book.title = "三体";
        book.author = "刘慈欣";
        assertEquals("《三体》 · 刘慈欣", BookText.calendarLabel(book));

        book.author = " ";
        assertEquals("《三体》", BookText.calendarLabel(book));
    }

    @Test
    public void calendarLabelHandlesNullBook() {
        assertEquals("", BookText.calendarLabel(null));
    }
}
