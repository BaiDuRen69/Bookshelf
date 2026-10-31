package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class XlsxPartsTest {

    @Test
    public void colNameHandlesMultiLetterColumns() {
        assertEquals("A", XlsxParts.colName(0));
        assertEquals("Z", XlsxParts.colName(25));
        assertEquals("AA", XlsxParts.colName(26));
        assertEquals("AB", XlsxParts.colName(27));
    }

    @Test
    public void isNumberAcceptsDecimalsOnly() {
        assertTrue(XlsxParts.isNumber("12"));
        assertTrue(XlsxParts.isNumber("12.5"));
        assertFalse(XlsxParts.isNumber(""));
        assertFalse(XlsxParts.isNumber(null));
        assertFalse(XlsxParts.isNumber("12元"));
    }

    @Test
    public void escapeCoversXmlSpecials() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;",
                XlsxParts.escape("&<>\"'"));
    }

    @Test
    public void sheetXmlWritesNumbersAndEscapesText() {
        String[] header = new String[]{"书名", "", "", "", "", "", "", "", "页数"};
        String[] row = new String[]{"A&B", "", "", "", "", "", "", "", "128"};
        String xml = XlsxParts.sheetXml(Arrays.asList(header, row), true);
        // 数值列写成 <v>，文本列内联并转义
        assertTrue(xml.contains("<c r=\"I2\"><v>128</v></c>"));
        assertTrue(xml.contains("A&amp;B"));
        // 有封面时数据行加高 + 挂 drawing
        assertTrue(xml.contains("<row r=\"2\" ht=\"56\" customHeight=\"1\">"));
        assertTrue(xml.contains("<drawing r:id=\"rId1\"/>"));
    }

    @Test
    public void sheetXmlWithoutImagesHasNoDrawing() {
        String xml = XlsxParts.sheetXml(
                Collections.singletonList(new String[]{"书名"}), false);
        assertFalse(xml.contains("drawing"));
        assertFalse(xml.contains("ht=\"56\""));
    }
}
