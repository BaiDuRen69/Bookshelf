package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 摘抄图片落盘（filesDir/excerpt_images/）。必须在后台线程调用。 */
public final class ExcerptFiles {

    public static final String DIR = "excerpt_images";

    private ExcerptFiles() {
    }

    /** 从相册 Uri 复制到私有目录，返回绝对路径；失败抛 IOException 由调用方提示 */
    public static String copyToFilesDir(Context ctx, Uri uri) throws Exception {
        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建摘抄图片目录");
        }
        File out = new File(dir, "excerpt_" + System.currentTimeMillis() + ".jpg");
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

    /** 只删摘抄目录内的文件，防止误删用户其它图片 */
    public static void deleteQuietly(Context ctx, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            File f = new File(path);
            if (f.exists()
                    && f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
