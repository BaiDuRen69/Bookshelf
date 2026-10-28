package com.bai.bookshelf.ui.debug;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookShareCard;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** 仅 debug：渲染第一本有封面的书为分享卡 PNG，供 adb 拉出来目检 */
public class ShareCardPreviewActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BookshelfApp.get().io(() -> {
            Book book = null;
            Bitmap cover = null;
            try {
                List<Book> all = BookshelfApp.get().db().bookDao().getAll();
                // 优先挑信息量最大的（有短评/标签/评分），检查满信息排版
                for (Book x : all) {
                    if (x.review != null && !x.review.trim().isEmpty()) {
                        book = x;
                        break;
                    }
                }
                if (book == null) {
                    for (Book x : all) {
                        if (x.tags != null && !x.tags.isEmpty() && x.rating > 0) {
                            book = x;
                            break;
                        }
                    }
                }
                if (book == null && !all.isEmpty()) {
                    book = all.get(0);
                }
                if (book != null && book.coverUrl != null && !book.coverUrl.isEmpty()) {
                    cover = Glide.with(getApplicationContext()).asBitmap()
                            .load(book.coverUrl.startsWith("http")
                                    ? book.coverUrl : new File(book.coverUrl))
                            .submit(640, 880)
                            .get(6, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                Log.e("ShareCardPreview", "cover load failed", e);
            }
            if (book == null) {
                Log.e("ShareCardPreview", "no book");
                runOnUiThread(this::finish);
                return;
            }
            try {
                Bitmap card = BookShareCard.render(book, cover);
                File dir = new File(getFilesDir(), "share");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File outFile = new File(dir, "preview_book.png");
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    card.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                Log.d("ShareCardPreview", "saved " + outFile.getAbsolutePath()
                        + " for " + book.title);
            } catch (Exception e) {
                Log.e("ShareCardPreview", "render failed", e);
            }
            runOnUiThread(this::finish);
        });
    }
}
