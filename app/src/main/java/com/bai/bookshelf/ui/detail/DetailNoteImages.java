package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.databinding.DialogImagePreviewBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 详情页备注图片区（v5.4，从 BookDetailActivity 抽出）：
 * 有图才显示入口，默认折叠；展开后按行渲染大图，点图预览。
 */
public final class DetailNoteImages {

    private List<NoteImage> images = new ArrayList<>();
    private boolean expanded;

    public void bind(Activity activity, ActivityBookDetailBinding b, List<NoteImage> list) {
        images = list == null ? new ArrayList<>() : list;
        b.rowNoteImages.setOnClickListener(v -> {
            expanded = !expanded;
            render(activity, b);
        });
        render(activity, b);
    }

    private void render(Activity activity, ActivityBookDetailBinding b) {
        if (images.isEmpty()) {
            expanded = false;
            b.rowNoteImages.setVisibility(android.view.View.GONE);
            b.containerNoteImages.setVisibility(android.view.View.GONE);
            return;
        }
        b.rowNoteImages.setVisibility(android.view.View.VISIBLE);
        b.rowNoteImages.setText(expanded
                ? "备注图片（" + images.size() + " 张）· 收起"
                : "备注图片（" + images.size() + " 张）· 展开");
        b.containerNoteImages.setVisibility(expanded
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (expanded) {
            renderImages(activity, b);
        }
    }

    private void renderImages(Activity activity, ActivityBookDetailBinding b) {
        b.containerNoteImages.removeAllViews();
        int density = Math.round(activity.getResources().getDisplayMetrics().density);
        for (NoteImage img : images) {
            ImageView iv = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 6 * density, 0, 6 * density);
            iv.setLayoutParams(lp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glide.with(activity)
                    .load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> preview(activity, img));
            b.containerNoteImages.addView(iv);
        }
    }

    private void preview(Activity activity, NoteImage img) {
        DialogImagePreviewBinding pb = DialogImagePreviewBinding.inflate(
                activity.getLayoutInflater());
        Glide.with(activity).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .show();
    }
}
