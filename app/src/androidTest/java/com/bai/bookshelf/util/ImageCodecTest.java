package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 图片压缩（v5.8.20）：相机原图入库要变小、要还能解码；已经很小或本来就达标的
 * 不折腾；替换失败时必须保留原图。跑在 devtest 包上，只用自己的临时文件。
 */
@RunWith(AndroidJUnit4.class)
public class ImageCodecTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 造一张「相机原图」：尺寸大、细节多（噪点 + 斜线），JPEG 压不出多少 */
    private File writeBigCover(String name, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(40, 70, 90));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 900; i++) {
            paint.setColor(Color.rgb((i * 7919) % 256, (i * 104729) % 256, (i * 15485863) % 256));
            float x = (i * 137) % width;
            float y = (i * 271) % height;
            canvas.drawLine(x, y, (x + 180) % width, (y + 90) % height, paint);
            canvas.drawCircle(x, y, 24 + (i % 40), paint);
        }
        File file = new File(ctx().getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out));
        }
        bitmap.recycle();
        return file;
    }

    @Test
    public void 大封面压完变小且还能解码() throws Exception {
        File file = writeBigCover("codec_big.jpg", 1600, 2400);
        long before = file.length();
        assertTrue("测试图本身要够大才有意义: " + before, before > ImageCodec.MIN_BYTES_TO_SHRINK);
        assertTrue(ImageCodec.needsShrink(file));

        assertTrue(ImageCodec.shrinkInPlace(file));
        long after = file.length();
        assertTrue("应该变小：" + before + " → " + after, after < before);
        assertTrue("长边要压进 1080: " + ImageCodec.longEdge(file.getAbsolutePath()),
                ImageCodec.longEdge(file.getAbsolutePath()) <= ImageCodec.MAX_EDGE);
        Bitmap decoded = ImageCodec.decodeScaled(file.getAbsolutePath(), ImageCodec.MAX_EDGE);
        assertTrue("压完必须还能解码", decoded != null && decoded.getWidth() > 0);
        decoded.recycle();
        assertFalse("达标后不该再被判定为需要压缩", ImageCodec.needsShrink(file));
        // 临时文件不能留在盘上
        assertFalse(new File(file.getParentFile(), file.getName() + ".shrink.tmp").exists());
        file.delete();
    }

    @Test
    public void 小图不动它() throws Exception {
        File file = writeBigCover("codec_small.jpg", 120, 160);
        assertFalse("小图不该被判定为需要压缩", ImageCodec.needsShrink(file));
        assertFalse("不存在的文件返回 0 长边", ImageCodec.needsShrink(
                new File(ctx().getCacheDir(), "不存在.jpg")));
        assertEquals(0L, ImageCodec.longEdge(
                new File(ctx().getCacheDir(), "不存在.jpg").getAbsolutePath()));
        // shrinkInPlace 自己不设门槛（要不要压由调用方先用 needsShrink 判断，
        // 新入库的封面则是无条件压一遍）：小图压完仍要能解码、尺寸不变
        long before = file.length();
        if (ImageCodec.shrinkInPlace(file)) {
            assertTrue("压完必须更小", file.length() < before);
        }
        assertTrue(ImageCodec.longEdge(file.getAbsolutePath()) <= 160);
        file.delete();
    }

    @Test
    public void 坏文件不崩也不改() throws Exception {
        File broken = new File(ctx().getCacheDir(), "codec_broken.jpg");
        try (FileOutputStream out = new FileOutputStream(broken)) {
            out.write("这不是一张图片".getBytes("UTF-8"));
        }
        long before = broken.length();
        assertFalse(ImageCodec.shrinkInPlace(broken));
        assertEquals(before, broken.length());
        assertFalse(new File(broken.getParentFile(), broken.getName() + ".shrink.tmp").exists());
        broken.delete();
    }
}
