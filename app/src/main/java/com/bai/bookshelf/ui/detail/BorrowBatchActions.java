package com.bai.bookshelf.ui.detail;

import android.view.View;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityLocationBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.StatusText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 借出记录页的批量操作（v5.6 从 BorrowHistoryActivity 抽出）：
 * 进入/退出多选、全选、批量收回、批量删除（连带清理备注图片）。
 */
public class BorrowBatchActions {

    public interface Host {
        List<BorrowRecord> records();

        /** 同步书籍/副本在库状态（io 线程调用） */
        void syncStock();

        /** 重新加载列表（主线程） */
        void reload();

        /** 勾选状态变化后刷新列表 */
        void notifyList();
    }

    private final BaseActivity activity;
    private final ActivityLocationBinding b;
    private final Host host;

    private boolean active = false;
    private final Set<Long> selectedIds = new HashSet<>();

    public BorrowBatchActions(BaseActivity activity, ActivityLocationBinding b, Host host) {
        this.activity = activity;
        this.b = b;
        this.host = host;
    }

    public boolean isActive() {
        return active;
    }

    public Set<Long> ids() {
        return selectedIds;
    }

    /** 进入/退出批量操作模式 */
    public void toggle() {
        active = !active;
        selectedIds.clear();
        refreshUi();
    }

    /** 全选当前列表 */
    public void selectAll() {
        for (BorrowRecord r : host.records()) {
            selectedIds.add(r.id);
        }
        refreshUi();
    }

    /** 单条勾选切换（供列表适配器调用） */
    public void toggleOne(long recordId, boolean checked) {
        if (checked) {
            selectedIds.add(recordId);
        } else {
            selectedIds.remove(recordId);
        }
        b.tvSelCount.setText("已选 " + selectedIds.size() + " 条");
    }

    /** 同步批量模式的界面状态（操作条、底部按钮、列表勾选框） */
    public void refreshUi() {
        b.selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        b.bottomBar.setVisibility(active ? View.GONE : View.VISIBLE);
        b.btnSelReturn.setVisibility(active ? View.VISIBLE : View.GONE); // 收回按钮仅本页使用
        b.tvSelCount.setText("已选 " + selectedIds.size() + " 条");
        host.notifyList();
    }

    /** 批量收回：选中的借出中记录标记为今日收回 */
    public void confirmReturn() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(activity, "请先勾选记录", Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        List<BorrowRecord> targets = new ArrayList<>();
        for (BorrowRecord r : host.records()) {
            if (selectedIds.contains(r.id) && r.status == BorrowRecord.STATUS_OUT) {
                targets.add(r);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(activity, "选中的记录都已收回", Toast.LENGTH_SHORT).show();
            return;
        }
        app.io(() -> {
            for (BorrowRecord r : targets) {
                r.status = BorrowRecord.STATUS_RETURNED;
                r.returnDate = StatusText.today();
                app.db().borrowRecordDao().update(r);
            }
            host.syncStock();
            app.ui(() -> {
                toggle();
                Toast.makeText(activity, "已收回 " + targets.size() + " 条",
                        Toast.LENGTH_SHORT).show();
                host.reload();
            });
        });
    }

    /** 批量删除（带确认） */
    public void confirmDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(activity, "请先勾选要删除的记录", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("批量删除记录")
                .setMessage("删除已选的 " + count + " 条借出记录？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    Set<Long> ids = new HashSet<>(selectedIds);
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
                        host.syncStock();
                        app.ui(() -> {
                            toggle();
                            Toast.makeText(activity, "已删除 " + count + " 条记录",
                                    Toast.LENGTH_SHORT).show();
                            host.reload();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
