package com.bai.bookshelf.ui.location;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.LocationQr;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 地点二维码弹窗（v5.8.18）：看码 / 分享码 / 直接进「按这个地点盘点」。
 */
public final class LocationQrDialog {

    /** 贴纸上的码边长（px）：720 打印出来够清晰，也不会把分享图撑太大 */
    private static final int QR_PX = 720;

    private LocationQrDialog() {
    }

    public static void show(Activity activity, Location location) {
        Bitmap sticker = LocationQr.renderSticker(
                LocationQr.payload(location.id), location.name, QR_PX);
        if (sticker == null) {
            Toast.makeText(activity, "二维码生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        ImageView image = new ImageView(activity);
        image.setImageBitmap(sticker);
        image.setAdjustViewBounds(true);
        image.setPadding((int) (16 * density), (int) (8 * density),
                (int) (16 * density), (int) (8 * density));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(image);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(location.name + " 的二维码")
                .setView(scroll)
                .setPositiveButton("分享这张码", (d, w) -> share(activity, sticker, location))
                .setNeutralButton("按这个地点盘点", (d, w) -> {
                    Intent intent = new Intent(activity, ScanActivity.class);
                    intent.putExtra(ScanActivity.EXTRA_INVENTORY, true);
                    intent.putExtra(ScanActivity.EXTRA_LOCATION_ID, location.id);
                    activity.startActivity(intent);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void share(Activity activity, Bitmap sticker, Location location) {
        try {
            File dir = new File(activity.getFilesDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, "qr_" + location.id + "_"
                    + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                sticker.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT,
                    "「" + location.name + "」的书架二维码 —— 小书房扫码可直接筛到这个地点");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "分享书架二维码"));
        } catch (Exception e) {
            Toast.makeText(activity, "分享失败：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
