package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 把相册/拍照得到的封面复制进 filesDir/covers/（v4.51 拍照识字用） */
public final class CoverFiles {

    private CoverFiles() {
    }

    public static String copyToCovers(Context ctx, Uri uri) throws Exception {
        File dir = new File(ctx.getFilesDir(), "covers");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建封面目录");
        }
        File out = new File(dir, "cover_ocr_" + System.currentTimeMillis() + ".jpg");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) {
                throw new java.io.IOException("无法读取这张图片");
            }
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        return out.getAbsolutePath();
    }
}
