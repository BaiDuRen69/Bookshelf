package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookShareCard;
import com.bai.bookshelf.util.BookText;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * 详情页分享书卡（v5.5，从 BookDetailActivity 抽出）：
 * 后台取封面 → 画 1080×1440 卡片 → files/share/ → FileProvider 分享。
 */
public final class DetailShare {

    private DetailShare() {
    }

    public static void shareBookCard(Activity activity, Book book, View hapticView) {
        BookshelfApp.get().io(() -> {
            Bitmap cover = null;
            try {
                String url = book.coverUrl;
                if (url != null && !url.isEmpty()) {
                    cover = Glide.with(activity.getApplicationContext()).asBitmap()
                            .load(url.startsWith("http") ? url : new File(url))
                            .submit(640, 880)
                            .get(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
            final Bitmap fCover = cover;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                Bitmap card = BookShareCard.render(book, fCover);
                try {
                    File dir = new File(activity.getFilesDir(), "share");
                    if (!dir.exists() && !dir.mkdirs()) {
                        return;
                    }
                    File outFile = new File(dir,
                            "book_" + book.id + "_" + System.currentTimeMillis() + ".png");
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        card.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    Uri uri = FileProvider.getUriForFile(activity,
                            activity.getPackageName() + ".fileprovider", outFile);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TEXT,
                            "《" + BookText.bareTitle(book.title)
                                    + "》" + (book.author.isEmpty()
                                    ? "" : " · " + book.author) + " —— 来自「小书房」");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(send, "分享书卡"));
                    if (hapticView != null) {
                        hapticView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                } catch (Exception e) {
                    Toast.makeText(activity, "分享失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
