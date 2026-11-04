package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.Typo;
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
import com.bai.bookshelf.databinding.ItemLocationBinding;
import com.bai.bookshelf.util.StatusText;
import com.bumptech.glide.Glide;

import java.io.File;
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
    /** 批量操作（v5.6 抽到 BorrowBatchActions） */
    private BorrowBatchActions batchActions;

    /** 编辑弹窗（v5.6 从本类抽出） */
    private BorrowEditDialog editDialog;

    // 每条借出记录的备注图片张数（用于列表里「〔图N〕」标记，不直接展示图片）
    private java.util.Map<Long, Integer> noteImageCounts = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        editDialog = new BorrowEditDialog(this, new BorrowEditDialog.Host() {
            @Override
            public long bookId() {
                return bookId;
            }

            @Override
            public void syncStock() {
                BorrowHistoryActivity.this.syncBookStock();
            }

            @Override
            public void onSaved() {
                reload();
            }
        });
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

        batchActions = new BorrowBatchActions(this, b, new BorrowBatchActions.Host() {
            @Override
            public List<BorrowRecord> records() {
                return data;
            }

            @Override
            public void syncStock() {
                BorrowHistoryActivity.this.syncBookStock();
            }

            @Override
            public void reload() {
                BorrowHistoryActivity.this.reload();
            }

            @Override
            public void notifyList() {
                adapter.notifyDataSetChanged();
            }
        });
        // 批量操作：进入/取消/全选/收回/删除
        b.btnBatch.setOnClickListener(v -> batchActions.toggle());
        b.btnSelCancel.setOnClickListener(v -> batchActions.toggle());
        b.btnSelAll.setOnClickListener(v -> batchActions.selectAll());
        b.btnSelReturn.setOnClickListener(v -> batchActions.confirmReturn());
        b.btnSelDelete.setOnClickListener(v -> batchActions.confirmDelete());
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
                        com.bai.bookshelf.util.BorrowLedger.deleteOpen(this, record.id);
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 记录增删改后同步书籍的藏书状态：逻辑已平移到
     * {@link com.bai.bookshelf.util.BorrowLedger#syncStockFromRecords}（v5.8.32），
     * 这里只保留 host.syncStock() 需要的调用壳。
     */
    private void syncBookStock() {
        com.bai.bookshelf.util.BorrowLedger
                .syncStockFromRecords(BookshelfApp.get(), bookId);
    }

    /** 单条记录的展示文案（多本时标明是第几本）；备注只展示前 20 字，图片不直接展示仅标〔图N〕 */
    private String describe(BorrowRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("借给 ").append(r.borrower);
        if (r.copyIndex > 1) {
            sb.append("（第 ").append(r.copyIndex).append(" 册）");
        }
        sb.append("\n借出：").append(r.lendDate.isEmpty() ? "未填" : r.lendDate);
        if (r.status == BorrowRecord.STATUS_RETURNED) {
            sb.append(" · 归还：").append(r.returnDate.isEmpty() ? "未填" : r.returnDate);
        } else {
            sb.append(" · 借出中");
            int due = com.bai.bookshelf.util.DateKeys.parseKey(r.dueDate);
            if (due > 0) {
                sb.append(" · 应还 ").append(r.dueDate);
                if (due < com.bai.bookshelf.util.DateKeys.todayKey()) {
                    sb.append("（逾期 ").append(DateKeys.daysBetween(due, com.bai.bookshelf.util.DateKeys.todayKey()))
                            .append(" 天）");
                }
            }
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
        Typo.text(tvNote, R.dimen.text_body);
        tvNote.setAutoLinkMask(Linkify.WEB_URLS);
        tvNote.setText(r.note.isEmpty() ? "（无备注）" : r.note);
        root.addView(tvNote);

        if (!imgs.isEmpty()) {
            android.widget.TextView tvHint = new android.widget.TextView(this);
            Typo.text(tvHint, R.dimen.text_body_small);
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
            Typo.text(h.b.tvName, R.dimen.text_body_medium);
            // 用不到的控件：默认徽标、星标
            h.b.tvDefaultBadge.setVisibility(View.GONE);
            h.b.btnStar.setVisibility(View.GONE);
            // 批量模式：显示勾选框、隐藏编辑/删除按钮，整行点击切换选中
            h.b.cbSelect.setVisibility(batchActions.isActive() ? View.VISIBLE : View.GONE);
            int btnVisibility = batchActions.isActive() ? View.GONE : View.VISIBLE;
            h.b.btnEdit.setVisibility(btnVisibility);
            h.b.btnDelete.setVisibility(btnVisibility);
            h.b.cbSelect.setOnCheckedChangeListener(null);
            h.b.cbSelect.setChecked(batchActions.ids().contains(record.id));
            h.b.cbSelect.setOnCheckedChangeListener((c, checked) ->
                    batchActions.toggleOne(record.id, checked));
            h.itemView.setOnClickListener(v -> {
                if (batchActions.isActive()) {
                    h.b.cbSelect.setChecked(!h.b.cbSelect.isChecked());
                }
            });
            // 非批量模式：点备注/条目查看完整备注与图片（图片不直接展示，点此展开）
            h.b.tvName.setOnClickListener(v -> {
                if (!batchActions.isActive()) {
                    showRecordDetail(record);
                }
            });
            h.b.btnEdit.setOnClickListener(v -> editDialog.show(record));
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
