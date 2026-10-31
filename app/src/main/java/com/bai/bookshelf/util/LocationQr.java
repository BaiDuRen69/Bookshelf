package com.bai.bookshelf.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 地点二维码（v5.8.18）：把「哪个书架」编成一张能打印贴上去的码。
 *
 * <p>载荷是自定义 scheme {@code bookshelf://shelf?location=12}——小书房自己的扫码页认出它
 * 就直接跳书架并按该地点筛选，盘点时逐架扫过去就是「按架顺序走」。
 * 编码用 zxing core（纯 Java、离线生成），解码复用已有的 ML Kit。
 */
public final class LocationQr {

    public static final String SCHEME = "bookshelf";
    public static final String HOST = "shelf";
    public static final String PARAM_LOCATION = "location";

    private LocationQr() {
    }

    /** 一个地点的二维码载荷 */
    public static String payload(long locationId) {
        return SCHEME + "://" + HOST + "?" + PARAM_LOCATION + "=" + locationId;
    }

    /** 从扫码文本里取地点 id；不是本 App 的码返回 -1（不认识的码继续按 ISBN 处理） */
    public static long parse(String raw) {
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (!value.startsWith(SCHEME + "://")) {
            return -1;
        }
        try {
            Uri uri = Uri.parse(value);
            if (!HOST.equals(uri.getHost())) {
                return -1;
            }
            String id = uri.getQueryParameter(PARAM_LOCATION);
            return id == null ? -1 : Long.parseLong(id.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 纯二维码图；编码失败返回 null（载荷为空或 zxing 抛异常） */
    public static Bitmap render(String payload, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            int[] row = new int[sizePx];
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    row[x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
                bitmap.setPixels(row, 0, sizePx, 0, y, sizePx, 1);
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 贴纸版：白底 + 二维码 + 地点名，打印或截图贴书架时不用猜是哪一排。
     */
    public static Bitmap renderSticker(String payload, String label, int qrPx) {
        Bitmap qr = render(payload, qrPx);
        if (qr == null) {
            return null;
        }
        float density = qrPx / 240f; // 以 240px 为 1x 基准，字随码一起放大
        int pad = Math.round(28 * density);
        int gap = Math.round(18 * density);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(34 * density);
        text.setColor(Color.BLACK);
        Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hint.setTextSize(22 * density);
        hint.setColor(Color.rgb(110, 110, 110));
        String name = label == null || label.trim().isEmpty() ? "未命名地点" : label.trim();
        int width = qrPx + pad * 2;
        int height = pad + qrPx + gap + Math.round(text.getTextSize())
                + Math.round(6 * density) + Math.round(hint.getTextSize()) + pad;
        Bitmap sticker = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sticker);
        c.drawColor(Color.WHITE);
        c.drawBitmap(qr, pad, pad, null);
        c.drawText(name, (width - text.measureText(name)) / 2f,
                pad + qrPx + gap + text.getTextSize(), text);
        String tip = "小书房 · 扫码筛这个地点";
        c.drawText(tip, (width - hint.measureText(tip)) / 2f,
                pad + qrPx + gap + text.getTextSize() + Math.round(6 * density)
                        + hint.getTextSize(), hint);
        return sticker;
    }
}
