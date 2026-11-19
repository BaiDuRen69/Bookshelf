package com.bai.bookshelf.ui.wish;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.WishBookMatcher;
import com.bai.bookshelf.util.WishFilter;
import com.bai.bookshelf.util.WishSortSpec;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 想读清单（v5.7，v5.9.8 大改）。
 *
 * <p>v5.9.8：可折叠搜索（书名/作者/ISBN/备注）；排序升级为组合排序
 * （多键 + 方向，{@link WishSortSpec}），手动排序仍是单选独占；条目区分
 * 「已在书架（借阅）」；显示心理价；列表滚动条与悬浮「到顶/到底」。
 * 长按进入多选后可批量改优先级或删除，「转为藏书」流程不变。
 */
public class WishListActivity extends BaseActivity {

    /** 桌面长按快捷方式：直接打开「添加想读」 */
    public static final String ACTION_ADD_WISH = "com.bai.bookshelf.action.ADD_WISH";

    private static final int REQ_CONVERT = 4801;

    /** 旧版单枚举排序的 prefs 键：只用于一次性迁移，迁移后不再写入 */
    private static final String KEY_SORT_LEGACY = "wish_sort_mode";
    private static final String KEY_SORT_SPEC = "wish_sort_spec";

    private ActivityWishListBinding b;
    private WishEditDialog editDialog;
    private WishAdapter adapter;
    private ItemTouchHelper touchHelper;

    /** 全量清单（查重、批量操作、手动顺序都以它为准） */
    private final List<WishBook> items = new ArrayList<>();
    /** 过滤后的展示清单（adapter 只绑这个）；无过滤时与 items 同序 */
    private final List<WishBook> visible = new ArrayList<>();
    /** 书架里的书（用于「已在书架」提示与标注，不含已归还电子书签） */
    private List<Book> libraryBooks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean selectionMode;
    /** 组合排序配置（含手动排序独占位）；旧 int 偏好在首次启动时迁移 */
    private WishSortSpec sortSpec;
    /** 搜索词（WishBookMatcher.normalize 归一化后）；会话内保留，不持久化 */
    private String searchQuery = "";
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

        sortSpec = loadSortSpec();
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

        b.tvWishSort.setOnClickListener(v -> showSortDialog());
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
        setupSearch();
        setupScrollJump();
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

    /** 旧 int 偏好一次性迁移成组合排序配置；坏 JSON 回退默认 */
    private WishSortSpec loadSortSpec() {
        String saved = prefs().getString(KEY_SORT_SPEC, null);
        if (saved != null) {
            return WishSortSpec.parse(saved);
        }
        WishSortSpec spec = WishSortSpec.fromLegacy(
                prefs().getInt(KEY_SORT_LEGACY, 0), null);
        prefs().edit().putString(KEY_SORT_SPEC, spec.toJson()).apply();
        return spec;
    }

    private void persistSortSpec() {
        prefs().edit().putString(KEY_SORT_SPEC, sortSpec.toJson()).apply();
    }

    // ======================= 搜索（T3） =======================

    private void setupSearch() {
        b.btnWishSearch.setOnClickListener(v -> {
            boolean open = b.llWishSearch.getVisibility() != View.VISIBLE;
            b.llWishSearch.setVisibility(open ? View.VISIBLE : View.GONE);
            b.btnWishSearch.setText(open ? "收起" : "搜索");
            if (open) {
                b.etWishSearch.requestFocus();
            } else {
                b.etWishSearch.setText(""); // 清空会经 TextWatcher 走 applyFilter
                searchQuery = "";
                applyFilter();
            }
        });
        b.btnWishSearchClear.setOnClickListener(v -> b.etWishSearch.setText(""));
        b.etWishSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                searchQuery = WishBookMatcher.normalize(s.toString());
                applyFilter();
            }
        });
    }

    private boolean isFiltering() {
        return !searchQuery.isEmpty();
    }

    /** 过滤态下的空态与正常空态共用一个组件，文案与按钮按场景切换 */
    private void applyFilter() {
        visible.clear();
        for (WishBook wish : items) {
            if (WishFilter.matches(wish, searchQuery)) {
                visible.add(wish);
            }
        }
        adapter.notifyDataSetChanged();
        updateCountLabel();
        updateEmptyState();
        updateScrollButtons();
    }

    // ======================= 加载与排序 =======================

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        final WishSortSpec spec = sortSpec;
        app.io(() -> {
            List<WishBook> list;
            List<Book> books;
            try {
                list = spec.manual
                        ? app.db().wishBookDao().getAllManual()
                        : app.db().wishBookDao().getAll();
                books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            } catch (Exception e) {
                list = new ArrayList<>();
                books = new ArrayList<>();
            }
            if (!spec.manual) {
                list.sort(spec.comparator());
            }
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
                applyFilter();
                refreshSelectionUi();
                if (pendingAddWish) {
                    pendingAddWish = false;
                    editDialog.show(null, false);
                }
            });
        });
    }

    private void updateSortLabel() {
        if (sortSpec.manual) {
            b.tvWishSort.setText("排序：手动（拖动 ≡ 调整）▾");
        } else {
            b.tvWishSort.setText("排序：" + sortSpec.label() + " ▾");
        }
    }

    /** v5.8.30：想读总数在页头副标题；v5.9.8 搜索时改成「命中 X / 共 N 本」 */
    private void updateCountLabel() {
        if (items.isEmpty()) {
            b.toolbar.setSubtitle(null);
        } else if (isFiltering()) {
            b.toolbar.setSubtitle("命中 " + visible.size() + " / 共 " + items.size() + " 本");
        } else {
            b.toolbar.setSubtitle("共 " + items.size() + " 本");
        }
    }

    private void updateEmptyState() {
        boolean empty = visible.isEmpty();
        b.recyclerWish.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.llWishEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        boolean filtering = isFiltering();
        b.emptyState.tvEmptyTitle.setText(filtering ? "没有匹配的想读" : "还没有想读的书");
        b.emptyState.tvEmptyDesc.setText(filtering
                ? "换个关键词，或收起搜索再看看全部" : "买书前先看一眼，避免买重；买到后一键转为藏书");
        b.emptyState.btnEmptyCta.setVisibility(filtering ? View.GONE : View.VISIBLE);
    }

    // ======================= 排序弹窗（T6） =======================

    /** 组合排序弹窗：勾选键 + 每键方向；「手动排序」单选独占，勾它就清掉其余键 */
    private void showSortDialog() {
        final String[] keyIds = {WishSortSpec.K_PRIORITY, WishSortSpec.K_PRICE,
                WishSortSpec.K_TITLE, WishSortSpec.K_TIME};
        // 工作副本：containsKey = 选中，value = 是否降序；点「确定」才写回 sortSpec
        final Map<String, Boolean> draft = new java.util.LinkedHashMap<>();
        for (WishSortSpec.KeyDir k : sortSpec.keys) {
            draft.put(k.key, k.desc);
        }
        final boolean[] manualDraft = {sortSpec.manual};
        final List<Runnable> renders = new ArrayList<>();
        final Runnable renderAll = () -> {
            for (Runnable r : renders) {
                r.run();
            }
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        for (String key : keyIds) {
            box.addView(sortRow(key, draft, manualDraft, renderAll, renders));
        }
        box.addView(sortManualRow(manualDraft, draft, renderAll, renders));
        TextView hint = new TextView(this);
        hint.setText("勾选多个键时按 优先级 → 价格 → 书名 → 添加时间 的次序逐级比较");
        hint.setTextSize(12);
        hint.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle("想读排序")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    sortSpec.manual = manualDraft[0];
                    sortSpec.keys.clear();
                    if (!manualDraft[0] && sortSpec.keys.isEmpty() && draft.isEmpty()) {
                        // 什么都没勾：给非空默认，避免列表失去排序口径
                        sortSpec.switchFromManual();
                    }
                    if (!manualDraft[0]) {
                        for (String key : keyIds) {
                            Boolean desc = draft.get(key);
                            if (desc != null) {
                                WishSortSpec.KeyDir k = new WishSortSpec.KeyDir();
                                k.key = key;
                                k.desc = desc;
                                sortSpec.keys.add(k);
                            }
                        }
                    }
                    if (sortSpec.keys.isEmpty() && !sortSpec.manual) {
                        sortSpec.switchFromManual();
                    }
                    persistSortSpec();
                    load();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 一行 = 键名（点选/取消）+ 方向箭头（仅选中后可点，▼↔▲） */
    private View sortRow(String key, Map<String, Boolean> draft, boolean[] manualDraft,
                         Runnable renderAll, List<Runnable> renders) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView name = new TextView(this);
        name.setTextSize(15);
        name.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        name.setPadding(0, 0, dp(12), 0);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        name.setClickable(true);
        name.setFocusable(true);
        row.addView(name);

        TextView arrow = new TextView(this);
        arrow.setTextSize(13);
        arrow.setPadding(dp(12), dp(4), dp(12), dp(4));
        arrow.setClickable(true);
        arrow.setFocusable(true);
        arrow.setBackgroundResource(R.drawable.bg_filter_pill);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(arrow);

        Runnable render = () -> {
            boolean checked = !manualDraft[0] && draft.containsKey(key);
            String label = WishSortSpec.keyName(key);
            name.setText(checked ? "☑ " + label : "☐ " + label);
            name.setTypeface(null, checked
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            arrow.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
            if (checked) {
                arrow.setText(draft.get(key) ? "▼ 降序" : "▲ 升序");
                arrow.setTextColor(ThemeManager.resolveColor(this,
                        ThemeManager.ATTR_COLOR_PRIMARY));
            }
        };
        renders.add(render);
        name.setOnClickListener(v -> {
            if (manualDraft[0]) {
                manualDraft[0] = false; // 从手动切回组合：给一个非空默认
                if (draft.isEmpty()) {
                    draft.put(WishSortSpec.K_PRIORITY, true);
                }
            }
            if (draft.containsKey(key)) {
                draft.remove(key);
            } else {
                draft.put(key, false);
            }
            renderAll.run();
        });
        arrow.setOnClickListener(v -> {
            Boolean desc = draft.get(key);
            if (desc != null) {
                draft.put(key, !desc);
                renderAll.run();
            }
        });
        render.run();
        return row;
    }

    /** 「手动排序」行：勾上就独占，清掉所有组合键（验收 A6 第 4 条） */
    private View sortManualRow(boolean[] manualDraft, Map<String, Boolean> draft,
                               Runnable renderAll, List<Runnable> renders) {
        TextView row = new TextView(this);
        row.setTextSize(15);
        row.setPadding(0, dp(14), 0, dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            if (!manualDraft[0]) {
                manualDraft[0] = true;
                draft.clear();
                renderAll.run();
            }
        });
        Runnable render = () -> {
            row.setText(manualDraft[0] ? "☑ 手动排序（拖动 ≡ 调整）" : "☐ 手动排序（拖动 ≡ 调整）");
            row.setTypeface(null, manualDraft[0]
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            row.setTextColor(ThemeManager.resolveColor(this, manualDraft[0]
                    ? ThemeManager.ATTR_COLOR_PRIMARY : R.attr.on_surface));
        };
        renders.add(render); // 从手动切回组合时（点任一键名）这行也要跟着刷新
        render.run();
        return row;
    }

    // ======================= 悬浮「到顶 / 到底」（T8） =======================

    private void setupScrollJump() {
        b.btnWishScrollTop.setOnClickListener(v -> {
            b.recyclerWish.smoothScrollToPosition(0);
            updateScrollButtons();
        });
        b.btnWishScrollBottom.setOnClickListener(v -> {
            int last = Math.max(0, visible.size() - 1);
            b.recyclerWish.smoothScrollToPosition(last);
            updateScrollButtons();
        });
        b.recyclerWish.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateScrollButtons();
            }
        });
    }

    /** 距顶超过一屏显示「到顶」、距底超过一屏显示「到底」；到端点自动隐藏 */
    private void updateScrollButtons() {
        if (b == null) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) b.recyclerWish.getLayoutManager();
        if (lm == null || visible.isEmpty()) {
            b.llWishScrollJump.setVisibility(View.GONE);
            return;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        boolean canUp = first > 0;
        boolean canDown = last < visible.size() - 1;
        boolean show = canUp || canDown;
        b.llWishScrollJump.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            b.btnWishScrollTop.setVisibility(canUp ? View.VISIBLE : View.GONE);
            b.btnWishScrollBottom.setVisibility(canDown ? View.VISIBLE : View.GONE);
        }
    }

    // ======================= 手动排序落库 =======================

    /** 切到手动排序前，把当前看到顺序写进 sort，视觉上不跳 */
    private void persistCurrentOrderAsManual() {
        final List<WishBook> snapshot = new ArrayList<>(visible);
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
        final List<WishBook> snapshot = new ArrayList<>(visible);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            for (int i = 0; i < snapshot.size(); i++) {
                WishBook wish = snapshot.get(i);
                wish.sort = i;
                app.db().wishBookDao().updateSort(wish.id, i);
            }
        });
    }

    /** 拖动手柄首次触发：自动切到手动排序（组合配置被清空） */
    private void switchToManualByDrag() {
        sortSpec.switchToManual();
        persistSortSpec();
        updateSortLabel();
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

    /** 全选只勾「看得见的」：过滤态下全选全量会误伤看不见的条目 */
    private void selectAll() {
        for (WishBook wish : visible) {
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
            WishBook wish = visible.get(position);
            h.b.tvWishTitle.setText(wish.title);
            h.b.tvWishPriority.setText(priorityText(wish.priority));
            h.b.tvWishPriority.setTextColor(ThemeManager.resolveColor(
                    WishListActivity.this,
                    wish.priority == WishBook.PRIORITY_HIGH
                            ? ThemeManager.ATTR_COLOR_PRIMARY : R.attr.on_surface_variant));

            // v5.9.8 T4：角标区分借阅来源；已归还电子书签维持「不算在书架」
            Book inShelf = WishBookMatcher.findInLibrary(libraryBooks, wish);
            h.b.tvWishInShelf.setVisibility(inShelf != null ? View.VISIBLE : View.GONE);
            if (inShelf != null) {
                h.b.tvWishInShelf.setText(BookFlags.isBorrowSource(inShelf)
                        ? "已在书架（借阅）" : "已在书架");
            }

            // v5.9.8 T5：心理价（¥ + 去尾零），0 = 没填不占位
            if (wish.expectedPrice > 0) {
                h.b.tvWishPrice.setVisibility(View.VISIBLE);
                h.b.tvWishPrice.setText("¥" + formatPrice(wish.expectedPrice));
            } else {
                h.b.tvWishPrice.setVisibility(View.GONE);
            }

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

            // v5.7：手柄常驻，任何排序方式下都能拖；首次拖动自动切到手动排序。
            // v5.9.8：过滤态下隐藏手柄——过滤序 ≠ 全量序，拖完落库会写错 sort
            boolean canDrag = !selectionMode && !isFiltering();
            h.b.tvWishDrag.setVisibility(canDrag ? View.VISIBLE : View.GONE);
            h.b.tvWishDrag.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && canDrag) {
                    if (!sortSpec.manual) {
                        switchToManualByDrag();
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
            return visible.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemWishBinding b;

            VH(ItemWishBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }

    /** 手动排序拖动：只在手动模式、非多选且无过滤时生效，松手后落库 */
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
            if (!sortSpec.manual || selectionMode || isFiltering()) {
                return false;
            }
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION
                    || from >= visible.size() || to >= visible.size()) {
                return false;
            }
            Collections.swap(visible, from, to);
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

    /** 心理价：1 位小数去尾零（45.50 → 45.5，45.00 → 45） */
    private static String formatPrice(double price) {
        return java.math.BigDecimal.valueOf(price)
                .stripTrailingZeros().toPlainString();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
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
