package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BookFormTextTest {

    @Test
    public void stripWanSuffix() {
        assertEquals("28", BookFormText.stripWanSuffix("28万字"));
        assertEquals("28", BookFormText.stripWanSuffix(" 28万 "));
        assertEquals("320千字", BookFormText.stripWanSuffix("320千字"));
        assertEquals("", BookFormText.stripWanSuffix(null));
    }

    @Test
    public void wordCountToStore() {
        assertEquals("28万字", BookFormText.wordCountToStore("28"));
        assertEquals("28万", BookFormText.wordCountToStore("28万"));
        assertEquals("320千字", BookFormText.wordCountToStore("320千字"));
        assertEquals("", BookFormText.wordCountToStore(""));
        assertEquals("", BookFormText.wordCountToStore(null));
    }
}
