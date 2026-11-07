package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.util.Typo;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.databinding.DialogReadingRecordBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.checkin.DayPickerSheet;
import com.bai.bookshelf.ui.widget.CalendarPicker;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadDays;
import com.bai.bookshelf.util.ReadingRecords;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页的「阅读记录」区（v5.7，v5.9.1 修订第四步加双模式）。
 *
 * <p>模糊档照旧：起止两个输入框 + CalendarPicker + suggestedStatus 推断（含弃读保护）。
 * 精确档：起止隐藏，改一行只读展示，点开 DayPickerSheet 补记；保存顺序固定
 * 「写 mode → applyDayKeys → syncTo」，任一步失败整体不落库并说明原因。
 */
public class ReadingRecordSection {

    private final BaseActivity activity;
    private final ActivityBookEditBinding b;
    private final Book book;
    private final List<ReadingRecords.Record> records = new ArrayList<>();
    private final String initialJson;
    /** 各轮日集合的读取缓存：列表行计数与弹窗初始已选都从这里取 */
    private final ReadingDaySets daySets = new ReadingDaySets();

    public ReadingRecordSection(BaseActivity activity, ActivityBookEditBinding b, Book book) {
        this.activity = activity;
        this.b = b;
        this.book = book;
        records.addAll(ReadingRecords.load(book));
        initialJson = ReadingRecords.toJson(records);
        b.btnAddReadingRecord.setOnClickListener(v -> add());
        render();
        BookshelfApp.get().whenDbReady(() -> daySets.load(BookshelfApp.get(), book.id, null));
    }

    /** 是否有改动（用于未保存确认） */
    public boolean isDirty() {
        return !ReadingRecords.toJson(records).equals(initialJson) || daySets.hasPending();
    }

    /** 保存前把最后一次记录汇总到书籍字段 */
    public void syncToBook() {
        ReadingRecords.syncTo(book, records);
    }

    /** 整本书入库时调用（io 线程）：把改过的轮次日集合写进 check_ins */
    public void persistDaySets(BookshelfApp app) {
        daySets.persist(app, book.id);
    }

    private void add() {
        // 手动添加通常是补录历史，默认模糊；要精确在分段里切（与 ReadingFlow 的活入口相反）
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
            row.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface));
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

    /** 会话行：模式徽标 + 双轨天数文案统一走 modeLabel/daysLabel，不再各处自己拼 */
    private String line(ReadingRecords.Record r) {
        boolean precise = r.mode == ReadingRecords.MODE_PRECISE;
        int days = precise ? ReadDays.precise(daySets.keys(r.seq)) : ReadingRecords.days(r);
        StringBuilder sb = new StringBuilder("第 ").append(r.seq).append(" 次 · ")
                .append(ReadingRecords.modeLabel(r))
                .append(" · ").append(ReadingRecords.daysLabel(days, precise));
        if (!r.startDate.isEmpty()) {
            sb.append(" · ").append(r.startDate);
            if (!r.endDate.isEmpty()) {
                sb.append(" — ").append(r.endDate);
            }
        } else if (!r.endDate.isEmpty()) {
            sb.append(" · — — ").append(r.endDate);
        }
        sb.append(" · ").append(StatusText.read(r.status));
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
        // v5.8.31：显式选过弃读后，日期推断不许把它覆盖回在读/已读
        final boolean[] droppedPicked = {editing.status == Book.READ_DROPPED};
        final int[] mode = {editing.mode};
        // 本轮已记日子：面板「确定」后更新；保存时交给 applyDayKeys 对齐
        final List<Integer>[] preciseKeys = newListHolder();
        preciseKeys[0] = daySets.keys(editing.seq);

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
            if (EditReadingMode.showsPreciseRow(mode[0])) {
                ReadingRecords.Record derived = new ReadingRecords.Record(editing.seq);
                derived.status = status[0];
                ReadingRecords.deriveRange(derived, preciseKeys[0]);
                int days = ReadDays.precise(preciseKeys[0]);
                db.tvRecDays.setText("阅读天数：" + (days > 0 ? days + " 天" : "—"));
                db.tvRecPreciseLine.setText(EditReadingMode.readonlyLine(
                        days, derived.startDate, derived.endDate));
            } else {
                ReadingRecords.Record temp = new ReadingRecords.Record(editing.seq);
                temp.status = status[0];
                temp.startDate = text(db.etRecStart);
                temp.endDate = text(db.etRecEnd);
                int days = ReadingRecords.days(temp);
                db.tvRecDays.setText("阅读天数：" + (days > 0 ? days + " 天" : "—"));
            }
        };

        Runnable syncChip = () -> {
            suppress[0] = true;
            db.cgRecStatus.check(chipId(status[0]));
            suppress[0] = false;
        };
        Runnable syncSeg = () -> {
            styleSeg(db.tvSegFuzzy, mode[0] == ReadingRecords.MODE_FUZZY);
            styleSeg(db.tvSegPrecise, mode[0] == ReadingRecords.MODE_PRECISE);
        };
        Runnable applyModeUi = () -> {
            boolean precise = EditReadingMode.showsPreciseRow(mode[0]);
            db.tilRecStart.setVisibility(EditReadingMode.showsDateInputs(mode[0])
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            db.tilRecEnd.setVisibility(EditReadingMode.showsDateInputs(mode[0])
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            db.llRecPrecise.setVisibility(precise ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            refreshDays.run();
        };

        java.util.function.IntConsumer switchMode = next -> {
            if (next == mode[0]) {
                return;
            }
            if (next == ReadingRecords.MODE_PRECISE && !daySets.isLoaded()) {
                Toast.makeText(activity, "打卡记录还在读取，稍等一下再切", Toast.LENGTH_SHORT).show();
                return;
            }
            if (next == ReadingRecords.MODE_FUZZY) {
                // 精确→模糊：派生起止回填输入框，已记的日子留在打卡记录里
                ReadingRecords.Record derived = new ReadingRecords.Record(editing.seq);
                derived.status = status[0];
                ReadingRecords.deriveRange(derived, preciseKeys[0]);
                if (!derived.startDate.isEmpty()) {
                    db.etRecStart.setText(derived.startDate);
                }
                db.etRecEnd.setText(derived.endDate);
                Toast.makeText(activity, EditReadingMode.fuzzySwitchNote(
                        ReadDays.precise(preciseKeys[0])), Toast.LENGTH_LONG).show();
            }
            // 模糊→精确不清空起止：原区间进面板当参考底纹
            mode[0] = next;
            syncSeg.run();
            applyModeUi.run();
        };
        db.tvSegFuzzy.setOnClickListener(v -> switchMode.accept(ReadingRecords.MODE_FUZZY));
        db.tvSegPrecise.setOnClickListener(v -> switchMode.accept(ReadingRecords.MODE_PRECISE));

        db.tvRecPreciseLine.setOnClickListener(v -> {
            // 参考底纹与「整段沿用」看的是这一轮**原来**的口径与起止，不是弹窗里暂切的 mode
            ReadingRecords.Record probe = new ReadingRecords.Record(editing.seq);
            probe.mode = editing.mode;
            probe.startDate = editing.startDate;
            probe.endDate = editing.endDate;
            DayPickerSheet.show(activity, book, probe, keys -> {
                preciseKeys[0] = keys;
                daySets.noteSaved(editing.seq, keys);
                refreshDays.run();
            });
        });

        syncChip.run();
        syncSeg.run();
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
            // 精确档的起止是派生值：选已读自动取日集合最大值、选在读自动清空，不问不手改
            if (EditReadingMode.showsDateInputs(mode[0])) {
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
            if (EditReadingMode.showsPreciseRow(mode[0])) {
                savePrecise(db, dialog, editing, status, mode, preciseKeys[0], syncSeg,
                        applyModeUi);
            } else {
                if (isNew) {
                    records.add(editing);
                }
                editing.status = status[0];
                editing.mode = ReadingRecords.MODE_FUZZY;
                editing.startDate = text(db.etRecStart);
                editing.endDate = text(db.etRecEnd);
                editing.rating = db.seekRecRating.getProgress() / 2.0;
                records.sort((a, c) -> Integer.compare(a.seq, c.seq));
                syncToBook();
                render();
                dialog.dismiss();
            }
        });
    }

    /** 精确档保存：mode → applyDayKeys → syncTo；被拒绝则整体不落库、弹窗留着说原因 */
    private void savePrecise(DialogReadingRecordBinding db, AlertDialog dialog,
                             ReadingRecords.Record editing, int[] status,
                             int[] mode, List<Integer> keys, Runnable syncSeg,
                             Runnable applyModeUi) {
        if (!EditReadingMode.canSavePrecise(keys.size())) {
            Toast.makeText(activity, "精确记录至少要有 1 天", Toast.LENGTH_SHORT).show();
            mode[0] = ReadingRecords.MODE_FUZZY; // 不静默降级：明确切回模糊让用户看见
            syncSeg.run();
            applyModeUi.run();
            return;
        }
        // 先把 mode 与状态进内存会话并 syncTo 暂存到 book，applyDayKeys 才认得这一轮是精确
        List<ReadingRecords.Record> staged = new ArrayList<>(records);
        if (!staged.contains(editing)) {
            staged.add(editing);
        }
        editing.mode = ReadingRecords.MODE_PRECISE;
        editing.status = status[0];
        ReadingRecords.syncTo(book, staged);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final CheckInStore.BatchResult result = CheckInStore.applyDayKeys(
                    app.db(), book, editing.seq, keys, DateKeys.todayKey());
            app.ui(() -> {
                if (result.rejected()) {
                    Toast.makeText(activity, "有 " + result.rejectedFuture + " 天在未来、"
                            + result.rejectedOtherSeq + " 天在别轮，本次未保存",
                            Toast.LENGTH_LONG).show();
                    return; // book 还没写库，整体不落
                }
                editing.rating = db.seekRecRating.getProgress() / 2.0;
                records.clear();
                records.addAll(staged);
                records.sort((a, c) -> Integer.compare(a.seq, c.seq));
                daySets.noteSaved(editing.seq, keys);
                syncToBook();
                render();
                dialog.dismiss();
            });
        });
    }

    /** 分段选中态：与统计页分栏同一套（底 + 主色 + 加粗 + setSelected 供读屏播报） */
    private void styleSeg(android.widget.TextView tv, boolean selected) {
        int primary = ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY);
        int normal = ThemeManager.resolveColor(activity, R.attr.on_surface_variant);
        tv.setBackgroundResource(selected ? R.drawable.bg_seg_selected_inset : 0);
        tv.setTextColor(selected ? primary : normal);
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        tv.setSelected(selected);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer>[] newListHolder() {
        return new List[1];
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
