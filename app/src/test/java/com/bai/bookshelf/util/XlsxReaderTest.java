package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XlsxReaderTest {

    @Test
    public void colIndexHandlesSingleAndDoubleLetters() {
        assertEquals(0, XlsxReader.colIndex("A1"));
        assertEquals(1, XlsxReader.colIndex("B2"));
        assertEquals(25, XlsxReader.colIndex("Z9"));
        assertEquals(26, XlsxReader.colIndex("AA1"));
        assertEquals(27, XlsxReader.colIndex("AB12"));
    }

    @Test
    public void colIndexIgnoresRowDigits() {
        assertEquals(0, XlsxReader.colIndex("A100"));
        assertEquals(26, XlsxReader.colIndex("AA999"));
    }

    @Test
    public void cellTrimsAndHandlesOutOfRange() {
        String[] row = new String[]{" 书名 ", null, "作者"};
        assertEquals("书名", XlsxReader.cell(row, 0));
        assertEquals("", XlsxReader.cell(row, 1));
        assertEquals("作者", XlsxReader.cell(row, 2));
        assertEquals("", XlsxReader.cell(row, 3));
        assertEquals("", XlsxReader.cell(row, -1));
    }
}
