package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.util.Typo;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.databinding.DialogBorrowRecordBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.widget.CalendarPicker;
import com.bai.bookshelf.util.BorrowSourceRecords;
import com.bai.bookshelf.util.DateKeys;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页的「借书记录」区（v5.7）：来源为借阅时，每次借阅记一条
 * （借阅自 / 借阅日期 / 应还日期 / 归还时间），保存时把最后一次同步到书籍汇总字段。
 */
public class BorrowRecordSection {

    private static final String NONE = "（无）";

    private final BaseActivity activity;
    private final ActivityBookEditBinding b;
    private final Book book;
    private final List<BorrowSourceRecords.Record> records = new ArrayList<>();
    private final String initialJson;
    private List<SourceChannel> channels = new ArrayList<>();
    private DialogBorrowRecordBinding openDialog;
    private AlertDialog dialog;

    public BorrowRecordSection(BaseActivity activity, ActivityBookEditBinding b, Book book) {
        this.activity = activity;
        this.b = b;
        this.book = book;
        records.addAll(BorrowSourceRecords.load(book));
        initialJson = BorrowSourceRecords.toJson(records);
        b.btnAddBorrowRecord.setOnClickListener(v -> add());
        render();
        loadChannels(null);
    }

    /** 从来源管理页返回后刷新下拉 */
    public void reloadChannels() {
        loadChannels(openDialog == null ? null : selectedSource(openDialog));
    }

    private void loadChannels(String selectName) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<SourceChannel> list = app.db().sourceChannelDao()
                    .getByType(Book.OWN_BORROW);
            app.ui(() -> {
                channels = list;
                if (openDialog != null) {
                    bindSourceSpinner(openDialog, selectName);
                }
            });
        });
    }

    /** 渠道下拉：与原编辑页「借阅自」同一套数据（来源库里 ownType=借阅 的项） */
    private void bindSourceSpinner(DialogBorrowRecordBinding db, String selectName) {
        List<String> names = new ArrayList<>();
        names.add(NONE);
        for (SourceChannel ch : channels) {
            names.add(ch.name);
        }
        String current = selectName == null ? "" : selectName.trim();
        int selected = 0;
        if (!current.isEmpty()) {
            int index = names.indexOf(current);
            if (index < 0) {
                names.add(current); // 老数据里的自由文本来源也保留可选
                index = names.size() - 1;
            }
            selected = index;
        }
        db.spBorrowSource.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, names));
        db.spBorrowSource.setSelection(selected);
    }

    private String selectedSource(DialogBorrowRecordBinding db) {
        Object item = db.spBorrowSource.getSelectedItem();
        if (item == null || NONE.contentEquals(item.toString())) {
            return "";
        }
        return item.toString().trim();
    }

    public boolean isDirty() {
        return !BorrowSourceRecords.toJson(records).equals(initialJson);
    }

    public void syncToBook() {
        BorrowSourceRecords.syncTo(book, records);
    }

    private void add() {
        renumber();
        BorrowSourceRecords.Record record = new BorrowSourceRecords.Record(records.size() + 1);
        record.source = defaultSource();
        record.quantity = defaultQuantity();
        showDialog(record);
    }

    private int defaultQuantity() {
        if (!records.isEmpty()) {
            return Math.max(1, records.get(records.size() - 1).quantity);
        }
        try {
            return Math.max(1, Integer.parseInt(b.etQuantity.getText().toString().trim()));
        } catch (Exception e) {
            return 1;
        }
    }

    /** 借阅数量同步到隐藏的「拥有数量」输入框，逐本信息按该数量展开 */
    private void syncQuantityToForm() {
        if (b.spOwn.getSelectedItemPosition() != Book.OWN_BORROW || records.isEmpty()) {
            return;
        }
        String q = String.valueOf(Math.max(1, records.get(records.size() - 1).quantity));
        if (!q.equals(b.etQuantity.getText().toString())) {
            b.etQuantity.setText(q);
        }
    }

    /** 新记录的借阅来源默认沿用上一次记录或书籍现有渠道 */
    private String defaultSource() {
        if (!records.isEmpty()) {
            String last = records.get(records.size() - 1).source;
            if (last != null && !last.trim().isEmpty()) {
                return last.trim();
            }
        }
        if (book.sourceChannel != null && !book.sourceChannel.trim().isEmpty()) {
            return book.sourceChannel.trim();
        }
        return "";
    }

    private void renumber() {
        for (int i = 0; i < records.size(); i++) {
            records.get(i).seq = i + 1;
        }
    }

    private void render() {
        b.containerBorrowRecords.removeAllViews();
        b.tvBorrowRecordsEmpty.setVisibility(records.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);
        int pad = dp(12);
        for (BorrowSourceRecords.Record r : records) {
            android.widget.TextView row = new android.widget.TextView(activity);
            row.setText(line(r));
            Typo.text(row, R.dimen.text_body_small);
            row.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                    activity, R.attr.on_surface));
            row.setBackgroundResource(R.drawable.bg_dialog_row);
            row.setPadding(pad, pad, pad, pad);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showDialog(r));
            b.containerBorrowRecords.addView(row);
        }
    }

    private String line(BorrowSourceRecords.Record r) {
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次");
        if (!r.source.isEmpty()) {
            sb.append(" · ").append(r.source);
        }
        sb.append(" · ").append(Math.max(1, r.quantity)).append(" 册");
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
        }
        if (!r.dueDate.isEmpty()) {
            sb.append(" — 应还 ").append(r.dueDate);
        }
        sb.append(r.returnDate.isEmpty() ? " · 未归还" : " · 已归还 " + r.returnDate);
        return sb.toString();
    }

    private void showDialog(BorrowSourceRecords.Record editing) {
        DialogBorrowRecordBinding db = DialogBorrowRecordBinding.inflate(
                activity.getLayoutInflater());
        boolean isNew = !records.contains(editing);
        openDialog = db;
        bindSourceSpinner(db, editing.source);
        db.etBorrowQuantity.setText(String.valueOf(Math.max(1, editing.quantity)));
        db.etBorrowRecStart.setText(editing.startDate);
        db.etBorrowRecDue.setText(editing.dueDate);
        db.etBorrowRecReturn.setText(editing.returnDate);

        db.btnAddBorrowSource.setOnClickListener(v -> AddOptionDialogs.showAddChannel(
                activity, Book.OWN_BORROW, "借阅来源", channels.size(),
                name -> loadChannels(name)));
        db.btnManageBorrowSource.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(activity,
                    com.bai.bookshelf.ui.location.SourceChannelActivity.class);
            intent.putExtra("ownType", Book.OWN_BORROW);
            activity.startActivity(intent);
        });

        db.etBorrowRecStart.setOnClickListener(v -> CalendarPicker.showSingle(activity,
                "借阅日期", DateKeys.parseKey(text(db.etBorrowRecStart)),
                key -> db.etBorrowRecStart.setText(DateKeys.formatKey(key)), () -> {
                    db.etBorrowRecStart.setText("");
                }));
        db.etBorrowRecDue.setOnClickListener(v -> CalendarPicker.showSingle(activity,
                "应还日期", DateKeys.parseKey(text(db.etBorrowRecDue)),
                key -> {
                    db.etBorrowRecDue.setText(DateKeys.formatKey(key));
                    ((BookEditActivity) activity).ensureNotifyPermission();
                },
                () -> {
                    db.etBorrowRecDue.setText("");
                }));
        db.etBorrowRecReturn.setOnClickListener(v -> CalendarPicker.showSingle(activity,
                "归还时间", DateKeys.parseKey(text(db.etBorrowRecReturn)),
                key -> db.etBorrowRecReturn.setText(DateKeys.formatKey(key)), () -> {
                    db.etBorrowRecReturn.setText("");
                }));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(isNew ? "添加借书记录（第 " + editing.seq + " 次）"
                        : "编辑借书记录（第 " + editing.seq + " 次）")
                .setView(db.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (!isNew) {
            builder.setNeutralButton("删除", (d, w) -> confirmDelete(editing));
        }
        dialog = builder.show();
        dialog.setOnDismissListener(d -> openDialog = null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String start = text(db.etBorrowRecStart);
            String due = text(db.etBorrowRecDue);
            if (start.isEmpty() && due.isEmpty()) {
                Toast.makeText(activity, "请至少填写借阅日期或应还日期",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (isNew) {
                records.add(editing);
            }
            editing.source = selectedSource(db);
            try {
                editing.quantity = Math.max(1, Integer.parseInt(
                        db.etBorrowQuantity.getText().toString().trim()));
            } catch (Exception e) {
                editing.quantity = 1;
            }
            editing.startDate = start;
            editing.dueDate = due;
            editing.returnDate = text(db.etBorrowRecReturn);
            records.sort((a, c) -> Integer.compare(a.seq, c.seq));
            syncToBook();
            syncQuantityToForm();
            render();
            dialog.dismiss();
        });
    }

    private void confirmDelete(BorrowSourceRecords.Record record) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除借书记录")
                .setMessage("删除第 " + record.seq + " 次借书记录？")
                .setPositiveButton("删除", (d, w) -> {
                    records.remove(record);
                    renumber();
                    syncToBook();
                    syncQuantityToForm();
                    render();
                    if (dialog != null) {
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static String text(android.widget.EditText et) {
        return et.getText().toString().trim();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
