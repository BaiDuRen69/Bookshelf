package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 粘贴 ISBN 清单解析（v5.8.16）：分隔符、规范化、去重与条数上限。
 */
public class IsbnListTest {

    @Test
    public void splitsOnCommonSeparators() {
        String raw = "9787111544937\n9787115373224, 9787020002207；9787536692930 9787020024582";
        assertEquals(5, IsbnList.parse(raw).size());
        assertEquals("9787111544937", IsbnList.parse(raw).get(0));
        assertEquals("9787536692930", IsbnList.parse(raw).get(3));
    }

    @Test
    public void stripsHyphensAndKeepsOrder() {
        List<String> out = IsbnList.parse("978-7-111-54493-7 978-7-115-37322-4");
        assertEquals(2, out.size());
        assertEquals("9787111544937", out.get(0));
        assertEquals("9787115373224", out.get(1));
    }

    @Test
    public void acceptsIsbn10WithCheckX() {
        List<String> out = IsbnList.parse("0-306-40615-X 0306406152");
        assertEquals(2, out.size());
        assertEquals("030640615X", out.get(0));
        assertEquals("0306406152", out.get(1));
    }

    @Test
    public void dedupesSameBookWrittenTwoWays() {
        List<String> out = IsbnList.parse("9787111544937\n978-7-111-54493-7\n9787111544937");
        assertEquals(1, out.size());
    }

    @Test
    public void dropsWrongLengthsAndNoise() {
        String raw = "书单如下：\n97871115449\n97871115449371\n9787111544937\nisbn 9787121277305";
        List<String> out = IsbnList.parse(raw);
        assertEquals(2, out.size());
        assertEquals("9787111544937", out.get(0));
        assertEquals("9787121277305", out.get(1));
    }

    @Test
    public void ignoresEmptyInput() {
        assertTrue(IsbnList.parse(null).isEmpty());
        assertTrue(IsbnList.parse("").isEmpty());
        assertTrue(IsbnList.parse("   \n ， ").isEmpty());
    }

    @Test
    public void capsAtMax() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            // 13 位数字，只要位数对就收下（不校验校验位）
            sb.append("97871").append(String.format(java.util.Locale.US, "%08d", i)).append('\n');
        }
        List<String> capped = IsbnList.parse(sb.toString());
        assertEquals(IsbnList.MAX, capped.size());
        assertEquals(300, IsbnList.parse(sb.toString(), Integer.MAX_VALUE).size());
    }

    @Test
    public void normalizesDigitsAndX() {
        assertEquals("9787111544937", IsbnList.normalize("978-7-111-54493-7"));
        assertEquals("030640615X", IsbnList.normalize("0 306 40615 x"));
        assertEquals("", IsbnList.normalize(null));
        assertEquals("", IsbnList.normalize("无书号"));
    }

    @Test
    public void validatesShapeOnly() {
        assertTrue(IsbnList.isValid("9787111544937"));
        assertTrue(IsbnList.isValid("030640615X"));
        assertFalse("13 位不该出现校验字母", IsbnList.isValid("978711154493X"));
        assertFalse(IsbnList.isValid("978711154493"));
        assertFalse(IsbnList.isValid("97871115449371"));
        assertFalse(IsbnList.isValid(""));
        assertFalse(IsbnList.isValid(null));
    }
}
