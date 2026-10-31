package com.bai.bookshelf.util;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 借阅条画布（v5.8.18）：正常 / 逾期两种都要画得出来，文字不许掉出画布外。
 * 离屏渲染存 PNG 到缓存，拉下来目检，不动前台。
 */
@RunWith(AndroidJUnit4.class)
public class LendSlipRenderTest {

    private Book book() {
        Book b = new Book();
        b.id = 1;
        b.title = "一日三秋";
        b.author = "刘震云";
        b.isbn = "9787531346326";
        b.stockStatus = Book.STOCK_LENT;
        b.coverUrl = "";
        return b;
    }

    /** 造一张纯色封面，验证主色取自封面而不是兜底 */
    private Bitmap redCover() {
        Bitmap bitmap = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(180, 40, 45));
        return bitmap;
    }

    /** BookShareCard 的纸色底色（掩掉 alpha 才能和 getPixel 的结果比） */
    private static final int PAPER = 0xFFF6F0E4 & 0xFFFFFF;

    private int nonBlankColumns(Bitmap bitmap) {
        int cols = 0;
        for (int x = 0; x < bitmap.getWidth(); x += 12) {
            boolean inked = false;
            for (int y = 0; y < bitmap.getHeight(); y += 12) {
                if ((bitmap.getPixel(x, y) & 0xFFFFFF) != PAPER) {
                    inked = true;
                    break;
                }
            }
            if (inked) {
                cols++;
            }
        }
        return cols;
    }

    @Test
    public void 借阅条正常与逾期都能画出内容() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Book book = book();

        Bitmap normal = BookShareCard.renderLendSlip(book, redCover(), "张三", "2026-11-20", 0);
        Bitmap overdue = BookShareCard.renderLendSlip(book, null, "李四", "2026-10-01", 30);

        assertTrue(normal.getWidth() == BookShareCard.WIDTH
                && normal.getHeight() == BookShareCard.SLIP_HEIGHT);
        // 有内容的列数要够多：画空了或只画了一条线都会露馅
        assertTrue("正常条画得太少: " + nonBlankColumns(normal),
                nonBlankColumns(normal) > 40);
        assertTrue("逾期条画得太少: " + nonBlankColumns(overdue),
                nonBlankColumns(overdue) > 40);

        // 最底一行仍应是纸色：内容溢出画布时这里会被字压到
        boolean edgeInked = false;
        int bottomY = BookShareCard.SLIP_HEIGHT - 1;
        for (int x = 0; x < BookShareCard.WIDTH; x += 8) {
            if ((overdue.getPixel(x, bottomY) & 0xFFFFFF) != PAPER) {
                edgeInked = true;
            }
        }
        assertTrue("内容不该顶到画布底边", !edgeInked);

        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getCacheDir(), "lend_slip.png"))) {
            Bitmap both = Bitmap.createBitmap(BookShareCard.WIDTH,
                    BookShareCard.SLIP_HEIGHT * 2 + 40, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(both);
            c.drawColor(Color.rgb(60, 60, 60));
            c.drawBitmap(normal, 0, 0, null);
            c.drawBitmap(overdue, 0, BookShareCard.SLIP_HEIGHT + 40, null);
            both.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
