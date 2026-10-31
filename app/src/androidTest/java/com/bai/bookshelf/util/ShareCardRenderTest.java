package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/** 分享卡离屏渲染（v5.8.14）：封面主色应进入品牌/状态/标签，而不是固定墨绿 */
@RunWith(AndroidJUnit4.class)
public class ShareCardRenderTest {

    @Test
    public void 分享卡采用封面主色() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Bitmap cover = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(cover);
        c.drawColor(0xFFC0392B); // 红封面
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        c.drawRect(40, 60, 360, 120, white);

        Book book = new Book();
        book.title = "分享卡测试之书";
        book.author = "测试作者";
        book.tags = "小说,历史";
        book.review = "这是一段用于分享卡渲染验证的短评。";
        book.publisher = "测试出版社";
        book.publishDate = "2024-01";
        book.pages = 320;
        book.isbn = "9781111111111";

        Bitmap card = BookShareCard.render(book, cover);
        assertEquals(BookShareCard.WIDTH, card.getWidth());
        assertEquals(BookShareCard.HEIGHT, card.getHeight());
        // 品牌字应该偏红而不是墨绿：随机抽查左上角区域，找到高饱和的红色像素
        int red = 0;
        for (int y = 70; y < 130; y += 2) {
            for (int x = 80; x < 400; x += 2) {
                int px = card.getPixel(x, y);
                if (((px >> 16) & 0xFF) > 120 && ((px >> 16) & 0xFF) > ((px >> 8) & 0xFF) + 30) {
                    red++;
                }
            }
        }
        assertTrue("品牌字应带封面红色调", red > 10);

        // 封面本身不能是半透明（旧版 paint alpha 0x33 会把红封面画成粉色）
        int coverPixel = card.getPixel(200, 300);
        assertTrue("封面应保持饱和红色：" + Integer.toHexString(coverPixel),
                ((coverPixel >> 16) & 0xFF) > 150
                        && ((coverPixel >> 16) & 0xFF) > ((coverPixel >> 8) & 0xFF) + 40);

        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getCacheDir(), "share_card.png"))) {
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
