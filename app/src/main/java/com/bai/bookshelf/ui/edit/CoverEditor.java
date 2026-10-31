package com.bai.bookshelf.ui.edit;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.ui.widget.BookCoverDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 编辑页封面处理（v5.7，从 BookEditActivity 抽出）：
 * 拍照 / 相册选图（复制进私有目录）/ 链接与本地路径预览、无封面布面首字占位。
 */
public final class CoverEditor {

    private final AppCompatActivity activity;
    private final ActivityBookEditBinding b;
    private final ActivityResultLauncher<Uri> takePictureLauncher;
    private final ActivityResultLauncher<String> pickImageLauncher;
    private File pendingPhotoFile;

    public CoverEditor(AppCompatActivity activity, ActivityBookEditBinding b) {
        this.activity = activity;
        this.b = b;
        takePictureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(), success -> {
                    if (success && pendingPhotoFile != null) {
                        b.etCoverUrl.setText(pendingPhotoFile.getAbsolutePath());
                        load();
                    }
                });
        pickImageLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        copyToLocal(uri);
                    }
                });
    }

    /** 拍照：先创建输出文件，再经 FileProvider 调起相机（此契约无需 CAMERA 权限） */
    public void takePhoto() {
        File dir = new File(activity.getFilesDir(), "covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        pendingPhotoFile = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", pendingPhotoFile);
        takePictureLauncher.launch(uri);
    }

    /** 相册选图 */
    public void pickImage() {
        pickImageLauncher.launch("image/*");
    }

    /** 加载封面：支持 http(s) 链接与本地文件路径；无封面时用布面首字封面 */
    public void load() {
        String url = b.etCoverUrl.getText().toString().trim();
        String title = b.etTitle.getText().toString().trim();
        Drawable placeholder = new BookCoverDrawable(activity, title.isEmpty() ? "书" : title);
        if (url.isEmpty()) {
            Glide.with(activity).clear(b.ivCover);
            b.ivCover.setImageDrawable(placeholder);
            return;
        }
        Glide.with(activity)
                .load(url.startsWith("http") ? url : new File(url))
                .placeholder(placeholder)
                .error(placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .into(b.ivCover);
    }

    /** 相册选图后复制到应用私有目录（原图 Uri 权限可能随时间失效） */
    private void copyToLocal(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                File dir = new File(activity.getFilesDir(), "covers");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File out = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                // v5.8.20：入库即压缩到长边 1080 / JPEG 85。相机原图 3–5MB 一张，
                // 不压的话备份包、Glide 解码、Excel 导出都要跟着变重。
                // 压不动（解码失败或反而更大）就原样留着，绝不因为压缩丢封面。
                com.bai.bookshelf.util.ImageCodec.shrinkInPlace(out);
                app.ui(() -> {
                    b.etCoverUrl.setText(out.getAbsolutePath());
                    load();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(activity, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}
