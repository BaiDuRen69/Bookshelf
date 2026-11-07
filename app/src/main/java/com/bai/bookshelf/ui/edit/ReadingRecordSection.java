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
    /** 各轮日集合的暂存区：弹窗保存才进 pending，整本书保存才落 check_ins */
    private final ReadingDaySets daySets = new ReadingDaySets();

    public ReadingRecordSection(BaseActivity activity, ActivityBookEditBinding b, Book book) {
        this.activity = activity;
        this.b = b;
        this.book = book;
        records.addAll(ReadingRecords.load(book));
        initialJson = ReadingRecords.toJson(records);
        b.btnAddReadingRecord.setOnClickListener(v -> add());
        render();
        com.bai.bookshelf.app.BookshelfApp.get().whenDbReady(
                () -> daySets.load(com.bai.bookshelf.app.BookshelfApp.get(), book.id, null));
    }

    /** 是否有改动（用于未保存确认）：会话 JSON 或待落库的日集合，任一变了都算脏 */
    public boolean isDirty() {
        return !ReadingRecords.toJson(records).equals(initialJson) || daySets.hasPending();
    }

    /** 保存前把最后一次记录汇总到书籍字段 */
    public void syncToBook() {
        ReadingRecords.syncTo(book, records);
    }

    /** 整本书入库时调用（io 线程）：把改过的轮次日集合写进 check_ins */
    public void persistDaySets(com.bai.bookshelf.app.BookshelfApp app) {
        daySets.persist(app, book.id);
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
        int days;
        if (r.mode == ReadingRecords.MODE_PRECISE) {
            sb.append(" · 精确");
            days = com.bai.bookshelf.util.ReadDays.precise(daySets.keys(r.seq));
        } else {
            days = ReadingRecords.days(r);
        }
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

        // ===== 记录口径（v5.9.1 第四步）=====
        final int[] mode = {editing.mode};
        final boolean[] suppressMode = {false};
        // 弹窗自己的日集合副本：点「取消」一个字都不漏写，点「保存」才进 daySets
        final java.util.Map<Integer, List<Integer>> dialogDays = new java.util.HashMap<>();
        // 选择器回调要在 applyModeUi 之后才建得出来，用 holder 打破声明顺序的环
        final com.bai.bookshelf.ui.checkin.MonthGridView.PickListener[] pickHolder =
                new com.bai.bookshelf.ui.checkin.MonthGridView.PickListener[1];

        Runnable refreshDays = () -> {
            ReadingRecords.Record temp = new ReadingRecords.Record(editing.seq);
            temp.status = status[0];
            temp.mode = mode[0];
            List<Integer> keys = dialogDays.get(editing.seq);
            if (mode[0] == ReadingRecords.MODE_PRECISE) {
                // 精确档的起止是派生值：输入框只读展示，不让用户手填出第二份真相
                ReadingRecords.deriveRange(temp,
                        keys == null ? java.util.Collections.emptyList() : keys);
                db.etRecStart.setText(temp.startDate);
                db.etRecEnd.setText(temp.endDate);
            } else {
                temp.startDate = text(db.etRecStart);
                temp.endDate = text(db.etRecEnd);
            }
            int days = com.bai.bookshelf.util.ReadDays.of(temp, keys, null);
            db.tvRecDays.setText("阅读天数：" + (days > 0 ? days + " 天" : "—"));
            db.tvRecPicked.setText("已选 " + com.bai.bookshelf.util.ReadDays.precise(keys) + " 天");
        };

        Runnable syncChip = () -> {
            suppress[0] = true;
            db.cgRecStatus.check(chipId(status[0]));
            suppress[0] = false;
        };
        Runnable syncModeChip = () -> {
            suppressMode[0] = true;
            db.cgRecMode.check(mode[0] == ReadingRecords.MODE_PRECISE
                    ? R.id.chip_rec_precise : R.id.chip_rec_fuzzy);
            suppressMode[0] = false;
        };

        // 撤销快照：口径、状态、起止、评分、各轮日集合，逐字段留底
        java.util.function.Supplier<DialogSnap> snap = () -> {
            DialogSnap s = new DialogSnap();
            s.mode = mode[0];
            s.status = status[0];
            s.start = text(db.etRecStart);
            s.end = text(db.etRecEnd);
            s.rating = db.seekRecRating.getProgress() / 2.0;
            for (java.util.Map.Entry<Integer, List<Integer>> e : dialogDays.entrySet()) {
                s.days.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            return s;
        };

        Runnable applyModeUi = () -> {
            boolean precise = mode[0] == ReadingRecords.MODE_PRECISE;
            db.llRecPrecise.setVisibility(precise ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            db.etRecStart.setClickable(!precise);
            db.etRecEnd.setClickable(!precise);
            if (precise) {
                List<Integer> keys = dialogDays.get(editing.seq);
                if (keys == null) {
                    keys = daySets.keys(editing.seq);
                    dialogDays.put(editing.seq, keys);
                }
                db.mgRecDays.setPicker(keys, pickHolder[0]);
            } else {
                db.mgRecDays.setPicker(null, null);
            }
            refreshDays.run();
        };
        Runnable refreshAll = () -> {
            syncChip.run();
            syncModeChip.run();
            applyModeUi.run();
        };
        java.util.function.Consumer<DialogSnap> restore = s -> {
            mode[0] = s.mode;
            status[0] = s.status;
            db.etRecStart.setText(s.start);
            db.etRecEnd.setText(s.end);
            db.seekRecRating.setProgress(Math.max(0, Math.min(10, (int) Math.round(s.rating * 2))));
            db.tvRecRating.setText(ReadingRecords.formatRating(s.rating) + " 分");
            dialogDays.clear();
            for (java.util.Map.Entry<Integer, List<Integer>> e : s.days.entrySet()) {
                dialogDays.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            refreshAll.run();
        };
        java.util.function.BiConsumer<String, DialogSnap> offerUndo = (msg, before) ->
                com.bai.bookshelf.util.UndoBar.offer(db.getRoot(), msg,
                        () -> restore.accept(before), null);

        pickHolder[0] = new com.bai.bookshelf.ui.checkin.MonthGridView.PickListener() {
            @Override
            public void onPickChanged(List<Integer> keys) {
                // 回调进来时 dialogDays 还是改动前的样子，先留底再覆盖
                DialogSnap before = snap.get();
                dialogDays.put(editing.seq, new ArrayList<>(keys));
                refreshDays.run();
                offerUndo.accept("阅读日改为 " + keys.size() + " 天", before);
            }

            @Override
            public void onPickBlocked(int dayKey, boolean future) {
                Toast.makeText(activity, future
                        ? "未来的日子还没发生，不能算读过"
                        : "这一天属于别的月份，先切月再选", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPickAnchor(int dayKey) {
                db.tvRecPickHint.setText(dayKey == 0
                        ? "点一天勾上，长按一天再点另一天框住整段"
                        : "起点 " + DateKeys.formatKey(dayKey) + "，再点一天框住整段");
            }
        };

        db.cgRecMode.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (suppressMode[0] || checkedIds.isEmpty()) {
                return;
            }
            int next = checkedIds.get(0) == R.id.chip_rec_precise
                    ? ReadingRecords.MODE_PRECISE : ReadingRecords.MODE_FUZZY;
            if (next == mode[0]) {
                return;
            }
            if (next == ReadingRecords.MODE_PRECISE && !daySets.isLoaded()) {
                Toast.makeText(activity, "打卡记录还在读取，稍等一下再切", Toast.LENGTH_SHORT).show();
                syncModeChip.run();
                return;
            }
            DialogSnap before = snap.get();
            mode[0] = next;
            applyModeUi.run();
            offerUndo.accept(next == ReadingRecords.MODE_PRECISE
                    ? "已切到精确记录" : "已切回模糊记录", before);
        });

        db.btnRecSpan.setOnClickListener(v -> {
            int from = DateKeys.parseKey(text(db.etRecStart));
            if (from == 0) {
                Toast.makeText(activity, "先填开始日期，才能整段沿用", Toast.LENGTH_SHORT).show();
                return;
            }
            int to = DateKeys.parseKey(text(db.etRecEnd));
            if (to == 0) {
                to = DateKeys.todayKey(); // 在读没有结束日：沿用到今天
            }
            to = Math.min(to, DateKeys.todayKey());
            DialogSnap before = snap.get();
            List<Integer> keys = DateKeys.keyRange(from, to);
            dialogDays.put(editing.seq, keys);
            db.mgRecDays.setPickedDays(keys);
            refreshDays.run();
            offerUndo.accept("已整段沿用 " + keys.size() + " 天", before);
        });

        db.btnRecClear.setOnClickListener(v -> {
            DialogSnap before = snap.get();
            dialogDays.put(editing.seq, new ArrayList<>());
            db.mgRecDays.setPickedDays(java.util.Collections.emptyList());
            refreshDays.run();
            offerUndo.accept("已清空这一轮的阅读日", before);
        });

        db.btnRecMove.setOnClickListener(v -> {
            if (!daySets.isLoaded()) {
                Toast.makeText(activity, "打卡记录还在读取，稍等一下再移", Toast.LENGTH_SHORT).show();
                return;
            }
            List<Integer> mine = dialogDays.get(editing.seq);
            if (mine == null || mine.isEmpty()) {
                Toast.makeText(activity, "这一轮还没有日子可移", Toast.LENGTH_SHORT).show();
                return;
            }
            List<ReadingRecords.Record> others = new ArrayList<>();
            for (ReadingRecords.Record r : records) {
                if (r.seq != editing.seq) {
                    others.add(r);
                }
            }
            if (others.isEmpty()) {
                Toast.makeText(activity, "没有别的轮次可移", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[others.size()];
            for (int i = 0; i < others.size(); i++) {
                labels[i] = "第 " + others.get(i).seq + " 次";
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("把这一轮的日子移到")
                    .setItems(labels, (d, which) -> {
                        int toSeq = others.get(which).seq;
                        DialogSnap before = snap.get();
                        List<Integer> target = dialogDays.get(toSeq);
                        if (target == null) {
                            target = daySets.keys(toSeq);
                        }
                        java.util.Set<Integer> merged = new java.util.TreeSet<>(target);
                        merged.addAll(mine);
                        dialogDays.put(toSeq, new ArrayList<>(merged));
                        dialogDays.put(editing.seq, new ArrayList<>());
                        db.mgRecDays.setPickedDays(java.util.Collections.emptyList());
                        refreshDays.run();
                        offerUndo.accept("已把 " + mine.size() + " 天移到第 " + toSeq + " 次", before);
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });

        syncChip.run();
        syncModeChip.run();
        applyModeUi.run();

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
            editing.mode = mode[0];
            if (mode[0] == ReadingRecords.MODE_PRECISE) {
                if (!daySets.isLoaded()) {
                    Toast.makeText(activity, "打卡记录还没读完，稍后再保存", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Integer> keys = dialogDays.get(editing.seq);
                if (keys == null) {
                    keys = new ArrayList<>();
                }
                // 起止是日集合的派生值，不从输入框回读（那俩框在精确档是只读的）
                ReadingRecords.Record derived = new ReadingRecords.Record(editing.seq);
                derived.status = status[0];
                ReadingRecords.deriveRange(derived, keys);
                editing.startDate = derived.startDate;
                editing.endDate = derived.endDate;
                for (java.util.Map.Entry<Integer, List<Integer>> e : dialogDays.entrySet()) {
                    if (!e.getValue().equals(daySets.keys(e.getKey()))) {
                        daySets.commit(e.getKey(), e.getValue());
                    }
                }
            } else {
                editing.startDate = text(db.etRecStart);
                editing.endDate = text(db.etRecEnd);
            }
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

    /** 弹窗内一次可撤销操作的留底：口径/状态/起止/评分 + 各轮日集合，逐字段还原 */
    private static final class DialogSnap {
        int mode;
        int status;
        String start = "";
        String end = "";
        double rating;
        final java.util.Map<Integer, List<Integer>> days = new java.util.HashMap<>();
    }
}
