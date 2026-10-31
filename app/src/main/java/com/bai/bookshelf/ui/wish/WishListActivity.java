package com.bai.bookshelf.ui.wish;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.databinding.ActivityWishListBinding;
import com.bai.bookshelf.databinding.ItemWishBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.WishBookMatcher;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 想读清单（v5.7）：把还没买/没借到的书先记下来。
 * 支持优先级/时间/书名/手动排序（手动可拖动手柄调整），
 * 长按进入多选后可批量改优先级或删除。
 */
public class WishListActivity extends BaseActivity {

    /** 桌面长按快捷方式：直接打开「添加想读」 */
    public static final String ACTION_ADD_WISH = "com.bai.bookshelf.action.ADD_WISH";

    private static final int REQ_CONVERT = 4801;

    private static final int SORT_PRIORITY = 0;
    private static final int SORT_TIME = 1;
    private static final int SORT_TITLE = 2;
    private static final int SORT_MANUAL = 3;
    private static final String KEY_SORT = "wish_sort_mode";

    private ActivityWishListBinding b;
    private WishEditDialog editDialog;
    private WishAdapter adapter;
    private ItemTouchHelper touchHelper;

    private final List<WishBook> items = new ArrayList<>();
    /** 书架里的书（用于「已在书架」提示与标注，不含已归还电子书签） */
    private List<Book> libraryBooks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean selectionMode;
    private int sortMode = SORT_PRIORITY;
    /** 快捷方式进入：等列表加载完再弹添加框，保证查重数据完整 */
    private boolean pendingAddWish;

    /** 正在「转为藏书」的那条想读；录入页真的保存成功后（RESULT_OK）才删除 */
    private WishBook converting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityWishListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsHelper.applyBottomInset(b.bottomWishBar);
        InsetsHelper.applyBottomInset(b.selectionBarWish);
        pendingAddWish = getIntent() != null
                && ACTION_ADD_WISH.equals(getIntent().getAction());

        sortMode = prefs().getInt(KEY_SORT, SORT_PRIORITY);
        editDialog = new WishEditDialog(this, new WishEditDialog.Host() {
            @Override
            public List<WishBook> items() {
                return items;
            }

            @Override
            public void onConvert(WishBook wish) {
                convertToBook(wish);
            }

            @Override
            public void onSaved() {
                load();
            }

            @Override
            public Book findInLibrary(WishBook wish) {
                return WishBookMatcher.findInLibrary(libraryBooks, wish);
            }
        });

        adapter = new WishAdapter();
        b.recyclerWish.setLayoutManager(new LinearLayoutManager(this));
        b.recyclerWish.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(b.recyclerWish);

        b.tvWishSort.setOnClickListener(v -> showSortMenu());
        b.btnAddWish.setOnClickListener(v -> editDialog.show(null, false));
        b.btnAddWishContinue.setOnClickListener(v -> editDialog.show(null, true));
        // v5.7 收口：空态改用共享组件（插画 + 衬线标题 + 说明 + 主按钮），文案在此给出
        b.emptyState.tvEmptyTitle.setText("还没有想读的书");
        b.emptyState.tvEmptyDesc.setText("买书前先看一眼，避免买重；买到后一键转为藏书");
        b.emptyState.btnEmptyCta.setText("添加想读");
        b.emptyState.btnEmptyCta.setOnClickListener(v -> editDialog.show(null, false));
        b.btnWishSelAll.setOnClickListener(v -> selectAll());
        b.btnWishSelEdit.setOnClickListener(v -> showBatchEdit());
        b.btnWishSelDelete.setOnClickListener(v -> confirmBatchDelete());
        b.btnWishSelCancel.setOnClickListener(v -> exitSelection());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // 从录入页返回后刷新（转为藏书会删掉这条）
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_ADD_WISH.equals(intent.getAction())) {
            pendingAddWish = true;
            load();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ui_prefs", MODE_PRIVATE);
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        final int mode = sortMode;
        app.io(() -> {
            List<WishBook> list;
            List<Book> books;
            try {
                list = mode == SORT_MANUAL
                        ? app.db().wishBookDao().getAllManual()
                        : app.db().wishBookDao().getAll();
                books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            } catch (Exception e) {
                list = new ArrayList<>();
                books = new ArrayList<>();
            }
            sortInMemory(list, mode);
            final List<WishBook> fList = list;
            final List<Book> fBooks = books;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                items.clear();
                items.addAll(fList);
                libraryBooks = fBooks;
                selectionMode = false;
                selectedIds.clear();
                adapter.notifyDataSetChanged();
                updateSortLabel();
                refreshSelectionUi();
                updateEmptyState();
                if (pendingAddWish) {
                    pendingAddWish = false;
                    editDialog.show(null, false);
                }
            });
        });
    }

    private static void sortInMemory(List<WishBook> list, int mode) {
        if (mode == SORT_TIME) {
            list.sort((a, c) -> Long.compare(c.createdAt, a.createdAt));
        } else if (mode == SORT_TITLE) {
            Collator collator = Collator.getInstance(Locale.CHINA);
            list.sort((a, c) -> collator.compare(a.title, c.title));
        }
    }

    private void updateSortLabel() {
        String name;
        switch (sortMode) {
            case SORT_TIME:
                name = "添加时间";
                break;
            case SORT_TITLE:
                name = "书名";
                break;
            case SORT_MANUAL:
                name = "手动（拖动 ≡ 调整）";
                break;
            default:
                name = "优先级";
                break;
        }
        b.tvWishSort.setText("排序：" + name + " ▾");
    }

    private void updateEmptyState() {
        boolean empty = items.isEmpty();
        b.recyclerWish.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.llWishEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showSortMenu() {
        String[] names = {"优先级", "添加时间", "书名", "手动排序"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("想读排序")
                .setSingleChoiceItems(names, sortMode, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == sortMode) {
                        return;
                    }
                    if (which == SORT_MANUAL) {
                        persistCurrentOrderAsManual();
                    }
                    sortMode = which;
                    prefs().edit().putInt(KEY_SORT, sortMode).apply();
                    load();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 切到手动排序前，把当前看到顺序写进 sort，视觉上不跳 */
    private void persistCurrentOrderAsManual() {
        final List<WishBook> snapshot = new ArrayList<>(items);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            for (int i = 0; i < snapshot.size(); i++) {
                WishBook wish = snapshot.get(i);
                wish.sort = i;
                app.db().wishBookDao().updateSort(wish.id, i);
            }
        });
    }

    /** 拖完后把手动顺序落库 */
    private void persistManualOrder() {
        final List<WishBook> snapshot = new ArrayList<>(items);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            for (int i = 0; i < snapshot.size(); i++) {
                WishBook wish = snapshot.get(i);
                wish.sort = i;
                app.db().wishBookDao().updateSort(wish.id, i);
            }
        });
    }

    // ======================= 多选与批量操作 =======================

    private void enterSelection(WishBook wish) {
        if (selectionMode) {
            return;
        }
        selectionMode = true;
        selectedIds.add(wish.id);
        refreshSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(WishBook wish) {
        if (!selectedIds.remove(wish.id)) {
            selectedIds.add(wish.id);
        }
        if (selectedIds.isEmpty()) {
            exitSelection();
            return;
        }
        refreshSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        for (WishBook wish : items) {
            selectedIds.add(wish.id);
        }
        refreshSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void exitSelection() {
        selectionMode = false;
        selectedIds.clear();
        refreshSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void refreshSelectionUi() {
        if (b == null) {
            return;
        }
        b.selectionBarWish.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        b.bottomWishBar.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        b.tvWishSelCount.setText("已选 " + selectedIds.size() + " 条");
    }

    private void showBatchEdit() {
        if (selectedIds.isEmpty()) {
            return;
        }
        String[] names = {"高优先", "中优先", "低优先"};
        int[] priorities = {WishBook.PRIORITY_HIGH, WishBook.PRIORITY_MID, WishBook.PRIORITY_LOW};
        new MaterialAlertDialogBuilder(this)
                .setTitle("批量编辑（已选 " + selectedIds.size() + " 条）")
                .setItems(names, (d, which) -> applyBatchPriority(priorities[which], names[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void applyBatchPriority(int priority, String label) {
        final int count = selectedIds.size();
        final Set<Long> ids = new HashSet<>(selectedIds);
        // v5.8.16：撤销需要改动前的优先级
        final java.util.Map<Long, Integer> before = new java.util.HashMap<>();
        for (WishBook wish : items) {
            if (ids.contains(wish.id)) {
                before.put(wish.id, wish.priority);
            }
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            for (WishBook wish : items) {
                if (ids.contains(wish.id)) {
                    wish.priority = priority;
                    wish.updatedAt = now;
                    app.db().wishBookDao().update(wish);
                }
            }
            app.ui(() -> {
                exitSelection();
                load();
                com.bai.bookshelf.util.UndoBar.offer(
                        findViewById(android.R.id.content),
                        "已把 " + count + " 条设为「" + label + "」",
                        before.isEmpty() ? null : () -> app.io(() -> {
                            for (WishBook wish : app.db().wishBookDao().getAllManual()) {
                                Integer old = before.get(wish.id);
                                if (old != null) {
                                    wish.priority = old;
                                    wish.updatedAt = System.currentTimeMillis();
                                    app.db().wishBookDao().update(wish);
                                }
                            }
                            app.ui(this::load);
                        }), null);
            });
        });
    }

    private void confirmBatchDelete() {
        final int count = selectedIds.size();
        if (count == 0) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("批量删除")
                .setMessage("删除已选的 " + count + " 条想读？删除后 8 秒内可在提示条上撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    final Set<Long> ids = new HashSet<>(selectedIds);
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        final List<WishBook> deleted = new ArrayList<>();
                        for (WishBook wish : items) {
                            if (ids.contains(wish.id)) {
                                deleted.add(wish);
                            }
                        }
                        for (Long id : ids) {
                            app.db().wishBookDao().deleteById(id);
                        }
                        app.ui(() -> {
                            exitSelection();
                            load();
                            // v5.8.16：按原主键写回即可整批恢复
                            com.bai.bookshelf.util.UndoBar.offer(
                                    findViewById(android.R.id.content),
                                    "已删除 " + count + " 条想读",
                                    () -> app.io(() -> {
                                        for (WishBook wish : deleted) {
                                            app.db().wishBookDao().insert(wish);
                                        }
                                        app.ui(this::load);
                                    }), null);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ======================= 「转为藏书」 =======================

    /** 买到/借到：把信息带进录入页；等录入页真的保存成功后再删掉这条想读 */
    private void convertToBook(WishBook wish) {
        converting = wish;
        Book prefill = new Book();
        prefill.title = wish.title;
        prefill.author = wish.author;
        prefill.isbn = wish.isbn;
        prefill.listPrice = wish.expectedPrice;
        android.content.Intent intent = new android.content.Intent(this, BookEditActivity.class);
        intent.putExtra(BookEditActivity.EXTRA_PREFILL, prefill);
        startActivityForResult(intent, REQ_CONVERT);
        Toast.makeText(this, "已带入录入页，保存后这条想读会自动移除",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONVERT) {
            return;
        }
        WishBook wish = converting;
        converting = null;
        if (wish == null || resultCode != RESULT_OK) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            app.db().wishBookDao().deleteById(wish.id);
            app.ui(() -> {
                Toast.makeText(this, "已加入书架，想读清单里已移除这条", Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    // ======================= 列表 =======================

    private class WishAdapter extends RecyclerView.Adapter<WishAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemWishBinding item = ItemWishBinding.inflate(
                    getLayoutInflater(), parent, false);
            return new VH(item);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            WishBook wish = items.get(position);
            h.b.tvWishTitle.setText(wish.title);
            h.b.tvWishPriority.setText(priorityText(wish.priority));
            h.b.tvWishPriority.setTextColor(ThemeManager.resolveColor(
                    WishListActivity.this,
                    wish.priority == WishBook.PRIORITY_HIGH
                            ? ThemeManager.ATTR_COLOR_PRIMARY : R.attr.on_surface_variant));
            boolean inShelf = WishBookMatcher.findInLibrary(libraryBooks, wish) != null;
            h.b.tvWishInShelf.setVisibility(inShelf ? View.VISIBLE : View.GONE);

            if (TextUtils.isEmpty(wish.author)) {
                h.b.tvWishMeta.setVisibility(View.GONE);
            } else {
                h.b.tvWishMeta.setVisibility(View.VISIBLE);
                h.b.tvWishMeta.setText(wish.author);
            }
            if (TextUtils.isEmpty(wish.note)) {
                h.b.tvWishNote.setVisibility(View.GONE);
            } else {
                h.b.tvWishNote.setVisibility(View.VISIBLE);
                h.b.tvWishNote.setText(wish.note);
            }

            h.b.cbWishSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.b.cbWishSelect.setOnCheckedChangeListener(null);
            h.b.cbWishSelect.setChecked(selectedIds.contains(wish.id));
            h.b.cbWishSelect.setOnCheckedChangeListener((cb, checked) -> {
                if (checked != selectedIds.contains(wish.id)) {
                    toggleSelection(wish);
                }
            });

            // v5.7：手柄常驻，任何排序方式下都能拖；首次拖动自动切到手动排序
            boolean canDrag = !selectionMode;
            h.b.tvWishDrag.setVisibility(canDrag ? View.VISIBLE : View.GONE);
            h.b.tvWishDrag.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && canDrag) {
                    if (sortMode != SORT_MANUAL) {
                        sortMode = SORT_MANUAL;
                        prefs().edit().putInt(KEY_SORT, sortMode).apply();
                        updateSortLabel();
                    }
                    touchHelper.startDrag(h);
                    return true;
                }
                return false;
            });

            h.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(wish);
                } else {
                    editDialog.show(wish, false);
                }
            });
            h.itemView.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    enterSelection(wish);
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemWishBinding b;

            VH(ItemWishBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }

    /** 手动排序拖动：只在手动模式且非多选时生效，松手后落库 */
    private class DragCallback extends ItemTouchHelper.SimpleCallback {

        DragCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // 只能按住 ≡ 手柄拖动，长按留给多选
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            if (sortMode != SORT_MANUAL || selectionMode) {
                return false;
            }
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            Collections.swap(items, from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            persistManualOrder();
        }
    }

    private static String priorityText(int priority) {
        if (priority == WishBook.PRIORITY_HIGH) {
            return "高优先";
        }
        if (priority == WishBook.PRIORITY_LOW) {
            return "低优先";
        }
        return "中优先";
    }
}
