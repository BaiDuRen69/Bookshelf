package com.bai.bookshelf.ui.checkin;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.SheetDayPickerBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadingRecords;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 精确日期选择器面板（v5.9.1 修订第三步）。
 *
 * <p>窗口动画与 24dp 顶部圆角沿用 ThemeOverlay.Bookshelf.BottomSheet，不另设背景。
 * 工具条四个动作即时生效、可反复操作；只有「确定」调 {@link CheckInStore#applyDayKeys}
 * 落库，失败项（未来 / 跨轮）在结果行与 Toast 里说清数量，面板不关。
 */
public final class DayPickerSheet {

    /** 确定且全部落库后回调，参数是最终日集合（升序） */
    public interface Callback {
        void onConfirmed(List<Integer> dayKeys);
    }

    private DayPickerSheet() {
    }

    public static void show(BaseActivity activity, Book book, ReadingRecords.Record record,
                            Callback callback) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final List<Integer> initial = app.db().checkInDao().getDayKeys(book.id, record.seq);
            app.ui(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                open(activity, app, book, record, initial, callback);
            });
        });
    }

    private static void open(BaseActivity activity, BookshelfApp app, Book book,
                             ReadingRecords.Record record, List<Integer> initial,
                             Callback callback) {
        SheetDayPickerBinding b = SheetDayPickerBinding.inflate(activity.getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(b.getRoot());

        b.tvPickerTitle.setText("精确记录 · 第 " + record.seq + " 轮");
        b.mgPicker.setRole(MonthGridView.ROLE_PICKER);
        b.mgPicker.setPickedDays(new HashSet<>(initial));

        // 原模糊区间只做参考底纹；「整段沿用」也只在这一轮原来有模糊起止时出现
        final int spanFrom = DateKeys.parseKey(record.startDate);
        int endRaw = DateKeys.parseKey(record.endDate);
        boolean hadFuzzyRange = record.mode == ReadingRecords.MODE_FUZZY && spanFrom > 0;
        final int spanTo = endRaw == 0 ? DateKeys.todayKey() : Math.min(endRaw, DateKeys.todayKey());
        if (hadFuzzyRange) {
            Set<Integer> ref = new HashSet<>(DateKeys.keyRange(spanFrom, spanTo));
            b.mgPicker.setReferenceDays(ref);
        } else {
            b.btnPickerSpan.setVisibility(View.GONE);
        }

        Runnable refreshCount = () ->
                b.tvPickerCount.setText(String.valueOf(b.mgPicker.pickedKeys().size()));
        refreshCount.run();

        b.mgPicker.setPickerListener(new MonthGridView.PickerListener() {
            @Override
            public void onToggleDay(int dayKey) {
                refreshCount.run();
            }

            @Override
            public void onRangeFill(int fromKey, int toKey) {
                refreshCount.run();
                b.tvPickerHint.setText("已框选 " + DateKeys.formatKey(fromKey) + " 至 "
                        + DateKeys.formatKey(toKey) + "；再点同样两端可整段取消");
                b.mgPicker.announceForAccessibility("已框选一段日期");
            }

            @Override
            public void onRangeCancel(int fromKey, int toKey) {
                refreshCount.run();
                b.tvPickerHint.setText("已取消 " + DateKeys.formatKey(fromKey) + " 至 "
                        + DateKeys.formatKey(toKey) + " 这一段");
                b.mgPicker.announceForAccessibility("已取消一段日期");
            }
        });

        b.btnPickerRange.setOnClickListener(v -> {
            boolean on = !b.mgPicker.isRangeMode();
            b.mgPicker.setRangeMode(on);
            b.tvPickerHint.setText(on
                    ? "框选模式：点起点，再点终点框住整段"
                    : "点一天勾上；开「连续区间」后点起点再点终点框住整段");
        });
        b.btnPickerClear.setOnClickListener(v -> {
            b.mgPicker.clearPicked();
            refreshCount.run();
            b.tvPickerResult.setVisibility(View.GONE);
        });
        b.btnPickerSpan.setOnClickListener(v -> {
            List<Integer> span = DateKeys.keyRange(spanFrom, spanTo);
            b.mgPicker.setPickedDays(new HashSet<>(span));
            refreshCount.run();
            b.tvPickerHint.setText("将记为 " + span.size() + " 天，含没翻过的日子");
        });
        b.btnPickerToday.setOnClickListener(v -> b.mgPicker.moveToToday());
        b.btnPickerCancel.setOnClickListener(v -> dialog.dismiss());
        b.btnPickerOk.setOnClickListener(v -> {
            final List<Integer> keys = b.mgPicker.pickedKeys();
            b.btnPickerOk.setEnabled(false);
            app.io(() -> {
                final CheckInStore.BatchResult result = CheckInStore.applyDayKeys(
                        app.db(), book, record.seq, keys, DateKeys.todayKey());
                app.ui(() -> {
                    b.btnPickerOk.setEnabled(true);
                    if (result.rejected()) {
                        String msg = "新增 " + result.added + " 天、删除 " + result.removed
                                + " 天；" + result.rejectedFuture + " 天在未来没记、"
                                + result.rejectedOtherSeq + " 天在别轮没记";
                        b.tvPickerResult.setText(msg);
                        b.tvPickerResult.setVisibility(View.VISIBLE);
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                        return; // 面板留着，让用户处理被拒绝的日子
                    }
                    dialog.dismiss();
                    if (callback != null) {
                        callback.onConfirmed(keys);
                    }
                });
            });
        });

        // 网格区限定高度，否则小屏上工具条与确定按钮会被挤出屏幕（同 PurchaseCalendarSheet 的坑）
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        if (screenH > 0) {
            ViewGroup.LayoutParams lp = b.pickerGridScroll.getLayoutParams();
            lp.height = (int) (screenH * 0.42f);
            b.pickerGridScroll.setLayoutParams(lp);
        }
        dialog.show();
    }
}
