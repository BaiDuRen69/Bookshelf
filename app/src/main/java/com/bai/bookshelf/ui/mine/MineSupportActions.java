package com.bai.bookshelf.ui.mine;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.DialogRewardQrBinding;
import com.bai.bookshelf.databinding.DialogSupportBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「我的」页的反馈与支持入口：邮件反馈、GitHub Issues / 主页、打赏弹窗与收款码保存相册。
 * 存储权限只在 API 28 及以下保存图片时申请。
 */
public class MineSupportActions {

    /** 反馈邮箱与 GitHub Issues 提交页（与 README「反馈」一节保持一致，改动需同步两处） */
    private static final String FEEDBACK_EMAIL = "baiduren69@outlook.com";
    private static final String FEEDBACK_ISSUES_URL = "https://github.com/BaiDuRen69/Bookshelf/issues/new";
    /** 项目主页（v4.15：「我的 GitHub」行跳转） */
    private static final String GITHUB_REPO_URL = "https://github.com/BaiDuRen69/Bookshelf";

    private final Fragment fragment;
    private final androidx.activity.result.ActivityResultLauncher<String> writePermLauncher;
    private int pendingSaveResId;
    private String pendingSaveName;

    public MineSupportActions(Fragment fragment) {
        this.fragment = fragment;
        this.writePermLauncher = fragment.registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        doSaveImageToGallery(pendingSaveResId, pendingSaveName);
                    } else {
                        Toast.makeText(fragment.requireContext(), "未获得存储权限，无法保存图片",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 拉起系统邮件应用（主题自动带上版本号）；无邮件应用时降级为复制邮箱 */
    public void sendFeedbackEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + FEEDBACK_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT, "小书房 App 反馈 v" + BuildConfig.VERSION_NAME);
        try {
            fragment.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(fragment.requireContext(), "未找到邮件应用，已改为复制邮箱地址",
                    Toast.LENGTH_LONG).show();
            copyToClipboard(FEEDBACK_EMAIL, "已复制邮箱地址");
        }
    }

    /** 浏览器打开 GitHub Issues 页；无浏览器时提示手动访问并复制链接 */
    public void openGitHubIssues() {
        openInBrowser(FEEDBACK_ISSUES_URL);
    }

    /** 浏览器打开项目 GitHub 主页（v4.15「我的 GitHub」行） */
    public void openGitHubRepo() {
        openInBrowser(GITHUB_REPO_URL);
    }

    /** 复制文本到剪贴板并提示 */
    public void copyToClipboard(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) fragment.requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("bookshelf_feedback", text));
        }
        Toast.makeText(fragment.requireContext(), toast, Toast.LENGTH_SHORT).show();
    }

    /** 「支持作者」弹窗：支付宝 / 微信 两个入口 */
    public void showSupportDialog() {
        DialogSupportBinding db = DialogSupportBinding.inflate(fragment.getLayoutInflater());
        AlertDialog dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        db.rowAlipay.setOnClickListener(v -> {
            dialog.dismiss();
            showRewardQrDialog(R.drawable.alipay_qr, "支付宝");
        });
        db.rowWechat.setOnClickListener(v -> {
            dialog.dismiss();
            showRewardQrDialog(R.drawable.wechat_qr, "微信");
        });
    }

    /** 收款码大图弹窗：展示图片 + 「保存」按钮（保存到相册） */
    private void showRewardQrDialog(int imgRes, String name) {
        DialogRewardQrBinding db = DialogRewardQrBinding.inflate(fragment.getLayoutInflater());
        Glide.with(fragment).load(imgRes).into(db.ivQr);
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(name + "收款码")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        db.btnSave.setOnClickListener(v -> saveImageToGallery(imgRes, name));
    }

    /** 保存收款码到相册（Pictures/小书房）；API 28 及以下先申请存储权限 */
    private void saveImageToGallery(int imgRes, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(fragment.requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSaveResId = imgRes;
            pendingSaveName = name;
            writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        doSaveImageToGallery(imgRes, name);
    }

    /** 通过 MediaStore 把收款码写入相册 */
    private void doSaveImageToGallery(int imgRes, String name) {
        Context appCtx = fragment.requireContext().getApplicationContext();
        try {
            Bitmap bmp = BitmapFactory.decodeResource(fragment.getResources(), imgRes);
            String fileName = "小书房打赏_" + name + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date()) + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            ContentResolver cr = appCtx.getContentResolver();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/小书房");
            } else {
                File dir = new File(Environment.getExternalStorageDirectory(), "Pictures/小书房");
                if (!dir.exists() && !dir.mkdirs()) {
                    Toast.makeText(appCtx, "保存失败：无法创建目录", Toast.LENGTH_LONG).show();
                    return;
                }
                values.put(MediaStore.Images.Media.DATA, new File(dir, fileName).getAbsolutePath());
            }
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Toast.makeText(appCtx, "保存失败：相册不可用", Toast.LENGTH_LONG).show();
                return;
            }
            try (OutputStream os = cr.openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            Toast.makeText(appCtx, "已保存到相册：Pictures/小书房", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(appCtx, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 浏览器打开链接；无浏览器时提示手动访问并复制链接 */
    private void openInBrowser(String url) {
        try {
            fragment.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(fragment.requireContext(),
                    "未找到浏览器，请手动访问（链接已复制）：\n" + url,
                    Toast.LENGTH_LONG).show();
            copyToClipboard(url, "已复制链接");
        }
    }
}
