package com.bai.bookshelf.ui.shelf;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架页：藏书列表 + 搜索 + 状态/地点筛选 + 排序 + 录入口。
 * 支持列表/封面两种视图；个人藏书量小，筛选排序在内存中完成。
 */
public class ShelfFragment extends Fragment {

    private FragmentShelfBinding b;
    private BookAdapter adapter;
    private BookGridAdapter gridAdapter;
    private BookAdapter textAdapter; // 纯文字列表（无封面）

    private List<Book> allBooks = new ArrayList<>();
    private List<Location> locations = new ArrayList<>();
    private List<com.bai.bookshelf.data.entity.Tag> tagList = new ArrayList<>();
    private List<Category> categoryList = new ArrayList<>();

    /** 分类筛选下拉的树形条目（一级 + 其下二级，与 spinner 项一一对应，v4.8） */
    private final List<Category> categoryEntries = new ArrayList<>();

    /** v4.9 两段式分类筛选：当前一级下选中的二级名（空 = 整组） */
    private String catFilterSub = "";

    /** 上一次分类下拉事件的位置（首个事件来自程序填充，不弹二级展开） */
    private int lastCategorySpinnerPos = -1;
    private List<String> channelList = new ArrayList<>(); // 藏书里出现过的来源渠道（去重）
    private List<String> countryList = new ArrayList<>(); // 藏书里出现过的国家（去重，v3.5：留实例快照供视图重建后重放）
    private String pendingSearchQuery;                   // 外部带入的搜索词（视图未就绪时缓存）
    private boolean spinnerReady = false; // 防止初始化 spinner 时触发筛选
    // v4.4：是否已收到本实例的第一次 books 推送。onViewCreated 里 applyViewMode()
    // 会同步调一次 applyFilters，此时首推未到、allBooks 为空，不挡住会在
    // 加载指示旁边闪现「空书架」文案；首推到达后置位，之后所有渲染正常放行
    private boolean booksReceived = false;
    private boolean sortReversed = false; // 排序倒序开关
    private int viewMode = 0;             // 视图模式：0=列表 1=封面网格 2=纯文字列表
    private boolean selectionMode = false; // 多选模式（长按进入）
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();
    private List<Book> lastResult = new ArrayList<>(); // 当前筛选结果（全选用）

    // v3.2：各筛选 Spinner 的上次内容缓存，用于「内容没变就不重建适配器」
    private final List<String> tagSpinnerCache = new ArrayList<>();
    private final List<String> locSpinnerCache = new ArrayList<>();
    private final List<String> channelSpinnerCache = new ArrayList<>();
    private final List<String> categorySpinnerCache = new ArrayList<>();
    private final List<String> countrySpinnerCache = new ArrayList<>();

    /**
     * v3.3：当前视图是否已给 Spinner 填过数据。
     *
     * <p>Spinner 随视图重建而重建，而内容缓存是 Fragment 实例级的；
     * 只有「已填过 且 内容相同」才能跳过重建，否则新 Spinner 会是空的。
     */
    private boolean spinnersFilled = false;
    /**
     * v3.3：各 Spinner 的「用户选中文案」，以内容 cache 对象为 key。
     *
     * <p>视图重建时新 Spinner 没有任何选中项，用它把用户此前的筛选选择恢复回来，
     * 避免切个 Tab 就被重置成「不限」。
     */
    private final java.util.Map<List<String>, String> spinnerSelectionCache = new java.util.HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // v3.3：视图即将销毁重建，先把各 Spinner 当前的选中文案存下来，
        // 否则用户在重建后会被重置回「不限」
        saveSelections();
        b = FragmentShelfBinding.inflate(inflater, container, false);
        // v3.3 关键修复：视图是每次 onCreateView 全新创建的，而下面这些字段是 Fragment 实例级的、
        // 会跨视图重建存活。若不同步重置，就会出现「cache 认为内容没变 → 跳过填充」，
        // 但新 Spinner 其实还是空的 → 分类/标签/地点/来源/国家全部空白且无法选择。
        spinnerReady = false;   // 新视图的 Spinner 还没数据
        spinnersFilled = false; // 新视图尚未填充过任何 Spinner
        clearSpinnerCaches();   // 清空缓存，强制下次填充
        return b.getRoot();
    }

    /** 保存各 Spinner 当前的选中文案（视图重建前调用） */
    private void saveSelections() {
        if (b == null) {
            return;
        }
        rememberSelection(b.spCategory, categorySpinnerCache);
        rememberSelection(b.spTag, tagSpinnerCache);
        rememberSelection(b.spLocation, locSpinnerCache);
        rememberSelection(b.spChannel, channelSpinnerCache);
        rememberSelection(b.spCountry, countrySpinnerCache);
    }

    private void rememberSelection(android.widget.Spinner spinner, List<String> cache) {
        if (spinner == null) {
            return;
        }
        Object sel = spinner.getSelectedItem();
        String text = sel == null ? "" : sel.toString();
        if (text.isEmpty() || text.equals(cache.isEmpty() ? "" : cache.get(0))) {
            // 选的是首项「不限」→ 不是筛选，清掉记忆
            spinnerSelectionCache.remove(cache);
            return;
        }
        spinnerSelectionCache.put(cache, text);
    }

    /** Spinner 选中变化时记录选中文案（以对应 cache 为 key） */
    private void recordSelection(AdapterView<?> parent, int position) {
        List<String> cache = cacheOf(parent);
        if (cache == null) {
            return;
        }
        if (position <= 0) {
            // 用户主动选回首项「不限」——清掉记忆，否则下次视图重建又会被恢复成旧筛选
            spinnerSelectionCache.remove(cache);
            return;
        }
        Object item = parent.getSelectedItem();
        String text = item == null ? "" : item.toString();
        if (text.isEmpty() || text.equals(cache.get(0))) {
            return;
        }
        spinnerSelectionCache.put(cache, text);
    }

    /** 由 Spinner 实例反查它对应的 cache 字段 */
    private List<String> cacheOf(AdapterView<?> parent) {
        if (b == null) {
            return null;
        }
        if (parent == b.spCategory) {
            return categorySpinnerCache;
        } else if (parent == b.spTag) {
            return tagSpinnerCache;
        } else if (parent == b.spLocation) {
            return locSpinnerCache;
        } else if (parent == b.spChannel) {
            return channelSpinnerCache;
        } else if (parent == b.spCountry) {
            return countrySpinnerCache;
        }
        return null; // 藏书状态/阅读/排序：内容写死在 arrays.xml，无需记忆
    }

    /** 清空所有 Spinner 内容缓存 */
    private void clearSpinnerCaches() {
        tagSpinnerCache.clear();
        locSpinnerCache.clear();
        channelSpinnerCache.clear();
        categorySpinnerCache.clear();
        countrySpinnerCache.clear();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // v4.4：首次加载指示改在视图创建时显示（此实例还没有任何数据可显示时），
        // 两段式渲染下 books 一到就隐藏（见 onBooksPushed 第一段）——
        // 指示只覆盖「Room 首推」的空窗，不再被启动期长任务拖长
        if (allBooks.isEmpty()) {
            b.llLoading.setVisibility(View.VISIBLE);
        }
        // 列表 / 封面 / 文字 三适配器：点击在多选模式下是切换选中，否则进详情页
        BookAdapter.OnBookClick openDetail = book -> {
            if (selectionMode) {
                toggleSelect(book);
                return;
            }
            Intent intent = new Intent(requireContext(), BookDetailActivity.class);
            intent.putExtra(BookDetailActivity.EXTRA_BOOK, book);
            startActivity(intent); // 刷新由 Room 响应式观察自动完成（v4.2）
        };
        adapter = new BookAdapter(openDetail);
        gridAdapter = new BookGridAdapter(openDetail::onClick);
        textAdapter = new BookAdapter(openDetail, true); // 无封面
        // 长按进入多选模式（三个适配器行为一致）
        BookAdapter.OnBookLongClick enterSelect = this::enterSelection;
        adapter.setOnBookLongClick(enterSelect);
        textAdapter.setOnBookLongClick(enterSelect);
        gridAdapter.setOnBookLongClick(this::enterSelection);

        // 多选操作条：全选 / 删除 / 取消
        b.btnSelAll.setOnClickListener(v -> selectAll());
        b.btnSelDelete.setOnClickListener(v -> confirmBatchDelete());
        b.btnSelCancel.setOnClickListener(v -> exitSelection());
        // 恢复上次的视图模式（兼容旧版的 boolean 存法）
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("ui_prefs", 0);
        viewMode = prefs.getInt("shelf_view_mode", -1);
        if (viewMode < 0) {
            viewMode = prefs.getBoolean("shelf_view_grid", false) ? 1 : 0;
        }
        applyViewMode();

        // 倒序开关：切换后立即重排
        b.btnSortReverse.setOnClickListener(v -> {
            sortReversed = !sortReversed;
            b.btnSortReverse.setText(sortReversed ? "倒序 ✓" : "倒序");
            applyFilters();
        });
        // 视图切换：列表 → 封面 → 文字 → 列表 循环
        b.btnView.setOnClickListener(v -> {
            viewMode = (viewMode + 1) % 3;
            prefs.edit().putInt("shelf_view_mode", viewMode).apply();
            applyViewMode();
        });

        // 筛选 spinner：藏书状态 / 阅读状态 / 排序（地点/标签/渠道/分类在数据加载后填充）
        // 全部使用紧凑闭合样式（小字号小内边距），下拉弹层保持系统默认
        b.spStock.setAdapter(new HighlightAdapter<>(R.array.filter_stock_entries));
        b.spReadFilter.setAdapter(new HighlightAdapter<>(R.array.filter_read_entries));
        b.spSort.setAdapter(new HighlightAdapter<>(R.array.sort_entries));

        AdapterView.OnItemSelectedListener filterListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 更新下拉弹层里的选中高亮位置
                if (parent.getAdapter() instanceof HighlightAdapter) {
                    ((HighlightAdapter<?>) parent.getAdapter()).setHighlight(position);
                }
                // v3.3：记住用户的选择，视图重建后据此恢复
                recordSelection(parent, position);
                if (spinnerReady) {
                    applyFilters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        b.spStock.setOnItemSelectedListener(filterListener);
        b.spReadFilter.setOnItemSelectedListener(filterListener);
        b.spLocation.setOnItemSelectedListener(filterListener);
        b.spTag.setOnItemSelectedListener(filterListener);
        b.spChannel.setOnItemSelectedListener(filterListener);
        // 分类下拉（v4.9）：在通用筛选行为之上，选中一级后弹出该组二级展开选择
        b.spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent.getAdapter() instanceof HighlightAdapter) {
                    ((HighlightAdapter<?>) parent.getAdapter()).setHighlight(position);
                }
                recordSelection(parent, position);
                // 首个事件来自程序填充（适配器刚装好），不当作用户选择
                boolean userDriven = lastCategorySpinnerPos >= 0;
                lastCategorySpinnerPos = position;
                if (!spinnerReady) {
                    return;
                }
                if (position > 0 && userDriven
                        && position - 1 < categoryEntries.size()) {
                    catFilterSub = ""; // 换了一级，先回到整组
                    applyFilters();
                    maybeShowSubCategoryDialog(categoryEntries.get(position - 1));
                } else {
                    if (position == 0) {
                        catFilterSub = "";
                    }
                    applyFilters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        b.spCountry.setOnItemSelectedListener(filterListener);
        b.spSort.setOnItemSelectedListener(filterListener);

        // 搜索框：边输边筛
        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 录入方式选择
        b.btnAdd.setOnClickListener(v -> showAddOptions());
        // 扫码快捷入口
        b.fabScan.setOnClickListener(v -> launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));

        // v4.2（评审 #7）：响应式观察 books 表——任何增删改（包括纯文本字段编辑）
        // 都由 Room 自动推送，无需指纹比对、RESULT_OK 标脏或手动触发。
        // 注册时 Room 会立即推送一次当前数据（冷启动首次加载 / 旋转屏恢复都靠它）。
        BookshelfApp.get().db().bookDao().getAllLive()
                .observe(getViewLifecycleOwner(), this::onBooksPushed);

        // 外部（统计页点击作者/出版社/国家）带入的搜索词：视图重建后应用一次
        if (pendingSearchQuery != null) {
            String q = pendingSearchQuery;
            pendingSearchQuery = null;
            b.etSearch.setText(q);
        }
    }

    /**
     * 由统计页/二级页等外部调用：切到书架并展示对应关键词的搜索结果（v2.8，v2.9 加固）。
     * 始终先缓存关键词；视图已就绪则立即灌入搜索框（TextWatcher 会自动触发筛选）。
     * 视图未就绪时由 onViewCreated / 响应式推送完成后兜底应用，保证任何时序下都能生效。
     */
    public void requestSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        pendingSearchQuery = keyword.trim();
        if (b != null) {
            pendingSearchQuery = null;
            b.etSearch.setText(keyword.trim());
        }
    }

    // v4.2：原先的 onResume/onHiddenChanged/onVisible 按需刷新已整体删除——
    // 书架改由 Room 响应式观察驱动（见 onViewCreated 的 getAllLive().observe），
    // books 表任何增删改都会自动推送刷新，纯文本编辑也不再依赖 RESULT_OK 标脏。
    // 切 Tab（show/hide）时视图与数据原地存活，无需任何手动刷新。

    /** 启动子页面（录入/编辑等）。刷新由 Room 响应式观察自动完成（v4.2） */
    private void launchAndRefresh(Intent intent) {
        startActivity(intent);
    }

    // ======================= 长按多选与批量删除 =======================

    /** 长按某本书进入多选模式，并选中它 */
    private void enterSelection(Book book) {
        if (selectionMode) {
            return; // 已在多选中，长按不重复触发
        }
        selectionMode = true;
        selectedIds.add(book.id);
        refreshSelectionUI();
    }

    /** 多选模式下点击：切换该书的选中状态；选空了自动退出多选 */
    private void toggleSelect(Book book) {
        if (!selectedIds.remove(book.id)) {
            selectedIds.add(book.id);
        }
        if (selectedIds.isEmpty()) {
            exitSelection();
            return;
        }
        refreshSelectionUI();
    }

    /** 全选当前筛选结果 */
    private void selectAll() {
        for (Book book : lastResult) {
            selectedIds.add(book.id);
        }
        refreshSelectionUI();
    }

    /** 退出多选模式 */
    private void exitSelection() {
        selectionMode = false;
        selectedIds.clear();
        refreshSelectionUI();
    }

    /** 把多选状态同步到操作条与三个适配器 */
    private void refreshSelectionUI() {
        if (b == null) {
            return;
        }
        b.selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        b.tvSelCount.setText("已选 " + selectedIds.size() + " 本");
        adapter.setSelectionState(selectionMode, selectedIds);
        textAdapter.setSelectionState(selectionMode, selectedIds);
        gridAdapter.setSelectionState(selectionMode, selectedIds);
    }

    /** 批量删除确认 */
    private void confirmBatchDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("批量删除")
                .setMessage("确定删除已选的 " + count + " 本书吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    java.util.Set<Long> ids = new java.util.HashSet<>(selectedIds);
                    app.io(() -> {
                        for (Long id : ids) {
                            // 联动清理：备注图片（文件+记录）、逐本信息
                            java.util.List<com.bai.bookshelf.data.entity.NoteImage> imgs =
                                    app.db().noteImageDao().getByOwnerKey("book:" + id);
                            app.db().noteImageDao().deleteByBook(id);
                            for (com.bai.bookshelf.data.entity.NoteImage img : imgs) {
                                new java.io.File(img.path).delete();
                            }
                            app.db().bookCopyDao().deleteByBook(id);
                            app.db().bookDao().deleteById(id);
                        }
                        app.ui(() -> {
                            exitSelection();
                            Toast.makeText(requireContext(), "已删除 " + count + " 本书", Toast.LENGTH_SHORT).show();
                            // 列表刷新由 Room 响应式观察自动推送（v4.2）
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 切换 RecyclerView 的视图模式（列表 / 封面网格 / 纯文字 三态循环） */
    private void applyViewMode() {
        if (b == null) {
            return;
        }
        if (viewMode == 1) {
            b.recycler.setLayoutManager(new GridLayoutManager(requireContext(), 3));
            b.recycler.setAdapter(gridAdapter);
            b.btnView.setText("文字"); // 按钮显示下一个模式
        } else if (viewMode == 2) {
            b.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            b.recycler.setAdapter(textAdapter);
            b.btnView.setText("列表");
        } else {
            b.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            b.recycler.setAdapter(adapter);
            b.btnView.setText("封面");
        }
        // v3.2：改成「只渲染当前视图」后，换适配器必须重新喂一次数据，
        // 否则新挂载的适配器是空的。首次加载时 allBooks 为空，applyFilters 会直接返回。
        applyFilters();
    }

    /**
     * Room 响应式推送回调（v4.2，评审 #7；v4.4 改两段式渲染）。
     *
     * <p><b>第一段（主线程，立即）</b>：books 数据由推送直接给出，马上更新
     * {@code allBooks} 并 applyFilters——书籍列表先渲染出来，「正在加载书架」
     * 指示在这一步就消失。此前 books 要等字典表补查完才显示，而字典查询在
     * 冷启动时还会被启动期长任务（默认数据补种、自动备份 zip）堵在同一个
     * io 线程后面，加载指示会久转不停。
     *
     * <p><b>第二段（io → 主线程）</b>：字典表（地点/标签/分类/国家/渠道）
     * 补查后填 Spinner 再精筛一次。字典查询失败降级空列表，不影响藏书展示。
     *
     * <p>books 表任何增删改都会触发本回调；没变化时 Room 不推送（零开销）。
     *
     * @param pushed books 表最新全量列表（可能为 null，按空处理）
     */
    private void onBooksPushed(java.util.List<Book> pushed) {
        if (b == null) {
            return;
        }
        // ---- 第一段：books 即到即渲染（主线程） ----
        booksReceived = true; // 首推已到，applyFilters 从此放行（v4.4）
        if (allBooks.isEmpty()) {
            // 只有首屏（本实例还没有任何数据）才收起加载指示，
            // 后续刷新列表里本来就有旧内容，闪一下反而更难看
            b.llLoading.setVisibility(View.GONE);
        }
        allBooks = BookSanitizer.normalizeAll(
                pushed == null ? new ArrayList<>() : pushed);
        // 外部带入的搜索词兜底：先灌搜索框（TextWatcher 会触发一次筛选），再统一 applyFilters
        if (pendingSearchQuery != null) {
            String q = pendingSearchQuery;
            pendingSearchQuery = null;
            b.etSearch.setText(q);
        }
        applyFilters(); // 字典 Spinner 未就绪时自动降级为「不限」（见 applyFilters 注释）

        // ---- 第二段：字典表补查（io 线程） ----
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            // 字典查询独立兜底：失败退回空列表，不影响藏书展示
            List<Location> locs = null;
            List<com.bai.bookshelf.data.entity.Tag> tags = null;
            List<Category> categories = null;
            List<String> countries = null;
            List<String> channels = null;
            try {
                locs = app.db().locationDao().getAll();
            } catch (Exception ignored) {
            }
            try {
                tags = app.db().tagDao().getAll();
            } catch (Exception ignored) {
            }
            try {
                categories = app.db().categoryDao().getAll();
            } catch (Exception ignored) {
            }
            // 国家/渠道去重交给 SQL（见 BookDao）
            try {
                countries = app.db().bookDao().getDistinctCountries();
            } catch (Exception ignored) {
            }
            try {
                channels = app.db().bookDao().getDistinctChannels();
            } catch (Exception ignored) {
            }

            final List<Location> fLocs = locs;
            final List<com.bai.bookshelf.data.entity.Tag> fTags = tags;
            final List<Category> fCategories = categories;
            final List<String> fCountries = countries == null ? new ArrayList<>() : countries;
            final List<String> fChannels = channels == null ? new ArrayList<>() : channels;

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                locations = fLocs == null ? new ArrayList<>() : fLocs;
                tagList = fTags == null ? new ArrayList<>() : fTags;
                categoryList = fCategories == null ? new ArrayList<>() : fCategories;
                channelList = fChannels;
                countryList = fCountries; // 留实例快照，旋转屏重建视图时兜底重放用
                // 各 Spinner 仅在内容变化时重建，避免无谓的全量 notify
                fillCountrySpinner(fCountries);
                fillLocationSpinner();
                fillTagSpinner();
                fillChannelSpinner();
                fillCategorySpinner();
                spinnersFilled = true; // 本视图的 Spinner 都已有数据
                spinnerReady = true;
                applyFilters();
            });
        });
    }

    /**
     * 紧凑版 spinner 适配器，下拉弹层中当前选中项高亮（加粗 + 主题色 + 浅底色）。
     * 闭合态用小字号样式（spinner_item），下拉项在系统默认样式上定制。
     */
    private class HighlightAdapter<T> extends ArrayAdapter<T> {

        private int highlight = 0; // 当前选中项下标（下拉弹层里高亮它）

        HighlightAdapter(int arrayRes) {
            super(requireContext(), R.layout.spinner_item,
                    java.util.Arrays.asList((T[]) getResources().getStringArray(arrayRes)));
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        HighlightAdapter(List<T> items) {
            super(requireContext(), R.layout.spinner_item, items);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        void setHighlight(int position) {
            highlight = position;
        }

        @NonNull
        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            android.widget.TextView tv =
                    (android.widget.TextView) super.getDropDownView(position, convertView, parent);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(14);
            if (position == highlight) {
                // 选中项：加粗 + 主题色文字 + 浅主题底色
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setTextColor(ThemeManager.resolveColor(requireContext(), ThemeManager.ATTR_COLOR_PRIMARY));
                tv.setBackgroundColor(ThemeManager.primaryAlpha(requireContext(), 0x14));
            } else {
                tv.setTypeface(android.graphics.Typeface.DEFAULT);
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.text_main));
                tv.setBackgroundColor(android.graphics.Color.WHITE);
            }
            return tv;
        }
    }

    /**
     * 给 Spinner 填充下拉项，仅在<b>内容真正变化</b>时才重建适配器（v3.2）。
     *
     * <p>原因：ArrayAdapter 每次 setAdapter 都会重建整个 Spinner 的视图树并丢掉选中状态，
     * 而旧版每次回页面都会全量重载 → 6 个 Spinner 无条件重建，
     * 这既是白花开销，也会让用户在筛选状态下切走再回来时看到下拉被"重置"的闪动。
     *
     * <p><b>v3.3 修正</b>：必须同时满足「本视图已填充过」才允许跳过。
     * 否则视图重建（切 Tab / 配置变化）后，缓存内容与全新的空 Spinner 不匹配，
     * 会被误判为"没变化"而跳过填充，导致分类/标签/地点/来源/国家全部空白。
     *
     * @param spinner 目标 Spinner
     * @param names   完整选项（含首项「不限」）
     * @param cache   记录上次内容的缓存字段
     */
    private void fillSpinnerIfChanged(android.widget.Spinner spinner, List<String> names, List<String> cache) {
        // Spinner 还没有适配器时必须填，否则永远是空白
        if (spinner.getAdapter() == null) {
            cache.clear();
            cache.addAll(names);
            spinner.setAdapter(new HighlightAdapter<>(new ArrayList<>(names)));
            // v3.3：视图重建后恢复用户此前的筛选选择（选中文案存在 cache 里）
            restoreSelection(spinner, names, cache);
            return;
        }
        if (spinnersFilled && names.equals(cache)) {
            return; // 本视图已填充过且内容没变，跳过重建
        }
        // 记录用户当前选中的文案，重建后恢复，避免被重置回第一项
        Object selected = spinner.getSelectedItem();
        String selectedText = selected == null ? null : selected.toString();
        int selectedIndex = textIndex(cache, selectedText);

        cache.clear();
        cache.addAll(names);
        spinner.setAdapter(new HighlightAdapter<>(new ArrayList<>(names)));
        if (selectedIndex > 0) {
            spinner.setSelection(selectedIndex, false);
        }
    }

    /** 在列表中找文案对应的下标，找不到返回 -1 */
    private static int textIndex(List<String> items, String text) {
        if (text == null) {
            return -1;
        }
        return items.indexOf(text);
    }

    /**
     * 视图重建后恢复筛选选中项（v3.3）。
     *
     * <p>视图重建时新 Spinner 的选中态是空的，但用户的筛选选择应该保留。
     * 这里用「重建前 cache 里记录的已选项文案」来恢复——cache 在 onCreateView 中被清空了，
     * 所以由调用方在清空前把已选项存到 spinnerSelectionCache。
     */
    private void restoreSelection(android.widget.Spinner spinner, List<String> names, List<String> cache) {
        int idx = textIndex(names, spinnerSelectionCache.get(cache));
        if (idx > 0) {
            spinner.setSelection(idx, false);
        }
    }

    /** 标签筛选下拉：首项「标签」= 不限，其余为标签库中的每个标签 */
    private void fillTagSpinner() {
        List<String> names = new ArrayList<>();
        names.add("标签");
        for (com.bai.bookshelf.data.entity.Tag tag : tagList) {
            names.add(tag.name);
        }
        fillSpinnerIfChanged(b.spTag, names, tagSpinnerCache);
    }

    /** 地点筛选下拉：首项「地点」= 不限，其余为各地点名 */
    private void fillLocationSpinner() {
        List<String> names = new ArrayList<>();
        names.add("地点");
        for (Location loc : locations) {
            names.add(loc.name);
        }
        fillSpinnerIfChanged(b.spLocation, names, locSpinnerCache);
    }

    /** 来源渠道筛选下拉：首项「来源」= 不限，其余为藏书中出现过的渠道 */
    private void fillChannelSpinner() {
        List<String> names = new ArrayList<>();
        names.add("来源");
        names.addAll(channelList);
        fillSpinnerIfChanged(b.spChannel, names, channelSpinnerCache);
    }

    /**
     * 分类筛选下拉（v4.9 两段式）：首项「分类」= 不限，其余只列一级栏目；
     * 选中一级后弹窗展开该组二级（见 maybeShowSubCategoryDialog）。
     */
    private void fillCategorySpinner() {
        categoryEntries.clear();
        List<String> names = new ArrayList<>();
        names.add("分类");
        for (Category top : categoryList) {
            if (top.parentId == null) {
                categoryEntries.add(top);
                names.add(top.name);
            }
        }
        fillSpinnerIfChanged(b.spCategory, names, categorySpinnerCache);
    }

    /**
     * 选中一级后展开该组二级（v4.9）：首项「全部一级」（含所有二级），点二级只筛该二级；
     * 取消 = 按一级全组筛选。无二级的老平铺分类不弹窗。
     */
    private void maybeShowSubCategoryDialog(Category top) {
        List<String> children = new ArrayList<>();
        for (Category c : categoryList) {
            if (c.parentId != null && c.parentId == top.id) {
                children.add(c.name);
            }
        }
        if (children.isEmpty()) {
            catFilterSub = "";
            return;
        }
        String[] items = new String[children.size() + 1];
        items[0] = "全部「" + top.name + "」（含所有二级）";
        for (int i = 0; i < children.size(); i++) {
            items[i + 1] = children.get(i);
        }
        int checked = catFilterSub.isEmpty() ? 0 : children.indexOf(catFilterSub) + 1;
        new AlertDialog.Builder(requireContext())
                .setTitle("「" + top.name + "」下的分类")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    catFilterSub = w == 0 ? "" : children.get(w - 1);
                    if (!catFilterSub.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "分类：" + top.name + "-" + catFilterSub, Toast.LENGTH_SHORT).show();
                    }
                    applyFilters();
                    d.dismiss();
                })
                .setNegativeButton("按一级筛选", (d, w) -> {
                    catFilterSub = "";
                    applyFilters();
                })
                .show();
    }

    /** 国家/地区筛选下拉：首项「国家」= 不限，其余为藏书中出现过的国家/地区 */
    private void fillCountrySpinner(List<String> names) {
        List<String> items = new ArrayList<>();
        items.add("国家");
        items.addAll(names);
        fillSpinnerIfChanged(b.spCountry, items, countrySpinnerCache);
    }

    /** 按搜索词 + 筛选条件 + 排序方式生成展示列表 */
    /** 空 Spinner 的 getSelectedItemPosition() 返回 INVALID_POSITION(-1)，夹到 0（=「不限」/首项） */
    private static int selPos(android.widget.Spinner sp) {
        int pos = sp.getSelectedItemPosition();
        return pos < 0 ? 0 : pos;
    }

    private void applyFilters() {
        // v4.4：本实例还没收到过 books 推送时不渲染（onViewCreated 的
        // applyViewMode 会提前调进来），避免加载指示旁边闪现空态文案
        if (b == null || !booksReceived) {
            return;
        }
        // v4.4：不再因字典 Spinner 未就绪而整体跳过——两段式渲染下 books 推送
        // 到达时先按「关键词 + 藏书/阅读状态 + 排序」渲染列表（这些 Spinner 内容
        // 写死在布局里，始终可用），字典 Spinner 空的时候一律按「不限」处理，
        // 字典补查完成后（spinnerReady 置位）第二段会再精筛一次。
        // 空 Spinner 的 getSelectedItemPosition() 返回 INVALID_POSITION(-1)，
        // 统一夹到 0（=「不限」），避免误触发字典过滤。
        String keyword = b.etSearch.getText().toString().trim().toLowerCase();
        int stockFilter = selPos(b.spStock);     // 0=全部
        int readFilter = selPos(b.spReadFilter); // 0=全部
        int locFilter = selPos(b.spLocation);    // 0=全部
        int tagFilter = selPos(b.spTag);         // 0=全部
        int channelFilter = selPos(b.spChannel); // 0=全部
        int catFilter = selPos(b.spCategory);    // 0=全部
        int sortType = selPos(b.spSort);
        String countryFilter = b.spCountry.getSelectedItemPosition() > 0
                ? String.valueOf(b.spCountry.getSelectedItem()) : "";

        List<Book> result = new ArrayList<>();
        for (Book book : allBooks) {
            // 关键词：命中书名/作者/出版社/ISBN/标签/分类/国家 任一（v2.8 加国家，支持统计页国家分布点击跳转）
            if (!keyword.isEmpty()
                    && !book.title.toLowerCase().contains(keyword)
                    && !book.author.toLowerCase().contains(keyword)
                    && !book.publisher.toLowerCase().contains(keyword)
                    && !book.isbn.contains(keyword)
                    && !book.tags.toLowerCase().contains(keyword)
                    && !book.category.toLowerCase().contains(keyword)
                    && !(book.country != null && book.country.toLowerCase().contains(keyword))) {
                continue;
            }
            // 藏书状态
            if (stockFilter > 0 && book.stockStatus != stockFilter - 1) {
                continue;
            }
            // 阅读状态
            if (readFilter > 0 && book.readStatus != readFilter - 1) {
                continue;
            }
            // 来源渠道
            if (channelFilter > 0 && !book.sourceChannel.equals(channelList.get(channelFilter - 1))) {
                continue;
            }
            // 地点
            if (locFilter > 0) {
                Long want = locations.get(locFilter - 1).id;
                // 注意：Long 包装类型不能用 != 比较
                if (book.locationId == null || !want.equals(book.locationId)) {
                    continue;
                }
            }
            // 标签（精确匹配单个标签，避免「小说」误中「科幻小说」）
            if (tagFilter > 0 && !hasTag(book, tagList.get(tagFilter - 1).name)) {
                continue;
            }
            // 分类（v4.9 两段式）：catFilter 选中一级，catFilterSub 为空 = 全组，
            // 非空 = 只看该二级（同时保留直接挂在一级名上的老数据）
            if (catFilter > 0 && catFilter - 1 < categoryEntries.size()) {
                Category sel = categoryEntries.get(catFilter - 1);
                boolean hit;
                if (!catFilterSub.isEmpty()) {
                    hit = book.category.equals(catFilterSub) || book.category.equals(sel.name);
                } else {
                    hit = book.category.equals(sel.name);
                    if (!hit) {
                        for (Category c : categoryList) {
                            if (c.parentId != null && c.parentId == sel.id
                                    && c.name.equals(book.category)) {
                                hit = true;
                                break;
                            }
                        }
                    }
                }
                if (!hit) {
                    continue;
                }
            }
            // 国家/地区（等值匹配）
            if (!countryFilter.isEmpty() && !countryFilter.equals(
                    book.country == null ? "" : book.country.trim())) {
                continue;
            }
            result.add(book);
        }

        // 排序（0=最新录入，原列表已是按录入时间倒序）
        switch (sortType) {
            case 1: // 按书名
                result.sort((x, y) -> x.title.compareToIgnoreCase(y.title));
                break;
            case 2: // 按版次（解析「2021-03」「2021年3月」等为年月加权值，v4.0 评审 #12）
                result.sort((x, y) -> Double.compare(
                        BookSanitizer.parseDateOrder(x.publishDate),
                        BookSanitizer.parseDateOrder(y.publishDate)));
                break;
            case 3: // 按定价（高→低）
                result.sort((x, y) -> Double.compare(y.listPrice, x.listPrice));
                break;
            case 4: // 按购入日期（近→远；解析不出日期的排最后，v4.0 评审 #12）
                result.sort((x, y) -> Double.compare(
                        BookSanitizer.parseDateOrder(y.purchaseDate),
                        BookSanitizer.parseDateOrder(x.purchaseDate)));
                break;
            case 5: // 按购入价格（高→低）
                result.sort((x, y) -> Double.compare(y.purchasePrice, x.purchasePrice));
                break;
            case 6: // 按豆瓣评分（高→低；无评分排最后）
                result.sort((x, y) -> Double.compare(parseDouban(y), parseDouban(x)));
                break;
            case 7: // 按我的评分（高→低）
                result.sort((x, y) -> Integer.compare(y.rating, x.rating));
                break;
            case 8: // 按字数（多→少；解析「320千字」「28万字」等写法）
                result.sort((x, y) -> Double.compare(BookSanitizer.parseWords(y.wordCount), BookSanitizer.parseWords(x.wordCount)));
                break;
            case 9: // 按书名长度（长→短）
                result.sort((x, y) -> Integer.compare(y.title.length(), x.title.length()));
                break;
            case 10: // 按页数（多→少）
                result.sort((x, y) -> Integer.compare(y.pages, x.pages));
                break;
            default:
                break;
        }

        // 倒序开关：对所有排序方式生效（含默认的最新录入）
        if (sortReversed) {
            Collections.reverse(result);
        }

        // v3.2 优化：只给「当前正在显示」的适配器提交数据。
        // 原先三个适配器每次都 submit（同一份数据渲染三遍），而 RecyclerView 一次只挂载一个，
        // 另两个的渲染纯属浪费——尤其是封面视图每次都要重新触发一遍 Glide 加载。
        // 切视图时 applyViewMode 会调用 applyFilters() 重新提交，所以切换依然即时。
        lastResult = result;
        Map<Long, String> locMap = locationNameMap(); // 只构造一次（原本构造了两次）
        if (viewMode == 1) {
            gridAdapter.submit(result);
        } else if (viewMode == 2) {
            textAdapter.submit(result, locMap);
        } else {
            adapter.submit(result, locMap);
        }
        updateEmptyState(result.isEmpty(), keyword,
                stockFilter + readFilter + locFilter + tagFilter + channelFilter + catFilter > 0
                        || !countryFilter.isEmpty());
    }

    /**
     * 空状态文案（v3.0 修复：此前无论什么原因列表为空，都显示「书架还是空的」，
     * 导致搜索无结果 / 筛选无结果时给出误导性提示）。
     *
     * @param empty       列表是否为空
     * @param keyword     当前搜索词
     * @param hasFilter   是否处于筛选状态（含关键词或任一筛选项非「全部」）
     */
    private void updateEmptyState(boolean empty, String keyword, boolean hasFilter) {
        if (b == null) {
            return;
        }
        b.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            return;
        }
        if (!keyword.isEmpty()) {
            b.tvEmpty.setText("没有找到包含「" + keyword.trim() + "」的书\n换个关键词，或清空筛选条件试试");
        } else if (hasFilter) {
            b.tvEmpty.setText("当前筛选条件下没有藏书\n试试把筛选条件改回「全部」");
        } else {
            b.tvEmpty.setText(getString(R.string.empty_shelf));
        }
    }

    /** 豆瓣评分是手动填的字符串，解析失败按 0 处理 */
    private double parseDouban(Book book) {
        try {
            return Double.parseDouble(book.doubanRating.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<Long, String> locationNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Location loc : locations) {
            map.put(loc.id, loc.name);
        }
        return map;
    }

    /** 判断一本书是否挂了某个标签（逗号分隔精确匹配） */
    private boolean hasTag(Book book, String tag) {
        for (String t : book.tags.split(",")) {
            if (t.trim().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 录入方式选择弹窗 */
    private void showAddOptions() {
        String[] options = {"扫码录入", "手动输入 ISBN", "完全手动录入"};
        new AlertDialog.Builder(requireContext())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        launchAndRefresh(new Intent(requireContext(), ScanActivity.class));
                    } else if (which == 1) {
                        showIsbnInput();
                    } else {
                        launchAndRefresh(new Intent(requireContext(), BookEditActivity.class));
                    }
                })
                .show();
    }

    /** 手动输入 ISBN 的弹窗 */
    private void showIsbnInput() {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        edit.setHint("输入 10 或 13 位 ISBN");
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(requireContext())
                .setTitle("按 ISBN 查询")
                .setView(db.getRoot())
                .setPositiveButton("查询", (d, w) -> {
                    String isbn = edit.getText().toString().trim();
                    if (isbn.length() < 10) {
                        Toast.makeText(requireContext(), "ISBN 位数不对", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AddBookHelper.byIsbn(requireActivity(), isbn);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
