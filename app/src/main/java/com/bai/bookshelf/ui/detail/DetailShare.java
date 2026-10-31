package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.BookShareCard;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.BorrowReminder;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 图片分享链路（v5.5 书卡 / v5.8.18 借阅条）：
 * 后台取封面 → 画卡 → files/share/ → FileProvider 分享。
 */
public final class DetailShare {

    /** 一次最多发几张借阅条：再多系统分享面板也放不下，且画图耗时 */
    private static final int MAX_SLIPS = 10;

    private DetailShare() {
    }

    /** 一张借阅条需要的三样：书、借出记录、逾期天数（0 = 未逾期） */
    public static final class Slip {
        public final Book book;
        public final BorrowRecord record;
        public final int overdueDays;

        public Slip(Book book, BorrowRecord record, int overdueDays) {
            this.book = book;
            this.record = record;
            this.overdueDays = overdueDays;
        }
    }

    public static void shareBookCard(Activity activity, Book book, View hapticView) {
        BookshelfApp.get().io(() -> {
            final Bitmap cover = loadCover(activity, book);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                Bitmap card = BookShareCard.render(book, cover);
                File file = writeImage(activity, "book_", book.id, card);
                if (file == null) {
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, uriFor(activity, file));
                send.putExtra(Intent.EXTRA_TEXT,
                        "《" + BookText.bareTitle(book.title)
                                + "》" + (book.author.isEmpty()
                                ? "" : " · " + book.author) + " —— 来自「小书房」");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch(activity, Intent.createChooser(send, "分享书卡"), hapticView);
            });
        });
    }

    /** 单本借阅条 */
    public static void shareLendSlip(Activity activity, Slip slip, View hapticView) {
        List<Slip> one = new ArrayList<>();
        one.add(slip);
        shareLendSlips(activity, one, hapticView);
    }

    /**
     * 批量借阅条（逾期书一键催还）：一次发出 N 张图，正文是各本书拼起来的催还文案。
     * 借阅人不同的时候文案里已经带了书名，收到的人按书名对得上。
     */
    public static void shareLendSlips(Activity activity, List<Slip> slips, View hapticView) {
        if (slips == null || slips.isEmpty()) {
            Toast.makeText(activity, "没有可分享的借阅条", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Slip> chosen = slips.size() > MAX_SLIPS
                ? slips.subList(0, MAX_SLIPS) : slips;
        BookshelfApp.get().io(() -> {
            final List<Uri> uris = new ArrayList<>();
            final StringBuilder text = new StringBuilder();
            for (Slip slip : chosen) {
                Bitmap cover = loadCover(activity, slip.book);
                Bitmap card = BookShareCard.renderLendSlip(slip.book, cover,
                        slip.record.borrower, slip.record.dueDate, slip.overdueDays);
                File file = writeImage(activity, "slip_", slip.book.id, card);
                if (file == null) {
                    continue;
                }
                uris.add(uriFor(activity, file));
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(BorrowReminder.reminderText(slip.book, slip.record, null));
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (uris.isEmpty()) {
                    Toast.makeText(activity, "借阅条生成失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent send = uris.size() == 1
                        ? new Intent(Intent.ACTION_SEND)
                        : new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType("image/png");
                if (uris.size() == 1) {
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                            new ArrayList<>(uris));
                }
                send.putExtra(Intent.EXTRA_TEXT, text + "\n—— 来自「小书房」");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch(activity, Intent.createChooser(send, "分享借阅条"), hapticView);
            });
        });
    }

    private static Bitmap loadCover(Activity activity, Book book) {
        try {
            String url = book.coverUrl;
            if (url != null && !url.isEmpty()) {
                return Glide.with(activity.getApplicationContext()).asBitmap()
                        .load(url.startsWith("http") ? url : new File(url))
                        .submit(640, 880)
                        .get(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // 封面取不到照样出图，画布会退回书名首字色块
        }
        return null;
    }

    private static File writeImage(Activity activity, String prefix, long bookId, Bitmap bitmap) {
        try {
            File dir = new File(activity.getFilesDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File outFile = new File(dir,
                    prefix + bookId + "_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return outFile;
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri uriFor(Activity activity, File file) {
        return FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", file);
    }

    private static void launch(Activity activity, Intent chooser, View hapticView) {
        activity.startActivity(chooser);
        if (hapticView != null) {
            hapticView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }
}
