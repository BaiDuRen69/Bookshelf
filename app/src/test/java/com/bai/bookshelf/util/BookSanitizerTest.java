package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BookSanitizerTest {

    @Test
    public void editionJoinAndSplit() {
        assertEquals("2022", BookSanitizer.joinEdition("2022", "", ""));
        assertEquals("2022-5", BookSanitizer.joinEdition("2022", "5", ""));
        assertEquals("2022|2", BookSanitizer.joinEdition("2022", "", "2"));
        assertEquals("2022-5|2", BookSanitizer.joinEdition("2022", "5", "2"));

        assertArrayEquals(new String[]{"2022", "5", "2"},
                BookSanitizer.splitEdition("2022-5|2"));
        assertNull(BookSanitizer.splitEdition("2022年5月第2版"));
    }

    @Test
    public void sanitizeEditionRemovesDanglingSeparators() {
        assertEquals("2022", BookSanitizer.sanitizeEdition("2022-|"));
        assertEquals("2022-5|2", BookSanitizer.sanitizeEdition("2022-5|2"));
        assertEquals("自由文本", BookSanitizer.sanitizeEdition("自由文本"));
    }

    @Test
    public void parseWords() {
        assertEquals(320000, BookSanitizer.parseWords("320千字"), 0.001);
        assertEquals(280000, BookSanitizer.parseWords("28万字"), 0.001);
        assertEquals(120000, BookSanitizer.parseWords("约 12 万字"), 0.001);
        assertEquals(0, BookSanitizer.parseWords(""), 0.001);
    }

    @Test
    public void parseDateOrder() {
        assertEquals(2021 * 12 + 3, BookSanitizer.parseDateOrder("2021年3月"), 0.001);
        assertEquals(2021 * 12 + 3, BookSanitizer.parseDateOrder("2021-03"), 0.001);
        assertEquals(-1, BookSanitizer.parseDateOrder("不是日期"), 0.001);
    }

    @Test
    public void parsePrintingCount() {
        assertEquals(3, BookSanitizer.parsePrintingCount("2022-1|3"));
        assertEquals(0, BookSanitizer.parsePrintingCount("2022-1"));
        assertEquals(0, BookSanitizer.parsePrintingCount(null));
    }
}
