package com.bai.bookshelf.ui.checkin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.databinding.ItemDayRecordBinding;
import com.bai.bookshelf.databinding.SheetDayRecordsBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CheckInDayDetail;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.UndoBar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 某一天的打卡记录明细（v5.9.2 第 5 项）。
 *
 * <p>「本月逐日」的书目列表只列在读的书，改成已读/想读之后那天的打卡就没有撤销入口，
 * 删书留下的孤儿记录更是无处可点。这里直接按 checkoutByDay 取全部记录：
 * 书名能查到就显示书名，查不到（书已删）显示「（已删除）」；每行一个撤销按钮，
 * 底部「撤销这一天全部」先弹确认。单条撤销不弹确认，但给一条可反悔的撤销条。
 *
 * <p>撤销一条记录不改书的阅读状态：走的仍是 {@link CheckInStore#toggle} 的
 *「已记在本轮 → 删」分支，已读完的书也能撤；书已删时没有 Book 可传，按主键删。
 * 面板在撤销后保持打开并缩短，页面回调负责重算日历点与摘要。
 */
public final class DayRecordsSheet {

    public interface Callback {
        /** 记录有增减：页面重取当月数据（点、摘要、逐日列表） */
        void onChanged();
    }

    private DayRecordsSheet() {
    }

    public static void show(BaseActivity activity, int dayKey,
                            Map<Long, String> titles, Callback callback) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return;
        }
        SheetDayRecordsBinding b = SheetDayRecordsBinding.inflate(activity.getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(b.getRoot());
        Session session = new Session(activity, app, b, dayKey, titles, callback);
        b.btnRecordsClear.setOnClickListener(v -> session.confirmClearAll());
        // 记录多时给列表一个上限，按钮不会被挤出屏幕；记录变少时跟着缩短
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        session.setListMaxHeight(screenH > 0 ? (int) (screenH * 0.4f) : 0);
        dialog.show();
        session.reload(true);
    }

    private static final class Session {
        private final BaseActivity activity;
        private final BookshelfApp app;
        private final SheetDayRecordsBinding b;
        private final int dayKey;
        private final Map<Long, String> titles;
        private final Callback callback;
        private final RecordsAdapter adapter = new RecordsAdapter(this::undoOne);
        private final int rowHeightPx;
        private int listMaxHeight;
        private boolean busy;

        Session(BaseActivity activity, BookshelfApp app,
                SheetDayRecordsBinding b, int dayKey, Map<Long, String> titles,
                Callback callback) {
            this.activity = activity;
            this.app = app;
            this.b = b;
            this.dayKey = dayKey;
            this.titles = titles;
            this.callback = callback;
            this.rowHeightPx = (int) (56 * activity.getResources().getDisplayMetrics().density);
            adapter.setTitles(titles);
            b.rvRecords.setLayoutManager(new LinearLayoutManager(activity));
            b.rvRecords.setAdapter(adapter);
        }

        void setListMaxHeight(int px) {
            listMaxHeight = px;
        }

        private void sizeList(int count) {
            ViewGroup.LayoutParams lp = b.rvRecords.getLayoutParams();
            int wanted = count <= 0 ? 0 : count * rowHeightPx;
            if (listMaxHeight > 0) {
                wanted = Math.min(wanted, listMaxHeight);
            }
            if (lp.height != wanted) {
                lp.height = wanted;
                b.rvRecords.setLayoutParams(lp);
            }
        }

        /** @param first 首次打开才播报标题；后续刷新只更新数字 */
        void reload(boolean first) {
            app.io(() -> {
                final List<CheckIn> rows;
                try {
                    rows = app.db().checkInDao().getByDay(dayKey);
                } catch (Exception ignored) {
                    return;
                }
                final List<CheckIn> copy = new ArrayList<>(rows);
                app.ui(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    adapter.submit(copy);
                    int n = copy.size();
                    sizeList(n);
                    b.tvRecordsTitle.setText(CheckInUiState.dayText(dayKey)
                            + " · " + n + " 条记录");
                    if (first) {
                        b.tvRecordsTitle.announceForAccessibility(
                                CheckInUiState.dayText(dayKey) + "，" + n + " 条打卡记录");
                    }
                    b.tvRecordsEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
                    b.btnRecordsClear.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
                    b.btnRecordsClear.setEnabled(!busy && n > 0);
                });
            });
        }

        private void undoOne(CheckIn row) {
            if (busy || row == null) {
                return;
            }
            busy = true;
            b.btnRecordsClear.setEnabled(false);
            final String title = titleOf(row.bookId);
            app.io(() -> {
                final boolean ok;
                try {
                    Book book = app.db().bookDao().getById(row.bookId);
                    if (book == null) {
                        // 书已删：没有 Book 可走 toggle，按主键清孤儿行
                        ok = app.db().checkInDao().deleteByBookAndDay(row.bookId, dayKey) > 0;
                    } else {
                        // owned == row.seq 分支先删后判，已读完/已弃读的书也能撤
                        ok = CheckInStore.toggle(app.db(), book, dayKey, true, row.seq)
                                == CheckInStore.Result.REMOVED;
                    }
                } catch (Exception ignored) {
                    app.ui(() -> finishOne(false, row, title));
                    return;
                }
                app.ui(() -> finishOne(ok, row, title));
            });
        }

        private void finishOne(boolean ok, CheckIn row, String title) {
            busy = false;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (!ok) {
                toast("这条记录已经不在了");
                reload(false);
                return;
            }
            toast("已撤销「" + title + "」的打卡");
            UndoBar.offer(b.getRoot(), "已撤销「" + title + "」的打卡",
                    () -> restore(row, title), null);
            changed();
        }

        /** 单删的撤销：原样插回（seq / backfill / createdAt 都保留），不重走打卡规则 */
        private void restore(CheckIn row, String title) {
            app.io(() -> {
                try {
                    app.db().checkInDao().insert(row);
                } catch (Exception ignored) {
                    return;
                }
                app.ui(() -> {
                    toast("已恢复「" + title + "」的打卡");
                    changed();
                });
            });
        }

        private void confirmClearAll() {
            final int n = adapter.getItemCount();
            if (busy || n == 0) {
                return;
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("撤销这一天的全部打卡？")
                    .setMessage("共 " + n + " 条记录。撤销后这本书的阅读天数会重算，"
                            + "但不会改变书的阅读状态。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("全部撤销", (d, w) -> clearAll())
                    .show();
        }

        private void clearAll() {
            busy = true;
            b.btnRecordsClear.setEnabled(false);
            app.io(() -> {
                final int removed;
                try {
                    List<Long> ids = new ArrayList<>();
                    for (CheckIn c : app.db().checkInDao().getByDay(dayKey)) {
                        ids.add(c.bookId);
                    }
                    removed = CheckInStore.removeDayForBooks(app.db(), ids, dayKey);
                } catch (Exception ignored) {
                    app.ui(() -> {
                        busy = false;
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            reload(false);
                        }
                    });
                    return;
                }
                app.ui(() -> {
                    busy = false;
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    toast(CheckInUiState.recordsClearedText(removed));
                    changed();
                });
            });
        }

        private void changed() {
            reload(false);
            // 第 12 项：单条撤销/恢复/全部撤销都会改「今日已打卡」
            com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(activity);
            if (callback != null) {
                callback.onChanged();
            }
        }

        private String titleOf(long bookId) {
            String name = titles == null ? null : titles.get(bookId);
            return name == null || name.isEmpty() ? CheckInDayDetail.DELETED_TITLE : name;
        }

        private void toast(String text) {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private interface UndoClick {
        void onUndo(CheckIn row);
    }

    private static final class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.VH> {
        private final List<CheckIn> rows = new ArrayList<>();
        private final UndoClick click;
        private Map<Long, String> titles;

        RecordsAdapter(UndoClick click) {
            this.click = click;
        }

        void submit(List<CheckIn> data) {
            rows.clear();
            if (data != null) {
                rows.addAll(data);
            }
            Collections.sort(rows, (a, b) -> Long.compare(a.bookId, b.bookId));
            notifyDataSetChanged();
        }

        void setTitles(Map<Long, String> titles) {
            this.titles = titles;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemDayRecordBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CheckIn row = rows.get(position);
            String name = titles == null ? null : titles.get(row.bookId);
            String title = name == null || name.isEmpty()
                    ? CheckInDayDetail.DELETED_TITLE : "《" + name + "》";
            holder.b.tvRecordBook.setText(title);
            holder.b.tvRecordKind.setText(row.backfill ? "补卡" : "直接打卡");
            holder.b.btnRecordUndo.setContentDescription("撤销" + title + "在这天的打卡");
            holder.b.btnRecordUndo.setOnClickListener(v -> click.onUndo(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final ItemDayRecordBinding b;

            VH(ItemDayRecordBinding binding) {
                super(binding.getRoot());
                b = binding;
            }
        }
    }
}
