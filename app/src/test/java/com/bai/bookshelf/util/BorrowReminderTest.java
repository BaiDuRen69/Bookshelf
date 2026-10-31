package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.Arrays;

public class BorrowReminderTest {

    @Test
    public void countBorrowedInDueSplitsTodayAndOverdue() {
        int today = DateKeys.todayKey();
        Book dueToday = new Book();
        dueToday.borrowDueDate = DateKeys.formatKey(today);
        Book overdue = new Book();
        overdue.borrowDueDate = DateKeys.formatKey(DateKeys.shiftDayKey(today, -3));
        Book future = new Book();
        future.borrowDueDate = DateKeys.formatKey(DateKeys.shiftDayKey(today, 2));
        Book noDue = new Book();

        int[] counts = BorrowReminder.countBorrowedInDue(
                Arrays.asList(dueToday, overdue, future, noDue));
        assertArrayEquals(new int[]{1, 1}, counts);
    }

    @Test
    public void countBorrowedInDueHandlesEmptyInput() {
        assertArrayEquals(new int[]{0, 0}, BorrowReminder.countBorrowedInDue(null));
    }
}
