package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.ui.widget.CalendarPicker;
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 详情页借阅到期横幅（v5.2，从 BookDetailActivity 抽出）：
 * 显示应还/逾期天数；点按复制催还文案，长按修改应还日期；未设日期时点按直接补设。
 */
public final class BorrowDueBanner {

    private BorrowDueBanner() {
    }

    public static void bind(Activity activity, TextView view, Book book,
                            BorrowRecord open, Runnable reload) {
        view.setVisibility(android.view.View.VISIBLE);
        int due = BorrowReminder.parseKey(open.dueDate);
        int today = BorrowReminder.todayKey();
        boolean overdue = due > 0 && due < today;
        String text;
        if (due == 0) {
            text = "借出中 · 未设置应还日期（点按设置）";
        } else if (overdue) {
            text = "已逾期 " + BorrowReminder.daysBetween(due, today)
                    + " 天 · 应还 " + open.dueDate + " · 点按复制催还文案";
        } else if (due == today) {
            text = "今天到期 · 点按复制催还文案";
        } else {
            text = "应还 " + open.dueDate + " · 还有 "
                    + BorrowReminder.daysBetween(today, due)
                    + " 天 · 点按复制催还文案";
        }
        view.setText(text);
        view.setTextColor(overdue
                ? ContextCompat.getColor(activity, R.color.danger)
                : ThemeManager.resolveColor(activity, R.attr.on_surface));
        view.setOnClickListener(v -> {
            if (due == 0) {
                showDuePicker(activity, open, reload);
            } else {
                copyReminder(activity, book, open);
            }
        });
        view.setOnLongClickListener(v -> {
            showDuePicker(activity, open, reload);
            return true;
        });
    }

    private static void copyReminder(Activity activity, Book book, BorrowRecord open) {
        String text = BorrowReminder.reminderText(book, open, null);
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(
                android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("催还文案", text));
            Toast.makeText(activity, "催还文案已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private static void showDuePicker(Activity activity, BorrowRecord open, Runnable reload) {
        int key = BorrowReminder.parseKey(open.dueDate);
        CalendarPicker.showSingle(activity, "应还日期", key,
                picked -> {
                    open.dueDate = BorrowReminder.formatKey(picked);
                    saveDueDate(activity, open, reload);
                },
                () -> {
                    open.dueDate = "";
                    saveDueDate(activity, open, reload);
                });
    }

    private static void saveDueDate(Activity activity, BorrowRecord open, Runnable reload) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            app.db().borrowRecordDao().update(open);
            app.ui(() -> {
                reload.run();
                BorrowReminder.refresh(activity);
            });
        });
    }
}
