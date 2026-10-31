package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BookIsbnTest {

    @Test
    public void formatsIsbn13() {
        assertEquals("978-7-536-69293-0", BookIsbn.format("9787536692930"));
        assertEquals("979-1-234-56789-7", BookIsbn.format("9791234567897"));
    }

    @Test
    public void formatsIsbn10() {
        assertEquals("7-536-69293-0", BookIsbn.format("7536692930"));
    }

    @Test
    public void keepsExistingSeparators() {
        assertEquals("978-7-536-69293-0", BookIsbn.format("978-7-536-69293-0"));
    }

    @Test
    public void returnsOriginalWhenUnrecognized() {
        assertEquals("", BookIsbn.format(null));
        assertEquals("123", BookIsbn.format("123"));
        assertEquals("978123", BookIsbn.format("978123"));
    }
}
