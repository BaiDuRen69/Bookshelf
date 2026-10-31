package com.bai.bookshelf.ui.mine;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.BookOcr;
import com.bai.bookshelf.util.CoverFiles;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 「我的」页的拍照识字入口（v4.51）：拍照或从相册选封面 → OCR 识别书名/作者/出版社 →
 * 预填到编辑页人工确认。相机用 TakePicture 契约，不需要 CAMERA 权限。
 */
public class MineOcrImport {

    private final Fragment fragment;
    private File pendingPhoto;
    private AlertDialog progress;

    private final ActivityResultLauncher<Uri> takeLauncher;
    private final ActivityResultLauncher<String> pickLauncher;

    public MineOcrImport(Fragment fragment) {
        this.fragment = fragment;
        this.takeLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    if (ok && pendingPhoto != null) {
                        runOcr(pendingPhoto.getAbsolutePath());
                    } else if (pendingPhoto != null) {
                        pendingPhoto.delete();
                    }
                });
        this.pickLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null && fragment.isAdded()) {
                        copyImage(uri);
                    }
                });
    }

    /** 入口：选择拍照或相册 */
    public void showSourceDialog() {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("拍照识字")
                .setItems(new String[]{"拍封面 / 扉页", "从相册选择"}, (d, which) -> {
                    if (which == 0) {
                        takePhoto();
                    } else {
                        pickLauncher.launch("image/*");
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 直接调系统相机（TakePicture 契约不需要 CAMERA 权限） */
    private void takePhoto() {
        File dir = new File(fragment.requireContext().getFilesDir(), "covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        pendingPhoto = new File(dir, "cover_ocr_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(fragment.requireContext(),
                fragment.requireContext().getPackageName() + ".fileprovider", pendingPhoto);
        takeLauncher.launch(uri);
    }

    private void copyImage(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                String path = CoverFiles.copyToCovers(fragment.requireContext(), uri);
                app.ui(() -> {
                    if (fragment.isAdded()) {
                        runOcr(path);
                    }
                });
            } catch (Exception e) {
                app.ui(() -> {
                    if (fragment.isAdded()) {
                        Toast.makeText(fragment.requireContext(), "图片读取失败：" + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void runOcr(String coverPath) {
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
        }
        android.widget.ProgressBar bar = new android.widget.ProgressBar(fragment.requireContext());
        int pad = Math.round(24 * fragment.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        progress = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("正在识别封面文字…")
                .setView(bar)
                .setCancelable(false)
                .create();
        progress.show();

        BookOcr.recognize(fragment.requireContext(),
                Uri.fromFile(new File(coverPath)),
                new BookOcr.Callback() {
                    @Override
                    public void onDone(BookOcr.Result result) {
                        if (progress != null && progress.isShowing()) {
                            progress.dismiss();
                        }
                        if (!fragment.isAdded()) {
                            return;
                        }
                        openEditor(coverPath, result);
                    }

                    @Override
                    public void onFailed(String message) {
                        if (progress != null && progress.isShowing()) {
                            progress.dismiss();
                        }
                        if (!fragment.isAdded()) {
                            return;
                        }
                        openEditor(coverPath, new BookOcr.Result());
                        Toast.makeText(fragment.requireContext(),
                                "识别失败，已把照片设为封面，请手动填写书名",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** 预填书名/作者/出版社 + 封面，交给编辑页人工确认 */
    private void openEditor(String coverPath, BookOcr.Result result) {
        Book prefill = new Book();
        prefill.title = result.title;
        prefill.author = result.author;
        prefill.publisher = result.publisher;
        prefill.coverUrl = coverPath;
        Intent intent = new Intent(fragment.requireContext(), BookEditActivity.class);
        intent.putExtra(BookEditActivity.EXTRA_PREFILL, prefill);
        fragment.startActivity(intent);
        Toast.makeText(fragment.requireContext(),
                result.title.isEmpty()
                        ? "没认出书名，已把照片设为封面，请手动填写"
                        : "已识别：" + result.title + "（请确认后保存）",
                Toast.LENGTH_LONG).show();
    }
}
