package com.bai.bookshelf.ui.detail;

import androidx.appcompat.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityLocationBinding;
import com.bai.bookshelf.databinding.DialogBorrowEditBinding;
import com.bai.bookshelf.databinding.ItemLocationBinding;
import com.bai.bookshelf.util.StatusText;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 借出记录二级页：某本书的全部借出历史（借出中/已归还），
 * 每条记录可编辑（借用人/日期/备注）与删除。
 * 复用地点管理页的布局与列表项（筛选、底部按钮、星标、勾选框均隐藏）。
 */
public class BorrowHistoryActivity extends BaseActivity {

    public static final String EXTRA_BOOK_ID = "book_id";

    private ActivityLocationBinding b; // 复用地点页布局
    private final List<BorrowRecord> data = new ArrayList<>();
    private RecordAdapter adapter;
    private long bookId;
    private boolean batchMode = false; // 批量操作模式
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    // 借出记录编辑弹窗中的备注图片状态
    private final List<NoteImage> currentEditImages = new ArrayList<>();
    private final java.util.Set<String> currentEditAddedPaths = new java.util.HashSet<>();
    private final java.util.Set<String> currentEditRemovedPaths = new java.util.HashSet<>();
    private DialogBorrowEditBinding currentEditBinding;
    private AlertDialog currentEditDialog; // 保留引用，相册返回时若被系统收起则重新展示
    private ActivityResultLauncher<String> pickEditImageLauncher;

    // 每条借出记录的备注图片张数（用于列表里「〔图N〕」标记，不直接展示图片）
    private java.util.Map<Long, Integer> noteImageCounts = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        pickEditImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::onEditImagePicked);
        b.toolbar.setTitle("借出记录");
        b.toolbar.setNavigationOnClickListener(v -> finish());
        // 本页用不到：大类筛选、底部「添加」按钮
        b.spFilter.setVisibility(View.GONE);
        b.btnAdd.setVisibility(View.GONE);
        b.btnBatch.setText("批量操作");

        bookId = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        if (bookId < 0) {
            finish();
            return;
        }

        adapter = new RecordAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        // 批量操作：进入/取消/全选/归还/删除
        b.btnBatch.setOnClickListener(v -> toggleBatchMode());
        b.btnSelCancel.setOnClickListener(v -> toggleBatchMode());
        b.btnSelAll.setOnClickListener(v -> {
            for (BorrowRecord r : data) {
                selectedIds.add(r.id);
            }
            refreshBatchUI();
        });
        b.btnSelReturn.setOnClickListener(v -> confirmBatchReturn());
        b.btnSelDelete.setOnClickListener(v -> confirmBatchDelete());
    }

    /** 进入/退出批量操作模式 */
    private void toggleBatchMode() {
        batchMode = !batchMode;
        selectedIds.clear();
        refreshBatchUI();
    }

    /** 同步批量模式的界面状态（操作条、底部按钮、列表勾选框） */
    private void refreshBatchUI() {
        b.selectionBar.setVisibility(batchMode ? View.VISIBLE : View.GONE);
        b.bottomBar.setVisibility(batchMode ? View.GONE : View.VISIBLE);
        b.btnSelReturn.setVisibility(batchMode ? View.VISIBLE : View.GONE); // 归还按钮仅本页使用
        b.tvSelCount.setText("已选 " + selectedIds.size() + " 条");
        adapter.notifyDataSetChanged();
    }

    /** 批量归还：选中的借出中记录标记为今日归还 */
    private void confirmBatchReturn() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(this, "请先勾选记录", Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        java.util.List<BorrowRecord> targets = new ArrayList<>();
        for (BorrowRecord r : data) {
            if (selectedIds.contains(r.id) && r.status == BorrowRecord.STATUS_OUT) {
                targets.add(r);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "选中的记录都已归还", Toast.LENGTH_SHORT).show();
            return;
        }
        app.io(() -> {
            for (BorrowRecord r : targets) {
                r.status = BorrowRecord.STATUS_RETURNED;
                r.returnDate = StatusText.today();
                app.db().borrowRecordDao().update(r);
            }
            syncBookStock();
            app.ui(() -> {
                toggleBatchMode();
                Toast.makeText(this, "已归还 " + targets.size() + " 条", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    /** 批量删除（带确认） */
    private void confirmBatchDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(this, "请先勾选要删除的记录", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("批量删除记录")
                .setMessage("删除已选的 " + count + " 条借出记录？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    java.util.Set<Long> ids = new java.util.HashSet<>(selectedIds);
                    app.io(() -> {
                        for (Long id : ids) {
                            String ownerKey = "borrow:" + id;
                            List<NoteImage> imgs = app.db().noteImageDao().getByOwnerKey(ownerKey);
                            app.db().noteImageDao().deleteByOwnerKey(ownerKey);
                            for (NoteImage img : imgs) {
                                new File(img.path).delete();
                            }
                            app.db().borrowRecordDao().deleteById(id);
                        }
                        syncBookStock();
                        app.ui(() -> {
                            toggleBatchMode();
                            Toast.makeText(this, "已删除 " + count + " 条记录", Toast.LENGTH_SHORT).show();
                            reload();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<BorrowRecord> list = app.db().borrowRecordDao().getByBook(bookId);
            java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
            for (BorrowRecord r : list) {
                counts.put(r.id, app.db().noteImageDao().getByOwnerKey("borrow:" + r.id).size());
            }
            app.ui(() -> {
                data.clear();
                data.addAll(list);
                noteImageCounts = counts;
                adapter.notifyDataSetChanged();
            });
        });
    }

    /** 编辑记录：借用人 / 借出日期 / 归还日期（留空=借出中）/ 备注 / 备注图片 */
    private void showEditDialog(BorrowRecord record) {
        currentEditBinding = DialogBorrowEditBinding.inflate(getLayoutInflater());
        DialogBorrowEditBinding db = currentEditBinding;
        db.etBorrower.setText(record.borrower);
        db.etLendDate.setText(record.lendDate);
        db.etReturnDate.setText(record.returnDate);
        db.etNote.setText(record.note);

        currentEditImages.clear();
        currentEditAddedPaths.clear();
        currentEditRemovedPaths.clear();
        loadBorrowNoteImages(record.id);

        db.btnAddNoteImage.setOnClickListener(v -> {
            try {
                pickEditImageLauncher.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("编辑借出记录")
                .setView(db.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    String borrower = db.etBorrower.getText().toString().trim();
                    if (borrower.isEmpty()) {
                        Toast.makeText(this, "借用人不能为空", Toast.LENGTH_SHORT).show();
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
                        saveBorrowNoteImages(app, record.id);
                        syncBookStock();
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        currentEditDialog = dialog;
        dialog.show();
    }

    private void loadBorrowNoteImages(long recordId) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<NoteImage> list = app.db().noteImageDao().getByOwnerKey("borrow:" + recordId);
            runOnUiThread(() -> {
                currentEditImages.addAll(list);
                if (currentEditBinding != null) {
                    renderEditNoteThumbs(currentEditBinding);
                }
            });
        });
    }

    private void saveBorrowNoteImages(BookshelfApp app, long recordId) {
        String ownerKey = "borrow:" + recordId;
        app.db().noteImageDao().deleteByOwnerKey(ownerKey);
        if (!currentEditImages.isEmpty()) {
            for (int i = 0; i < currentEditImages.size(); i++) {
                NoteImage img = currentEditImages.get(i);
                img.bookId = bookId;
                img.ownerKey = ownerKey;
                img.sort = i;
            }
            app.db().noteImageDao().insertAll(currentEditImages);
        }
        // 清理被移除的文件
        for (String p : currentEditAddedPaths) {
            boolean kept = false;
            for (NoteImage img : currentEditImages) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        for (String p : currentEditRemovedPaths) {
            new File(p).delete();
        }
        currentEditAddedPaths.clear();
        currentEditRemovedPaths.clear();
    }

    private void onEditImagePicked(Uri uri) {
        if (uri == null || currentEditBinding == null) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                File dir = new File(getFilesDir(), "note_images");
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
                    img.bookId = bookId;
                    img.path = out.getAbsolutePath();
                    currentEditImages.add(img);
                    currentEditAddedPaths.add(img.path);
                    renderEditNoteThumbs(currentEditBinding);
                    // 部分 ROM 在拉起相册时会收起弹窗，返回后重新展示以便用户看到已选缩略图
                    if (currentEditDialog != null && !currentEditDialog.isShowing()) {
                        currentEditDialog.show();
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void renderEditNoteThumbs(DialogBorrowEditBinding db) {
        db.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * getResources().getDisplayMetrics().density);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (NoteImage img : currentEditImages) {
            ImageView iv = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(this).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showEditNotePreview(db, img));
            db.containerNoteThumbs.addView(iv);
        }
        boolean has = !currentEditImages.isEmpty();
        db.scrollNoteThumbs.setVisibility(has ? View.VISIBLE : View.GONE);
        db.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + currentEditImages.size() + " 张）"
                : "＋ 添加备注图片");
    }

    private void showEditNotePreview(DialogBorrowEditBinding db, NoteImage img) {
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> {
                    currentEditImages.remove(img);
                    if (!currentEditAddedPaths.contains(img.path)) {
                        currentEditRemovedPaths.add(img.path);
                    }
                    renderEditNoteThumbs(db);
                })
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** 删除记录（带确认）：同时清理该记录的备注图片 */
    private void confirmDelete(BorrowRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除借出记录")
                .setMessage("删除借给「" + record.borrower + "」的这条记录？")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        String ownerKey = "borrow:" + record.id;
                        List<NoteImage> imgs = app.db().noteImageDao().getByOwnerKey(ownerKey);
                        app.db().noteImageDao().deleteByOwnerKey(ownerKey);
                        for (NoteImage img : imgs) {
                            new File(img.path).delete();
                        }
                        app.db().borrowRecordDao().deleteById(record.id);
                        syncBookStock();
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 记录增删改后同步书籍的藏书状态：
     * 借阅记录带 copyIndex，逐本对齐——第 1 本反映到书籍主记录 stockStatus，
     * 第 2 本起反映到 book_copies；仍有未归还记录的本 → 借出，否则 → 在库
     * （防止删掉借出中记录后对应的本还挂着「借出」）。遗失状态不覆盖。
     */
    private void syncBookStock() {
        BookshelfApp app = BookshelfApp.get();
        Book book = com.bai.bookshelf.util.BookSanitizer
                .normalize(app.db().bookDao().getById(bookId));
        if (book == null) {
            return;
        }
        // 当前仍然借出中的记录 → 涉及的副本序号集合
        java.util.Set<Integer> openCopies = new java.util.HashSet<>();
        for (BorrowRecord r : app.db().borrowRecordDao().findOpenListByBook(bookId)) {
            openCopies.add(r.copyIndex);
        }
        // 第 1 本：写在书籍主记录上
        boolean open1 = openCopies.contains(1);
        int want1 = open1 ? Book.STOCK_LENT : Book.STOCK_IN;
        if (book.stockStatus != want1 && book.stockStatus != Book.STOCK_LOST) {
            book.stockStatus = want1;
            book.updatedAt = System.currentTimeMillis();
            app.db().bookDao().update(book);
        }
        // 第 2 本起：写在副本表上
        for (com.bai.bookshelf.data.entity.BookCopy c : app.db().bookCopyDao().getByBook(bookId)) {
            if (c.stockStatus == Book.STOCK_LOST) {
                continue; // 用户手动标了遗失，不被借出记录覆盖
            }
            int want = openCopies.contains(c.copyIndex) ? Book.STOCK_LENT : Book.STOCK_IN;
            if (c.stockStatus != want) {
                c.stockStatus = want;
                app.db().bookCopyDao().update(c);
            }
        }
    }

    /** 单条记录的展示文案（多本时标明是第几本）；备注只展示前 20 字，图片不直接展示仅标〔图N〕 */
    private String describe(BorrowRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("借给 ").append(r.borrower);
        if (r.copyIndex > 1) {
            sb.append("（第 ").append(r.copyIndex).append(" 本）");
        }
        sb.append("\n借出：").append(r.lendDate.isEmpty() ? "未填" : r.lendDate);
        if (r.status == BorrowRecord.STATUS_RETURNED) {
            sb.append(" · 归还：").append(r.returnDate.isEmpty() ? "未填" : r.returnDate);
        } else {
            sb.append(" · 借出中");
        }
        if (!r.note.isEmpty()) {
            // 备注折叠：最多展示前 20 字，超出以「…」收尾（点条目查看完整备注与图片）
            String shown = r.note.length() > 20 ? r.note.substring(0, 20) + "…" : r.note;
            sb.append("\n备注：").append(shown);
        }
        Integer cnt = noteImageCounts.get(r.id);
        if (cnt != null && cnt > 0) {
            sb.append(" 〔图").append(cnt).append("〕");
        }
        return sb.toString();
    }

    /** 点列表条目的备注/图片区域：弹出只读详情（完整备注 + 可点击查看大图的备注图片） */
    private void showRecordDetail(BorrowRecord r) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<NoteImage> imgs = app.db().noteImageDao().getByOwnerKey("borrow:" + r.id);
            app.ui(() -> buildRecordDetailDialog(r, imgs));
        });
    }

    private void buildRecordDetailDialog(BorrowRecord r, List<NoteImage> imgs) {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        android.widget.TextView tvNote = new android.widget.TextView(this);
        tvNote.setTextSize(15);
        tvNote.setAutoLinkMask(Linkify.WEB_URLS);
        tvNote.setText(r.note.isEmpty() ? "（无备注）" : r.note);
        root.addView(tvNote);

        if (!imgs.isEmpty()) {
            android.widget.TextView tvHint = new android.widget.TextView(this);
            tvHint.setTextSize(13);
            tvHint.setText("备注图片（点击查看大图）：");
            root.addView(tvHint);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int size = dp(96);
            int gap = dp(8);
            for (NoteImage img : imgs) {
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(gap);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(this).load(new File(img.path))
                        .placeholder(R.drawable.ic_cover_placeholder)
                        .error(R.drawable.ic_cover_placeholder)
                        .into(iv);
                iv.setOnClickListener(v -> showImagePreview(img));
                row.addView(iv);
            }
            hsv.addView(row);
            root.addView(hsv);
        }
        sv.addView(root);
        new MaterialAlertDialogBuilder(this)
                .setTitle("借给 " + r.borrower)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 查看单张备注图片大图 */
    private void showImagePreview(NoteImage img) {
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 简单列表适配器（本页专用） */
    private class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemLocationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            BorrowRecord record = data.get(position);
            h.b.tvName.setText(describe(record));
            h.b.tvName.setTextSize(14);
            // 用不到的控件：默认徽标、星标
            h.b.tvDefaultBadge.setVisibility(View.GONE);
            h.b.btnStar.setVisibility(View.GONE);
            // 批量模式：显示勾选框、隐藏编辑/删除按钮，整行点击切换选中
            h.b.cbSelect.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            int btnVisibility = batchMode ? View.GONE : View.VISIBLE;
            h.b.btnEdit.setVisibility(btnVisibility);
            h.b.btnDelete.setVisibility(btnVisibility);
            h.b.cbSelect.setOnCheckedChangeListener(null);
            h.b.cbSelect.setChecked(selectedIds.contains(record.id));
            h.b.cbSelect.setOnCheckedChangeListener((c, checked) -> {
                if (checked) {
                    selectedIds.add(record.id);
                } else {
                    selectedIds.remove(record.id);
                }
                b.tvSelCount.setText("已选 " + selectedIds.size() + " 条");
            });
            h.itemView.setOnClickListener(v -> {
                if (batchMode) {
                    h.b.cbSelect.setChecked(!h.b.cbSelect.isChecked());
                }
            });
            // 非批量模式：点备注/条目查看完整备注与图片（图片不直接展示，点此展开）
            h.b.tvName.setOnClickListener(v -> {
                if (!batchMode) {
                    showRecordDetail(record);
                }
            });
            h.b.btnEdit.setOnClickListener(v -> showEditDialog(record));
            h.b.btnDelete.setOnClickListener(v -> confirmDelete(record));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemLocationBinding b;

            VH(ItemLocationBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
