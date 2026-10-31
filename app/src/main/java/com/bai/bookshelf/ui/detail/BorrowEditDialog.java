package com.bai.bookshelf.ui.detail;

import android.net.Uri;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.DialogBorrowEditBinding;
import com.bai.bookshelf.databinding.DialogImagePreviewBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 借出记录的编辑弹窗：借用人 / 借出日期 / 归还日期（留空=借出中）/ 备注 / 备注图片。
 * 保存后由宿主同步书籍在库状态并刷新列表。
 */
public class BorrowEditDialog {

    public interface Host {
        long bookId();

        /** 保存后同步书籍/副本的在库状态（在 io 线程调用） */
        void syncStock();

        /** 保存完成后的界面刷新（主线程） */
        void onSaved();
    }

    private final BaseActivity activity;
    private final Host host;

    // 弹窗中的备注图片状态
    private final List<NoteImage> images = new ArrayList<>();
    private final Set<String> addedPaths = new HashSet<>();
    private final Set<String> removedPaths = new HashSet<>();
    private DialogBorrowEditBinding binding;
    private AlertDialog dialog; // 保留引用，相册返回时若被系统收起则重新展示
    private final ActivityResultLauncher<String> picker;

    public BorrowEditDialog(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.picker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImagePicked);
    }

    public void show(BorrowRecord record) {
        binding = DialogBorrowEditBinding.inflate(activity.getLayoutInflater());
        DialogBorrowEditBinding db = binding;
        db.etBorrower.setText(record.borrower);
        db.etLendDate.setText(record.lendDate);
        db.etReturnDate.setText(record.returnDate);
        db.etNote.setText(record.note);

        images.clear();
        addedPaths.clear();
        removedPaths.clear();
        loadImages(record.id);

        db.btnAddNoteImage.setOnClickListener(v -> {
            try {
                picker.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开相册：" + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle("编辑借出记录")
                .setView(db.getRoot())
                .setPositiveButton("保存", (dd, w) -> {
                    String borrower = db.etBorrower.getText().toString().trim();
                    if (borrower.isEmpty()) {
                        Toast.makeText(activity, "借用人不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    record.borrower = borrower;
                    record.lendDate = db.etLendDate.getText().toString().trim();
                    record.returnDate = db.etReturnDate.getText().toString().trim();
                    record.note = db.etNote.getText().toString().trim();
                    // 归还日期留空 = 借出中，否则 = 已归还
                    record.status = record.returnDate.isEmpty()
                            ? BorrowRecord.STATUS_OUT : BorrowRecord.STATUS_RETURNED;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().borrowRecordDao().update(record);
                        saveImages(app, record.id);
                        host.syncStock();
                        app.ui(host::onSaved);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog = d;
        d.show();
    }

    private void loadImages(long recordId) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<NoteImage> list = app.db().noteImageDao().getByOwnerKey("borrow:" + recordId);
            app.ui(() -> {
                images.addAll(list);
                if (binding != null) {
                    renderThumbs(binding);
                }
            });
        });
    }

    private void saveImages(BookshelfApp app, long recordId) {
        String ownerKey = "borrow:" + recordId;
        app.db().noteImageDao().deleteByOwnerKey(ownerKey);
        if (!images.isEmpty()) {
            for (int i = 0; i < images.size(); i++) {
                NoteImage img = images.get(i);
                img.bookId = host.bookId();
                img.ownerKey = ownerKey;
                img.sort = i;
            }
            app.db().noteImageDao().insertAll(images);
        }
        // 清理被移除的文件
        for (String p : addedPaths) {
            boolean kept = false;
            for (NoteImage img : images) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        for (String p : removedPaths) {
            new File(p).delete();
        }
        addedPaths.clear();
        removedPaths.clear();
    }

    private void onImagePicked(Uri uri) {
        if (uri == null || binding == null) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                File dir = new File(activity.getFilesDir(), "note_images");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File out = new File(dir, "note_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                app.ui(() -> {
                    NoteImage img = new NoteImage();
                    img.bookId = host.bookId();
                    img.path = out.getAbsolutePath();
                    images.add(img);
                    addedPaths.add(img.path);
                    renderThumbs(binding);
                    // 部分 ROM 在拉起相册时会收起弹窗，返回后重新展示以便用户看到已选缩略图
                    if (dialog != null && !dialog.isShowing()) {
                        dialog.show();
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(activity, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void renderThumbs(DialogBorrowEditBinding db) {
        db.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * activity.getResources().getDisplayMetrics().density);
        int gap = (int) (8 * activity.getResources().getDisplayMetrics().density);
        for (NoteImage img : images) {
            ImageView iv = new ImageView(activity);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(activity).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showPreview(db, img));
            db.containerNoteThumbs.addView(iv);
        }
        boolean has = !images.isEmpty();
        db.scrollNoteThumbs.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        db.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + images.size() + " 张）"
                : "＋ 添加备注图片");
    }

    private void showPreview(DialogBorrowEditBinding db, NoteImage img) {
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
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
                .setNegativeButton("移除", (d, w) -> {
                    images.remove(img);
                    if (!addedPaths.contains(img.path)) {
                        removedPaths.add(img.path);
                    }
                    renderThumbs(db);
                })
                .show();
    }
}
