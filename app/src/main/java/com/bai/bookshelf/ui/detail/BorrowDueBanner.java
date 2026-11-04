package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.util.DateKeys;
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

    /**
     * v5.8.33：「详情页到期条」可以在「通知与提醒」里关掉。
     * 只影响这条横屏的显示，不动借阅记录、到期日与任何数据。
     */
    public static void bind(Activity activity, TextView view, Book book,
                            BorrowRecord open, Runnable reload) {
        if (!com.bai.bookshelf.util.ReminderPrefs.bannerBorrowDue(activity)) {
            view.setVisibility(android.view.View.GONE);
            return;
        }
        view.setVisibility(android.view.View.VISIBLE);
        int due = DateKeys.parseKey(open.dueDate);
        int today = DateKeys.todayKey();
        boolean overdue = due > 0 && due < today;
        String text;
        if (due == 0) {
            text = "借出中 · 未设置应还日期（点按设置）";
        } else if (overdue) {
            text = "已逾期 " + DateKeys.daysBetween(due, today)
                    + " 天 · 应还 " + open.dueDate + " · 点按复制催还文案";
        } else if (due == today) {
            text = "今天到期 · 点按复制催还文案";
        } else {
            text = "应还 " + open.dueDate + " · 还有 "
                    + DateKeys.daysBetween(today, due)
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
            showActions(activity, view, book, open,
                    overdue ? DateKeys.daysBetween(due, today) : 0, reload);
            return true;
        });
    }

    /** 长按：发一张借阅条，或改应还日期（v5.8.18 加借阅条入口） */
    private static void showActions(Activity activity, android.view.View anchor,
                                    Book book, BorrowRecord open, int overdueDays,
                                    Runnable reload) {
        String[] options = {"生成借阅条（发图催还）", "修改应还日期"};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(book.title)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        DetailShare.shareLendSlip(activity,
                                new DetailShare.Slip(book, open, overdueDays), anchor);
                    } else {
                        showDuePicker(activity, open, reload);
                    }
                })
                .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                .show();
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
        int key = DateKeys.parseKey(open.dueDate);
        CalendarPicker.showSingle(activity, "应还日期", key,
                picked -> {
                    open.dueDate = DateKeys.formatKey(picked);
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
