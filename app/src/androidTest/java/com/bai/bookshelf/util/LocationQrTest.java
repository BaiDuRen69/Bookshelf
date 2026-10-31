package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 地点二维码（v5.8.18）：zxing 画出来的码必须能被 App 自己的扫码器（ML Kit）读回来，
 * 否则贴到书架上就是一张废图。这里做「编码 → 解码 → 解析出地点 id」的闭环。
 */
@RunWith(AndroidJUnit4.class)
public class LocationQrTest {

    /** 数一下黑白模块是否都在（全黑/全白的图说明没画出来） */
    private void assertHasBothColors(Bitmap bitmap) {
        int black = 0;
        int white = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 3) {
            for (int x = 0; x < bitmap.getWidth(); x += 3) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                if (r < 40) {
                    black++;
                } else if (r > 200) {
                    white++;
                }
            }
        }
        assertTrue("码点太少: " + black, black > 200);
        assertTrue("空白太少: " + white, white > 200);
    }

    @Test
    public void 载荷编解码往返() {
        String payload = LocationQr.payload(42);
        assertEquals("bookshelf://shelf?location=42", payload);
        assertEquals(42L, LocationQr.parse(payload));
        assertEquals(-1L, LocationQr.parse("9787111544937"));
        assertEquals(-1L, LocationQr.parse("https://example.com"));
        assertEquals(-1L, LocationQr.parse(null));
        assertEquals(-1L, LocationQr.parse("bookshelf://other?location=42"));
    }

    @Test
    public void 画出的码能被扫码器读回地点() throws Exception {
        Bitmap qr = LocationQr.render(LocationQr.payload(7), 600);
        assertNotNull(qr);
        assertEquals(600, qr.getWidth());
        assertHasBothColors(qr);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> found = new AtomicReference<>("");
        BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                .process(InputImage.fromBitmap(qr, 0))
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String value = barcode.getRawValue();
                        if (value != null && !value.isEmpty()) {
                            found.set(value);
                        }
                    }
                    done.countDown();
                })
                .addOnFailureListener(e -> done.countDown());
        assertTrue("解码超时", done.await(20, TimeUnit.SECONDS));
        assertEquals("bookshelf://shelf?location=7", found.get());
        assertEquals(7L, LocationQr.parse(found.get()));
    }

    @Test
    public void 贴纸版留出地点名位置() {
        Bitmap sticker = LocationQr.renderSticker(
                LocationQr.payload(3), "书房第二层", 480);
        assertNotNull(sticker);
        assertEquals(480 + 2 * Math.round(28 * (480 / 240f)), sticker.getWidth());
        assertTrue("贴纸要比码本身高（下面写了名字）",
                sticker.getHeight() > 480 + 60);
        assertHasBothColors(sticker);
    }
}
