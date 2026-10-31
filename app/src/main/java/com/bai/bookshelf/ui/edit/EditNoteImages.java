package com.bai.bookshelf.ui.edit;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 编辑页的书级备注图片：相册选图、落盘到私有目录、缩略图/预览/移除、脏检查与文件清理。
 * 逐本备注图片复用同一个选图入口，选中后交给 {@link Host#addCopyNoteImage}。
 *
 * <p>列表里的条目不一定已入库（新增模式 bookId 还未落定），保存时由
 * {@link #snapshotForSave()} 统一补 bookId/sort，再整批重写。
 */
public class EditNoteImages {

    /** 宿主提供当前书籍与逐本图片的落点 */
    public interface Host {
        Book book();

        void addCopyNoteImage(NoteImage img, int copyIndex);
    }

    private final ComponentActivity activity;
    private final ActivityBookEditBinding b;
    private final Host host;

    private final List<NoteImage> images = new ArrayList<>();
    private final Set<String> addedPaths = new HashSet<>();   // 本次会话新加的文件
    private final Set<String> removedPaths = new HashSet<>(); // 本次会话移除的旧文件
    private int initialCount = 0; // 脏检查基准：打开页面时的图片张数

    /** 相册选图当前目标：-1=书级备注；>=2=对应 copyIndex 的逐本备注 */
    private int pickTarget = -1;

    private final ActivityResultLauncher<String> picker;

    public EditNoteImages(ComponentActivity activity, ActivityBookEditBinding b, Host host) {
        this.activity = activity;
        this.b = b;
        this.host = host;
        this.picker = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    int target = pickTarget;
                    pickTarget = -1;
                    saveThenAdd(uri, target);
                });
        render();
    }

    /** 编辑模式预载书级备注图片，加载完成后记脏检查基准 */
    public void loadIfEdit(boolean editMode) {
        if (!editMode) {
            initialCount = images.size();
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<NoteImage> list = app.db().noteImageDao()
                    .getByOwnerKey("book:" + host.book().id);
            app.ui(() -> {
                images.addAll(list);
                initialCount = images.size();
                render();
            });
        });
    }

    /** 打开相册给书级备注选图 */
    public void pickForBook() {
        pick(-1);
    }

    /** 打开相册给第 copyIndex 本（>=2）选逐本备注图片 */
    public void pickForCopy(int copyIndex) {
        pick(copyIndex);
    }

    /** 书级备注图片是否有增删 */
    public boolean isDirty() {
        return images.size() != initialCount;
    }

    /** 保存前快照：补 bookId/sort，避免后台线程读 UI 列表 */
    public List<NoteImage> snapshotForSave() {
        List<NoteImage> result = new ArrayList<>(images);
        long bookId = host.book().id;
        for (int i = 0; i < result.size(); i++) {
            NoteImage img = result.get(i);
            img.bookId = bookId;
            img.sort = i;
        }
        return result;
    }

    /** 放弃改动：清掉本次新加但没留在列表里的临时文件 */
    public void cleanupAbandoned() {
        for (String p : addedPaths) {
            if (!contains(images, p)) {
                new File(p).delete();
            }
        }
        addedPaths.clear();
    }

    /** 保存成功：清理未保留的新图与被移除的旧图文件 */
    public void cleanupRemoved() {
        for (String p : addedPaths) {
            if (!contains(images, p)) {
                new File(p).delete();
            }
        }
        for (String p : removedPaths) {
            new File(p).delete();
        }
        addedPaths.clear();
        removedPaths.clear();
    }

    private void pick(int target) {
        pickTarget = target;
        try {
            picker.launch("image/*");
        } catch (Exception e) {
            pickTarget = -1;
            Toast.makeText(activity, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 选中图片后先复制到应用私有目录（原图 Uri 权限会随时间失效），再加入对应列表 */
    private void saveThenAdd(Uri uri, int target) {
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
                    img.bookId = host.book().id;
                    img.path = out.getAbsolutePath();
                    if (target < 0) {
                        img.sort = images.size();
                        images.add(img);
                        addedPaths.add(img.path);
                        render();
                    } else {
                        host.addCopyNoteImage(img, target);
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(activity, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 渲染备注图片缩略图（横向滚动，点缩略图可预览/移除） */
    private void render() {
        b.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * activity.getResources().getDisplayMetrics().density);
        int gap = (int) (8 * activity.getResources().getDisplayMetrics().density);
        for (NoteImage img : images) {
            android.widget.ImageView iv = new android.widget.ImageView(activity);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            Glide.with(activity)
                    .load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showPreview(img));
            b.containerNoteThumbs.addView(iv);
        }
        b.scrollNoteThumbs.setVisibility(images.isEmpty()
                ? android.view.View.GONE : android.view.View.VISIBLE);
        b.btnAddNoteImage.setText(images.isEmpty()
                ? "＋ 添加备注图片" : "＋ 继续添加备注图片（" + images.size() + " 张）");
    }

    /** 点缩略图：大图预览，可直接移除这张 */
    private void showPreview(NoteImage img) {
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(
                        activity.getLayoutInflater());
        Glide.with(activity).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> remove(img))
                .show();
    }

    /** 移除一张备注图片（本次新加的直接记账，旧图的文件在保存时删除） */
    private void remove(NoteImage img) {
        images.remove(img);
        if (!addedPaths.contains(img.path)) {
            removedPaths.add(img.path);
        }
        render();
    }

    private static boolean contains(List<NoteImage> list, String path) {
        for (NoteImage img : list) {
            if (img.path.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
