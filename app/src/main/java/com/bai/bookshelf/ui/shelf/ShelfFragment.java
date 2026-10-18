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
    private List<String> channelList = new ArrayList<>(); // 藏书里出现过的来源渠道（去重）
    private List<String> countryList = new ArrayList<>(); // 藏书里出现过的国家（去重，v3.5：留实例快照供视图重建后重放）
    private String pendingSearchQuery;                   // 外部带入的搜索词（视图未就绪时缓存）
    private boolean spinnerReady = false; // 防止初始化 spinner 时触发筛选
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
        b.spCategory.setOnItemSelectedListener(filterListener);
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
     * Room 响应式推送回调（v4.2，评审 #7）：books 表任何增删改都会触发，
     * 注册时也会立即推送一次当前数据（冷启动首次加载 / 旋转屏恢复）。
     *
     * <p>回调在主线程，books 数据已由推送直接给出（不再查库）；字典表
     * （地点/标签/分类/国家/渠道）在 io 线程补查后一起刷 UI。原先的
     * 「指纹比对 + RESULT_OK 标脏 + 视图重建补偿」整套状态机已删除：
     * 没变化时 Room 不推送（零开销），变化时推送的 books 就是最新数据。
     *
     * @param pushed books 表最新全量列表（可能为 null，按空处理）
     */
    private void onBooksPushed(java.util.List<Book> pushed) {
        if (b == null) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        // 只有「首次加载」（还没有任何数据可显示）才显示加载指示，
        // 后续刷新时列表里本来就有旧内容，闪一下反而更难看
        final boolean showLoading = allBooks.isEmpty();
        if (showLoading) {
            b.llLoading.setVisibility(View.VISIBLE);
        }
        final List<Book> books = BookSanitizer.normalizeAll(
                pushed == null ? new ArrayList<>() : pushed);
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
                if (showLoading) {
                    b.llLoading.setVisibility(View.GONE);
                }
                allBooks = books;
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
                // 外部带入的搜索词兜底：数据刚加载完，此时视图与数据都就绪，灌入最稳妥
                if (pendingSearchQuery != null) {
                    String q = pendingSearchQuery;
                    pendingSearchQuery = null;
                    b.etSearch.setText(q);
                }
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

    /** 分类筛选下拉：首项「分类」= 不限，其余为分类库中的每个分类 */
    private void fillCategorySpinner() {
        List<String> names = new ArrayList<>();
        names.add("分类");
        for (Category cat : categoryList) {
            names.add(cat.name);
        }
        fillSpinnerIfChanged(b.spCategory, names, categorySpinnerCache);
    }

    /** 国家/地区筛选下拉：首项「国家」= 不限，其余为藏书中出现过的国家/地区 */
    private void fillCountrySpinner(List<String> names) {
        List<String> items = new ArrayList<>();
        items.add("国家");
        items.addAll(names);
        fillSpinnerIfChanged(b.spCountry, items, countrySpinnerCache);
    }

    /** 按搜索词 + 筛选条件 + 排序方式生成展示列表 */
    private void applyFilters() {
        if (b == null) {
            return;
        }
        // v3.2：applyViewMode() 会在视图挂载时就调用这里，此时筛选 Spinner 还没填数据。
        // 未就绪时 getSelectedItem() 可能返回 INVALID_POSITION 或抛异常，直接跳过；
        // 数据由响应式推送完成后会再调一次，那时才真正筛选。
        if (!spinnerReady) {
            return;
        }
        String keyword = b.etSearch.getText().toString().trim().toLowerCase();
        int stockFilter = b.spStock.getSelectedItemPosition();     // 0=全部
        int readFilter = b.spReadFilter.getSelectedItemPosition(); // 0=全部
        int locFilter = b.spLocation.getSelectedItemPosition();    // 0=全部
        int tagFilter = b.spTag.getSelectedItemPosition();         // 0=全部
        int channelFilter = b.spChannel.getSelectedItemPosition(); // 0=全部
        int catFilter = b.spCategory.getSelectedItemPosition();    // 0=全部
        int sortType = b.spSort.getSelectedItemPosition();
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
            // 分类（单值字段，等值匹配）
            if (catFilter > 0 && !book.category.equals(categoryList.get(catFilter - 1).name)) {
                continue;
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
