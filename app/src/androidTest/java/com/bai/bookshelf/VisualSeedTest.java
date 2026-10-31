package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉验收样本（v5.8.22 批次 H 第七节）：给 devtest 灌 12 本带封面的合成书，
 * 让「封面四角、卡片 16 / hero 20 层级、暗色描边、字体 2.0 不裁字」这几项有东西可看。
 *
 * <p>只写 devtest 包（与正式版数据完全隔离），书名统一前缀、ISBN 统一段号，
 * 收尾跑 {@link #清掉视觉验收样本()} 精确删干净，不给正式版留任何痕迹。
 */
@RunWith(AndroidJUnit4.class)
public class VisualSeedTest {

    private static final String TITLE_PREFIX = "视觉样本";
    private static final String ISBN_PREFIX = "9789900";
    private static final int COUNT = 12;

    @Test
    public void 灌视觉验收样本() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        AppDatabase db = BookshelfApp.get().db();
        assertTrue("先清干净再灌", findSeeded(db).isEmpty());

        File coverDir = new File(ctx.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        coverDir.mkdirs();
        List<Long> ids = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < COUNT; i++) {
            File cover = new File(coverDir, "seed_cover_" + i + ".jpg");
            writeCover(cover, i);
            Book b = new Book();
            b.title = TITLE_PREFIX + (i + 1);
            b.author = new String[]{"刘震云", "余华", "东野圭吾", "乔治·伽莫夫"}[i % 4];
            b.publisher = new String[]{"花城出版社", "人民文学出版社", "上海译文出版社"}[i % 3];
            b.isbn = ISBN_PREFIX + String.format("%5d", i);
            b.category = i % 3 == 0 ? "文学" : (i % 3 == 1 ? "科普" : "历史");
            b.tags = i % 2 == 0 ? "重读,常翻" : "新买";
            b.pages = 180 + i * 37;
            b.listPrice = 29 + i * 3;
            b.purchasePrice = 18 + i * 2;
            b.purchaseDate = "2026-0" + (i % 9 + 1) + "-1" + (i % 9);
            b.rating = i % 5;
            b.quantity = i % 4 == 0 ? 3 : 1;
            b.coverUrl = cover.getAbsolutePath();
            b.createdAt = now - i * 3_600_000L;
            b.updatedAt = now - i * 3_600_000L;
            b.readStatus = i < 5 ? Book.READ_DONE : (i < 8 ? Book.READING : Book.READ_NONE);
            b.readingProgress = i < 5 ? 100 : (i < 8 ? 30 + i * 5 : 0);
            b.stockStatus = (i >= 8 && i <= 10) ? Book.STOCK_LENT : Book.STOCK_IN;
            if (i % 5 == 0) {
                b.seriesName = "样本套装";
                b.seriesVolume = String.valueOf(i / 5 + 1);
            }
            long id = db.bookDao().insert(b);
            ids.add(id);
            if (i % 2 == 0) {
                Excerpt e = new Excerpt();
                e.bookId = id;
                e.content = "这是第 " + (i + 1) + " 本的样本摘抄，用来看摘抄卡片与圆角在列表里的表现。";
                e.note = "样本批注";
                e.tags = "金句";
                e.createdAt = now;
                db.excerptDao().insert(e);
            }
            if (b.stockStatus == Book.STOCK_LENT) {
                BorrowRecord r = new BorrowRecord();
                r.bookId = id;
                r.borrower = "同学甲";
                r.lendDate = "2026-09-20";
                r.dueDate = "2026-10-20";
                r.status = BorrowRecord.STATUS_OUT;
                r.copyIndex = 1;
                db.borrowRecordDao().insert(r);
            }
        }
        assertEquals(COUNT, findSeeded(db).size());
        android.util.Log.i("VisualSeed", "已灌 " + COUNT + " 本样本，ids=" + ids);
    }

    @Test
    public void 清掉视觉验收样本() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        AppDatabase db = BookshelfApp.get().db();
        for (Book b : findSeeded(db)) {
            db.excerptDao().deleteByBook(b.id);
            db.borrowRecordDao().deleteByBook(b.id);
            db.bookDao().deleteById(b.id);
        }
        File coverDir = new File(ctx.getFilesDir(), "covers");
        File[] left = coverDir.listFiles((d, name) -> name.startsWith("seed_cover_"));
        if (left != null) {
            for (File f : left) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        assertTrue("样本没删干净", findSeeded(db).isEmpty());
    }

    private List<Book> findSeeded(AppDatabase db) {
        List<Book> out = new ArrayList<>();
        for (Book b : db.bookDao().getAll()) {
            if (b.title != null && b.title.startsWith(TITLE_PREFIX)) {
                out.add(b);
            }
        }
        return out;
    }

    /** 一张能看出圆角的封面：纯色底 + 白边，四角与背景对比明显 */
    private void writeCover(File f, int i) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.rgb(30 + i * 18 % 200, 60 + i * 27 % 180, 120 - i * 9 % 90));
        c.drawRect(0, 0, 300, 450, p);
        p.setColor(Color.WHITE);
        p.setTextSize(46);
        c.drawText("样本 " + (i + 1), 24, 90, p);
        p.setColor(Color.argb(120, 255, 255, 255));
        c.drawRect(0, 0, 300, 450, p);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
        }
        bmp.recycle();
    }
}
