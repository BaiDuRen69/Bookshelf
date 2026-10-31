package com.bai.bookshelf.ui.excerpt;

import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.databinding.DialogExcerptBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.ExcerptFiles;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 摘抄编辑弹窗（v5.6 从 ExcerptListActivity 抽出）：
 * 页码/原文/批注/标签 + 配图选择；取消或换图时回收未保存的图片文件。
 */
public class ExcerptEditDialog {

    public interface Host {
        long bookId();

        /** 保存成功后刷新列表 */
        void onSaved();

        /** 弹窗里的「分享」 */
        void onShare(Excerpt excerpt);
    }

    private final BaseActivity activity;
    private final Host host;

    private DialogExcerptBinding dialog;
    private boolean saved;
    private boolean dialogActive;
    private long dialogSession;
    private long pickSession;

    /** 编辑中的摘抄原有图片（取消/换图时清文件用） */
    private String originalImagePath = "";
    /** 弹窗里当前选中的图片（新复制进 files 的） */
    private String pendingImagePath = "";

    private final ActivityResultLauncher<String> picker;

    public ExcerptEditDialog(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.picker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        copyPickedImage(uri);
                    }
                });
    }

    public void show(Excerpt existing) {
        dialog = DialogExcerptBinding.inflate(activity.getLayoutInflater());
        saved = false;
        dialogActive = true;
        dialogSession++;
        originalImagePath = existing == null ? "" : existing.imagePath;
        pendingImagePath = originalImagePath == null ? "" : originalImagePath;

        if (existing != null) {
            if (existing.page > 0) {
                dialog.etExcerptPage.setText(String.valueOf(existing.page));
            }
            dialog.etExcerptContent.setText(existing.content);
            dialog.etExcerptNote.setText(existing.note);
            dialog.etExcerptTags.setText(existing.tags);
        }
        dialog.btnPickImage.setOnClickListener(v -> {
            pickSession = dialogSession;
            picker.launch("image/*");
        });
        dialog.btnRemoveImage.setOnClickListener(v -> {
            pendingImagePath = "";
            renderImage();
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(existing == null ? "添加摘抄" : "编辑摘抄")
                .setView(dialog.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (existing != null) {
            builder.setNeutralButton("分享", (d, w) -> host.onShare(existing));
        }
        AlertDialog alert = builder.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (save(existing, alert)) {
                        alert.dismiss();
                    }
                });
        alert.setOnDismissListener(d -> {
            dialogActive = false;
            // 取消/返回：回收这次新复制但没保存的图片
            if (!saved && !pendingImagePath.equals(originalImagePath)) {
                ExcerptFiles.deleteQuietly(activity, pendingImagePath);
            }
        });
        renderImage();
    }

    private void renderImage() {
        boolean has = !pendingImagePath.isEmpty();
        dialog.ivExcerptImage.setVisibility(has ? View.VISIBLE : View.GONE);
        dialog.btnRemoveImage.setVisibility(has ? View.VISIBLE : View.GONE);
        dialog.btnPickImage.setText(has ? "更换图片" : "添加图片");
        if (has) {
            Glide.with(activity).load(new File(pendingImagePath))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(dialog.ivExcerptImage);
        }
    }

    private void copyPickedImage(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                String path = ExcerptFiles.copyToFilesDir(activity, uri);
                app.ui(() -> {
                    if (dialog == null || !dialogActive || pickSession != dialogSession) {
                        ExcerptFiles.deleteQuietly(activity, path);
                        return;
                    }
                    // 换图时先把上一张未保存的新图删掉
                    if (!pendingImagePath.equals(originalImagePath)) {
                        ExcerptFiles.deleteQuietly(activity, pendingImagePath);
                    }
                    pendingImagePath = path;
                    renderImage();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(activity, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private boolean save(Excerpt existing, AlertDialog alert) {
        if (dialog == null) {
            return false;
        }
        String content = dialog.etExcerptContent.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(activity, "摘抄原文不能为空", Toast.LENGTH_SHORT).show();
            return false;
        }
        int page = 0;
        try {
            page = Integer.parseInt(dialog.etExcerptPage.getText().toString().trim());
        } catch (Exception ignored) {
        }
        final Excerpt e = existing == null ? new Excerpt() : existing;
        e.bookId = host.bookId();
        e.page = Math.max(0, page);
        e.content = content;
        e.note = dialog.etExcerptNote.getText().toString().trim();
        e.tags = dialog.etExcerptTags.getText().toString().trim();
        e.imagePath = pendingImagePath;
        long now = System.currentTimeMillis();
        e.updatedAt = now;
        if (existing == null) {
            e.createdAt = now;
        }
        saved = true;
        final String oldPath = originalImagePath;
        final String newPath = pendingImagePath;
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (existing == null) {
                app.db().excerptDao().insert(e);
            } else {
                app.db().excerptDao().update(e);
            }
            if (!newPath.equals(oldPath)) {
                ExcerptFiles.deleteQuietly(activity, oldPath);
            }
            app.ui(() -> {
                Toast.makeText(activity, "已保存摘抄", Toast.LENGTH_SHORT).show();
                host.onSaved();
            });
        });
        return true;
    }
}
