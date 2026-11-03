package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.util.Typo;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.databinding.DialogReadingRecordBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.widget.CalendarPicker;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadingRecords;
import com.bai.bookshelf.util.StatusText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页的「阅读记录」区（v5.7）：
 * 一本书可添加多次阅读（第 1 次、第 2 次…），每次记录状态、起止日期、自动天数与评分。
 * 保存书籍时取最后一次记录汇总成书籍的阅读状态/日期/评分。
 */
public class ReadingRecordSection {

    private final BaseActivity activity;
    private final ActivityBookEditBinding b;
    private final Book book;
    private final List<ReadingRecords.Record> records = new ArrayList<>();
    private final String initialJson;

    public ReadingRecordSection(BaseActivity activity, ActivityBookEditBinding b, Book book) {
        this.activity = activity;
        this.b = b;
        this.book = book;
        records.addAll(ReadingRecords.load(book));
        initialJson = ReadingRecords.toJson(records);
        b.btnAddReadingRecord.setOnClickListener(v -> add());
        render();
    }

    /** 是否有改动（用于未保存确认） */
    public boolean isDirty() {
        return !ReadingRecords.toJson(records).equals(initialJson);
    }

    /** 保存前把最后一次记录汇总到书籍字段 */
    public void syncToBook() {
        ReadingRecords.syncTo(book, records);
    }

    private void add() {
        // v5.8.31：取号统一走 ReadingFlow.nextSeq（max+1）。
        // 旧实现按 List 下标重排全部 seq，删过中间一场后会与状态机的取号规则错位，
        // 把「最后一场」指错对象。
        showDialog(new ReadingRecords.Record(com.bai.bookshelf.util.ReadingFlow.nextSeq(records)));
    }

    private void render() {
        b.containerReadingRecords.removeAllViews();
        boolean empty = records.isEmpty();
        b.tvReadingRecordsEmpty.setVisibility(empty ? android.view.View.VISIBLE
                : android.view.View.GONE);
        int pad = dp(12);
        for (ReadingRecords.Record r : records) {
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
            b.containerReadingRecords.addView(row);
        }
    }

    /**
     * 会话行（v5.8.31）：状态标签、这一场自己的起止与进度都要显示出来，
     * 弃读那场的评分额外标「不计入平均分」——汇总分只平均已读会话。
     */
    private String line(ReadingRecords.Record r) {
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次 · ")
                .append(StatusText.read(r.status));
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
            if (!r.endDate.isEmpty()) {
                sb.append(" — ").append(r.endDate);
            }
        } else if (!r.endDate.isEmpty()) {
            sb.append(" · — — ").append(r.endDate);
        }
        int days = ReadingRecords.days(r);
        if (days > 0) {
            sb.append(" · ").append(days).append(" 天");
        }
        // 每场显示自己的进度（缺字段的老记录由 parse 补齐：已读 100、其余 0）
        if (r.status == Book.READING || r.status == Book.READ_DROPPED) {
            sb.append(" · 读到 ").append(ReadingRecords.progressOf(r)).append("%");
        }
        if (r.rating > 0) {
            sb.append(" · ★").append(ReadingRecords.formatRating(r.rating));
            if (r.status == Book.READ_DROPPED) {
                sb.append("（不计入平均分）");
            }
        }
        return sb.toString();
    }

    private void showDialog(ReadingRecords.Record editing) {
        DialogReadingRecordBinding db = DialogReadingRecordBinding.inflate(
                activity.getLayoutInflater());
        final boolean isNew = !records.contains(editing);
        final int[] status = {editing.status};
        final boolean[] suppress = {false};
        // v5.8.31：这一场是否被**显式**选成弃读。选过之后，后面无论怎么改日期
        // 都不允许 suggestedStatus 把它推断回在读/已读（弃读没有「读完日期」可言）
        final boolean[] droppedPicked = {editing.status == Book.READ_DROPPED};

        db.etRecStart.setText(editing.startDate);
        db.etRecEnd.setText(editing.endDate);
        db.seekRecRating.setProgress(Math.max(0, Math.min(10,
                (int) Math.round(editing.rating * 2))));
        db.tvRecRating.setText(ReadingRecords.formatRating(editing.rating) + " 分");
        db.seekRecRating.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress,
                                          boolean fromUser) {
                db.tvRecRating.setText(ReadingRecords.formatRating(progress / 2.0) + " 分");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });

        Runnable refreshDays = () -> {
            ReadingRecords.Record temp = new ReadingRecords.Record(editing.seq);
            temp.status = status[0];
            temp.startDate = text(db.etRecStart);
            temp.endDate = text(db.etRecEnd);
            int days = ReadingRecords.days(temp);
            db.tvRecDays.setText("阅读天数：" + (days > 0 ? days + " 天" : "—"));
        };

        Runnable syncChip = () -> {
            suppress[0] = true;
            db.cgRecStatus.check(chipId(status[0]));
            suppress[0] = false;
        };
        syncChip.run();

        db.cgRecStatus.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (suppress[0] || checkedIds.isEmpty()) {
                return;
            }
            int next = statusOfChip(checkedIds.get(0));
            if (next == Book.READ_NONE) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("设为未读")
                        .setMessage("设为未读会清空所有阅读记录，是否继续？")
                        .setPositiveButton("清空阅读记录", (d, w) -> {
                            records.clear();
                            syncToBook();
                            render();
                            db.getRoot().clearFocus();
                            if (dialogHolder[0] != null) {
                                dialogHolder[0].dismiss();
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, (d, w) -> syncChip.run())
                        .show();
                return;
            }
            if (next == Book.READ_DROPPED) {
                droppedPicked[0] = true;
            }
            if (next == Book.READ_DROPPED
                    && !text(db.etRecStart).isEmpty() && !text(db.etRecEnd).isEmpty()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("设为弃读")
                        .setMessage("是否把结束日期改为今天？")
                        .setPositiveButton("改为今天", (d, w) -> {
                            db.etRecEnd.setText(DateKeys.formatKey(DateKeys.todayKey()));
                            status[0] = Book.READ_DROPPED;
                            refreshDays.run();
                        })
                        .setNegativeButton("保持原日期", (d, w) -> {
                            status[0] = Book.READ_DROPPED;
                            refreshDays.run();
                        })
                        .show();
                return;
            }
            if (next == Book.READING && !text(db.etRecEnd).isEmpty()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("设为在读")
                        .setMessage("是否清除结束日期？")
                        .setPositiveButton("清除结束日期", (d, w) -> {
                            db.etRecEnd.setText("");
                            status[0] = Book.READING;
                            refreshDays.run();
                        })
                        .setNegativeButton("保留日期", (d, w) -> {
                            status[0] = Book.READING;
                            refreshDays.run();
                        })
                        .show();
                return;
            }
            status[0] = next;
            refreshDays.run();
        });

        db.etRecStart.setOnClickListener(v -> CalendarPicker.showSingle(activity,
                "开始阅读", DateKeys.parseKey(text(db.etRecStart)),
                key -> {
                    db.etRecStart.setText(DateKeys.formatKey(key));
                    if (status[0] != Book.READ_DROPPED && !droppedPicked[0]) {
                        status[0] = ReadingRecords.suggestedStatus(text(db.etRecStart),
                                text(db.etRecEnd));
                        syncChip.run();
                    }
                    refreshDays.run();
                },
                () -> {
                    db.etRecStart.setText("");
                    if (status[0] != Book.READ_DROPPED && !droppedPicked[0]) {
                        status[0] = ReadingRecords.suggestedStatus("", text(db.etRecEnd));
                        syncChip.run();
                    }
                    refreshDays.run();
                }));
        db.etRecEnd.setOnClickListener(v -> CalendarPicker.showSingle(activity,
                "结束阅读", DateKeys.parseKey(text(db.etRecEnd)),
                key -> {
                    db.etRecEnd.setText(DateKeys.formatKey(key));
                    if (status[0] != Book.READ_DROPPED && !droppedPicked[0]) {
                        status[0] = ReadingRecords.suggestedStatus(text(db.etRecStart),
                                text(db.etRecEnd));
                        syncChip.run();
                    }
                    refreshDays.run();
                },
                () -> {
                    db.etRecEnd.setText("");
                    if (status[0] != Book.READ_DROPPED && !droppedPicked[0]) {
                        status[0] = ReadingRecords.suggestedStatus(text(db.etRecStart), "");
                        syncChip.run();
                    }
                    refreshDays.run();
                }));
        refreshDays.run();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(isNew ? "添加阅读记录（第 " + editing.seq + " 次）"
                        : "编辑阅读记录（第 " + editing.seq + " 次）")
                .setView(db.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (!isNew) {
            builder.setNeutralButton("删除", (d, w) -> confirmDelete(editing));
        }
        AlertDialog dialog = builder.show();
        dialogHolder[0] = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (status[0] == Book.READ_NONE) {
                Toast.makeText(activity, "请选择阅读状态", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isNew) {
                records.add(editing);
            }
            editing.status = status[0];
            editing.startDate = text(db.etRecStart);
            editing.endDate = text(db.etRecEnd);
            editing.rating = db.seekRecRating.getProgress() / 2.0;
            records.sort((a, c) -> Integer.compare(a.seq, c.seq));
            syncToBook();
            render();
            dialog.dismiss();
        });
    }

    /** 当前弹窗引用（“未读”清空时用于关闭） */
    private final AlertDialog[] dialogHolder = new AlertDialog[1];

    private void confirmDelete(ReadingRecords.Record record) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除阅读记录")
                .setMessage("删除第 " + record.seq + " 次阅读记录？")
                .setPositiveButton("删除", (d, w) -> {
                    records.remove(record);
                    // v5.8.31：不再把剩下的会话重新编号——重排 seq 会让状态机认错的
                    // 「最后一场」，删掉的那场也不该把后面的历史整体往前挪一位
                    syncToBook();
                    render();
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static String text(android.widget.EditText et) {
        return et.getText().toString().trim();
    }

    private static int chipId(int status) {
        switch (status) {
            case Book.READING:
                return R.id.chip_rec_reading;
            case Book.READ_DONE:
                return R.id.chip_rec_done;
            case Book.READ_DROPPED:
                return R.id.chip_rec_dropped;
            default:
                return R.id.chip_rec_none;
        }
    }

    private static int statusOfChip(int chipId) {
        if (chipId == R.id.chip_rec_reading) {
            return Book.READING;
        }
        if (chipId == R.id.chip_rec_done) {
            return Book.READ_DONE;
        }
        if (chipId == R.id.chip_rec_dropped) {
            return Book.READ_DROPPED;
        }
        return Book.READ_NONE;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
