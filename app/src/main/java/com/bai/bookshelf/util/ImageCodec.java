package com.bai.bookshelf.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 图片压缩（v5.8.20）：相机原图 3–5MB 一张，直接塞进 filesDir 会让备份包、Glide 解码
 * 和 Excel 导出一起变重。入库统一压到长边 1080 / JPEG 85，够用且单张约 100–250KB。
 *
 * <p>替换是「先写临时文件 → 确认能重新解码且确实更小 → 才覆盖原文件」，
 * 任何一步失败都保留原图，不会出现压坏又回不去的情况。
 */
public final class ImageCodec {

    /** 长边像素上限 */
    public static final int MAX_EDGE = 1080;
    /** JPEG 质量 */
    public static final int QUALITY = 85;
    /** 小于这个字节数不值得再解一次盘（省不了多少） */
    public static final int MIN_BYTES_TO_SHRINK = 256 * 1024;

    private ImageCodec() {
    }

    /** 是否需要压：文件够大，或者长边超标 */
    public static boolean needsShrink(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        if (file.length() >= MIN_BYTES_TO_SHRINK) {
            return true;
        }
        return longEdge(file.getAbsolutePath()) > MAX_EDGE;
    }

    /**
     * 原地压缩一张图。
     *
     * @return true 表示确实换成了更小的新文件
     */
    public static boolean shrinkInPlace(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        long originalBytes = file.length();
        Bitmap scaled = decodeScaled(file.getAbsolutePath(), MAX_EDGE);
        if (scaled == null) {
            return false;
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".shrink.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                return false;
            }
            out.flush();
        } catch (IOException | OutOfMemoryError e) {
            tmp.delete();
            scaled.recycle();
            return false;
        }
        scaled.recycle();
        long newBytes = tmp.length();
        // 新文件必须真的更小，而且能重新解码（否则宁可留原图）
        if (newBytes <= 0 || newBytes >= originalBytes
                || decodeScaled(tmp.getAbsolutePath(), MAX_EDGE) == null) {
            tmp.delete();
            return false;
        }
        if (file.delete()) {
            return tmp.renameTo(file) || copyOver(tmp, file);
        }
        boolean copied = copyOver(tmp, file);
        if (copied) {
            tmp.delete();
        }
        return copied;
    }

    /** 图片长边（解不出返回 0） */
    public static long longEdge(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(path, bounds);
        } catch (Exception e) {
            return 0;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return 0;
        }
        return Math.max(bounds.outWidth, bounds.outHeight);
    }

    /** 按 2 的幂采样解码，再精确缩到 maxEdge 以内 */
    public static Bitmap decodeScaled(String path, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > maxEdge * 2L) {
                sample *= 2;
            }
            options.inSampleSize = Math.max(1, sample);
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                return null;
            }
            int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (edge <= maxEdge) {
                return bitmap;
            }
            float ratio = maxEdge / (float) edge;
            Matrix matrix = new Matrix();
            matrix.postScale(ratio, ratio);
            Bitmap resized = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (resized != bitmap) {
                bitmap.recycle();
            }
            return resized;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static boolean copyOver(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
