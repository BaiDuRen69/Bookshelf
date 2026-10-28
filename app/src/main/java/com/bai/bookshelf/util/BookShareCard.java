package com.bai.bookshelf.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.bai.bookshelf.data.entity.Book;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 单本书的分享卡（v4.52）：1080×1440 PNG。
 * 固定暖纸 + 墨绿 + 黄铜配色，不随 App 主题变化，发出去更像一张藏书票。
 */
public final class BookShareCard {

    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1440;

    private static final int BG = 0xFFF6F0E4;
    private static final int INK = 0xFF2B2A26;
    private static final int MUTED = 0xFF7B7365;
    private static final int BRASS = 0xFFB08A3E;
    private static final int GREEN = 0xFF2F5D4A;
    private static final int LINE = 0xFFE3D8C3;

    private BookShareCard() {
    }

    /** 在调用线程直接绘制（很快），cover 可为 null */
    public static Bitmap render(Book book, Bitmap cover) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(BG);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float left = 80f;

        // 顶部：品牌 + 日期
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(28f);
        paint.setLetterSpacing(0.08f);
        paint.setColor(GREEN);
        c.drawText("小书房 · 藏书", left, 110f, paint);
        paint.setLetterSpacing(0f);
        paint.setTextSize(24f);
        paint.setColor(MUTED);
        String date = new SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                .format(new Date());
        c.drawText(date, WIDTH - left - paint.measureText(date), 110f, paint);

        float lineY = 146f;
        paint.setColor(LINE);
        paint.setStrokeWidth(2f);
        c.drawLine(left, lineY, WIDTH - left, lineY, paint);

        // 封面
        float coverX = left;
        float coverY = 190f;
        float coverW = 320f;
        float coverH = 440f;
        drawCover(c, paint, book, cover, coverX, coverY, coverW, coverH);

        // 右列文字
        float textX = 448f;
        float maxW = WIDTH - left - textX;
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(60f);
        paint.setColor(INK);
        paint.setLetterSpacing(0f);
        float titleBottom = drawWrapped(c, book.title, paint, textX, 268f, maxW, 3, 82f);

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(32f);
        paint.setColor(MUTED);
        String author = book.author == null || book.author.isEmpty() ? "佚名" : book.author;
        c.drawText(clip(author, paint, maxW), textX, titleBottom + 30f, paint);

        // 阅读状态（在读 / 已读 / 未读）
        paint.setTextSize(28f);
        paint.setColor(GREEN);
        c.drawText(statusText(book), textX, titleBottom + 92f, paint);

        float metaY = titleBottom + 168f;
        paint.setTextSize(34f);
        paint.setColor(BRASS);
        StringBuilder stars = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            stars.append(i <= book.rating ? "★" : "☆");
        }
        c.drawText(stars.toString(), textX, metaY, paint);
        float starsW = paint.measureText(stars.toString());
        paint.setTextSize(24f);
        paint.setColor(MUTED);
        c.drawText(book.rating > 0 ? "个人评分 " + book.rating + "/5" : "未评分",
                textX + starsW + 24f, metaY, paint);

        // 标签（最多 3 个）
        String tags = tagLine(book.tags);
        if (!tags.isEmpty()) {
            paint.setTextSize(27f);
            paint.setColor(GREEN);
            c.drawText(clip(tags, paint, maxW), textX, metaY + 56f, paint);
        }

        // 出版信息
        paint.setTextSize(25f);
        paint.setColor(MUTED);
        StringBuilder meta = new StringBuilder();
        append(meta, book.publisher);
        append(meta, yearOf(book.publishDate));
        if (book.pages > 0) {
            append(meta, book.pages + " 页");
        }
        if (meta.length() > 0) {
            c.drawText(clip(meta.toString(), paint, maxW), textX, metaY + 108f, paint);
        }

        // 引文块
        float quoteTop = 760f;
        paint.setColor(LINE);
        paint.setStrokeWidth(2f);
        c.drawLine(left, quoteTop - 34f, WIDTH - left, quoteTop - 34f, paint);

        String quote = pickQuote(book);
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(36f);
        float quoteMaxW = WIDTH - left * 2 - 34f;
        float quoteHeight = measureWrappedHeight(quote, paint, quoteMaxW, 5, 58f);
        float quoteY = Math.max(quoteTop + 90f,
                quoteTop + 90f + (300f - quoteHeight) / 2f);

        // 「书 摘」抬头贴着引文
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(24f);
        paint.setLetterSpacing(0.3f);
        paint.setColor(BRASS);
        c.drawText("书  摘", left, quoteY - 48f, paint);
        paint.setLetterSpacing(0f);

        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(36f);
        paint.setColor(INK);
        drawWrapped(c, quote, paint, left + 34f, quoteY, quoteMaxW, 5, 58f);

        // 左侧黄铜竖线
        paint.setColor(BRASS);
        paint.setStrokeWidth(6f);
        c.drawLine(left, quoteY - 40f, left, quoteY - 40f + quoteHeight, paint);

        // 底部信息
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(24f);
        paint.setColor(MUTED);
        if (book.isbn != null && !book.isbn.isEmpty()) {
            c.drawText("ISBN " + book.isbn, left, 1252f, paint);
        }
        c.drawText("数据只保存在这台手机 · 不联网、不上传", left, 1300f, paint);

        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(44f);
        paint.setColor(GREEN);
        String brand = "小书房";
        c.drawText(brand, WIDTH - left - paint.measureText(brand), 1320f, paint);

        return bitmap;
    }

    private static void drawCover(Canvas c, Paint paint, Book book, Bitmap cover,
                                  float x, float y, float w, float h) {
        RectF rect = new RectF(x, y, x + w, y + h);
        Path clip = new Path();
        clip.addRoundRect(rect, 14f, 14f, Path.Direction.CW);

        paint.clearShadowLayer();
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(rect, 14f, 14f, paint);

        c.save();
        c.clipPath(clip);
        if (cover != null) {
            // centerCrop
            float scale = Math.max(w / cover.getWidth(), h / cover.getHeight());
            float dw = cover.getWidth() * scale;
            float dh = cover.getHeight() * scale;
            float dx = x + (w - dw) / 2f;
            float dy = y + (h - dh) / 2f;
            c.drawBitmap(cover, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
        } else {
            paint.setColor(GREEN);
            c.drawRect(rect, paint);
            paint.setColor(0x66FFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            c.drawRect(new RectF(x + 20f, y + 20f, x + w - 20f, y + h - 20f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF6F0E4);
            paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            paint.setTextSize(150f);
            String ch = book.title == null || book.title.isEmpty()
                    ? "书" : book.title.substring(0, 1);
            c.drawText(ch,
                    x + (w - paint.measureText(ch)) / 2f,
                    y + h / 2f + 55f, paint);
        }
        c.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x1A000000);
        paint.setStrokeWidth(2f);
        c.drawRoundRect(rect, 14f, 14f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static String statusText(Book book) {
        if (book.stockStatus == Book.STOCK_LENT) {
            return "借出中";
        }
        if (book.stockStatus == Book.STOCK_LOST) {
            return "已遗失";
        }
        if (book.readStatus == Book.READING) {
            return "在读";
        }
        if (book.readStatus == Book.READ_DONE) {
            return "已读";
        }
        return "未读";
    }

    private static String pickQuote(Book book) {
        if (book.review != null && !book.review.trim().isEmpty()) {
            return "「" + book.review.trim() + "」";
        }
        if (book.note != null && !book.note.trim().isEmpty()) {
            return "「" + book.note.trim() + "」";
        }
        if (book.summary != null && !book.summary.trim().isEmpty()) {
            return book.summary.trim();
        }
        return "「这本书还没有留下短评。」";
    }

    private static String tagLine(String tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = tags.split("[,，]");
        int n = 0;
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append('#').append(t);
            if (++n >= 3) {
                break;
            }
        }
        return sb.toString();
    }

    private static String yearOf(String edition) {
        if (edition == null) {
            return "";
        }
        // 版次格式 yyyy-mm|N 或旧文本，取前 4 位数字
        String digits = edition.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return digits.substring(0, 4);
        }
        return edition;
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(value.trim());
    }

    private static String clip(String text, Paint paint, float maxW) {
        if (paint.measureText(text) <= maxW) {
            return text;
        }
        String ell = "…";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (paint.measureText(text.substring(0, mid) + ell) <= maxW) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ell;
    }

    /** 只量高度，不绘制（用于引文块垂直居中） */
    private static float measureWrappedHeight(String text, Paint paint,
                                              float maxW, int maxLines, float lineH) {
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            return 0f;
        }
        int lines = 0;
        int start = 0;
        while (start < content.length() && lines < maxLines) {
            int end = start + 1;
            while (end <= content.length()) {
                if (paint.measureText(content.substring(start, end)) > maxW) {
                    end--;
                    break;
                }
                if (end == content.length()) {
                    break;
                }
                end++;
            }
            start = Math.max(end, start + 1);
            while (start < content.length() && content.charAt(start) == 10) {
                start++;
            }
            lines++;
        }
        return lines * lineH;
    }


    /** 逐字换行，返回最后一行的 baseline */
    private static float drawWrapped(Canvas c, String text, Paint paint,
                                     float x, float y, float maxW, int maxLines,
                                     float lineH) {
        return drawWrappedMeasured(c, text, paint, x, y, maxW, maxLines, lineH, null);
    }

    private static float drawWrappedMeasured(Canvas c, String text, Paint paint,
                                             float x, float y, float maxW, int maxLines,
                                             float lineH, float[] usedHeight) {
        String content = text == null ? "" : text.replace("\r", "").trim();
        if (content.isEmpty()) {
            if (usedHeight != null) {
                usedHeight[0] = 0f;
            }
            return y;
        }
        int line = 0;
        int start = 0;
        float baseline = y;
        while (start < content.length() && line < maxLines) {
            int end = start + 1;
            while (end <= content.length()) {
                String candidate = content.substring(start, end);
                if (paint.measureText(candidate) > maxW) {
                    end--;
                    break;
                }
                if (end == content.length()) {
                    break;
                }
                end++;
            }
            if (end <= start) {
                end = start + 1;
            }
            boolean lastLine = line == maxLines - 1 && end < content.length();
            String part = content.substring(start, Math.min(end, content.length()));
            if (lastLine) {
                part = clip(part + "…", paint, maxW);
            }
            c.drawText(part, x, baseline, paint);
            start = Math.max(end, start + 1);
            // 跳过换行符
            while (start < content.length() && content.charAt(start) == '\n') {
                start++;
            }
            line++;
            baseline += lineH;
        }
        if (usedHeight != null) {
            usedHeight[0] = line * lineH;
        }
        return baseline;
    }
}
