package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PriceSplitterTest {

    @Test
    public void splitsByListPriceAndKeepsTotal() throws Exception {
        List<PriceSplitter.AllocResult> result = PriceSplitter.split(100, books(23, 32, 68));
        assertEquals(18.70, result.get(0).allocatedPrice, 0.001);
        assertEquals(26.02, result.get(1).allocatedPrice, 0.001);
        assertEquals(55.28, result.get(2).allocatedPrice, 0.001);
        double sum = 0;
        for (PriceSplitter.AllocResult r : result) {
            sum += r.allocatedPrice;
        }
        assertEquals(100.0, sum, 0.001);
    }

    @Test
    public void lastBookAbsorbsRoundingDifference() throws Exception {
        List<PriceSplitter.AllocResult> result = PriceSplitter.split(10, books(10, 90));
        assertEquals(1.00, result.get(0).allocatedPrice, 0.001);
        assertEquals(9.00, result.get(1).allocatedPrice, 0.001);
    }

    @Test
    public void rejectsInvalidInput() {
        assertThrows(PriceSplitter.SplitException.class,
                () -> PriceSplitter.split(10, new ArrayList<>()));
        assertThrows(PriceSplitter.SplitException.class,
                () -> PriceSplitter.split(-1, books(10)));
        assertThrows(PriceSplitter.SplitException.class,
                () -> PriceSplitter.split(10, books(0, 0)));
        assertThrows(PriceSplitter.SplitException.class,
                () -> PriceSplitter.split(10, books(-1, 10)));
    }

    private static List<PriceSplitter.BookInput> books(double... prices) {
        List<PriceSplitter.BookInput> list = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            list.add(new PriceSplitter.BookInput(i + 1, "书" + (i + 1), prices[i]));
        }
        return list;
    }
}
