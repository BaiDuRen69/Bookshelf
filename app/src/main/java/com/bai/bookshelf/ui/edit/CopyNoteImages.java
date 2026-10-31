package com.bai.bookshelf.ui.edit;

import android.app.Activity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.DialogImagePreviewBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐本备注图片（v5.6 从 CopyFormBuilder 抽出）：
 * 从库加载、追加新图、缩略图条、预览/移除与脏检查。
 */
public final class CopyNoteImages {

    private CopyNoteImages() {
    }

    /**
     * 编辑模式时从库加载该副本的备注图片。
     * 新增模式 bookId <= 0 时直接返回，避免无效查询。
     */
    public static void loadNoteImagesIfNeeded(Activity act, BookshelfApp app,
                                              long bookId, CopyFormBuilder.Holder h) {
        if (bookId <= 0) {
            h.initialNoteImageCount = h.noteImages.size();
            return;
        }
        // 若当前 holder 已有图片（来自 pendingCopyImages 抢救），以当前会话状态为准，不再覆盖
        if (!h.noteImages.isEmpty()) {
            h.initialNoteImageCount = h.noteImages.size();
            renderNoteThumbs(h);
            return;
        }
        String ownerKey = "copy:" + bookId + ":" + h.copyIndex;
        app.io(() -> {
            List<NoteImage> list = app.db().noteImageDao().getByOwnerKey(ownerKey);
            act.runOnUiThread(() -> {
                h.noteImages.clear();
                h.noteImages.addAll(list);
                h.initialNoteImageCount = list.size();
                renderNoteThumbs(h);
            });
        });
    }

    /** 把一张新选中的图片追加到该副本的备注图片列表（调用方已负责落盘） */
    public static void addNoteImage(Activity act, CopyFormBuilder.Holder h, NoteImage img) {
        h.noteImages.add(img);
        h.addedImagePaths.add(img.path);
        renderNoteThumbs(h);
    }

    /** 渲染备注图片缩略图横向条（点图预览/移除） */
    public static void renderNoteThumbs(CopyFormBuilder.Holder h) {
        if (h.containerNoteThumbs == null) {
            return;
        }
        h.containerNoteThumbs.removeAllViews();
        Activity act = (Activity) h.root.getContext();
        int size = (int) (72 * act.getResources().getDisplayMetrics().density);
        int gap = (int) (8 * act.getResources().getDisplayMetrics().density);
        for (NoteImage img : h.noteImages) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            Glide.with(act).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showPreview(act, h, img));
            h.containerNoteThumbs.addView(iv);
        }
        boolean has = !h.noteImages.isEmpty();
        h.scrollNoteThumbs.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        h.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + h.noteImages.size() + " 张）"
                : "＋ 添加备注图片");
    }

    /** 预览大图并支持移除 */
    private static void showPreview(Activity act, CopyFormBuilder.Holder h, NoteImage img) {
        // v4.18: 图片放进圆角卡片底
        DialogImagePreviewBinding pb = DialogImagePreviewBinding.inflate(act.getLayoutInflater());
        Glide.with(act).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(act)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> remove(h, img))
                .show();
    }

    private static void remove(CopyFormBuilder.Holder h, NoteImage img) {
        h.noteImages.remove(img);
        if (!h.addedImagePaths.contains(img.path)) {
            h.removedImagePaths.add(img.path);
        }
        renderNoteThumbs(h);
    }

    /** 取出当前要保存的图片列表（bookId/sort 由调用方补，ownerKey 由保存时补） */
    public static List<NoteImage> getNoteImages(CopyFormBuilder.Holder h) {
        return new ArrayList<>(h.noteImages);
    }

    /** 该副本的备注图片是否有增删（用于脏检查） */
    public static boolean hasImageChanges(CopyFormBuilder.Holder h) {
        return h.noteImages.size() != h.initialNoteImageCount;
    }
}
