package com.bai.bookshelf.ui.shelf;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.databinding.SheetAddBookBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 书架页：藏书列表 + 搜索 + 状态/地点筛选 + 排序 + 录入口。
 * 支持列表/封面两种视图；个人藏书量小，筛选排序在内存中完成。
 */
public class ShelfFragment extends Fragment {

    private FragmentShelfBinding b;
    private BookAdapter adapter;
    private BookGridAdapter gridAdapter;
    private BookAdapter textAdapter; // 纯文字列表（无封面）
    /** v4.18：网格模式的间距分配器（切换视图时需要移除，避免叠加） */
    private ShelfGridSpacing gridSpacing;

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

    /** v4.10：行内展开的分类 chips 当前所属一级 id（-1 = 未展开） */
    private long chipCategoryTopId = -1;

    /** v4.10 两段式来源筛选：选中的来源大类（-1 = 不限）与二级渠道名（空 = 整组） */
    private int channelTypeSel = -1;
    private String channelSubSel = "";

    /** v4.10：行内展开的来源 chips 当前大类（-1 = 未展开） */
    private int chipChannelType = -1;

    /** 上一次来源下拉事件的位置（首个事件来自程序填充，不展开） */
    private int lastChannelSpinnerPos = -1;

    /** 来源渠道库全量（按 ownType 分组，v4.10） */
    private List<com.bai.bookshelf.data.entity.SourceChannel> channelRowsAll = new ArrayList<>();
    private List<String> channelList = new ArrayList<>(); // 藏书里出现过的来源渠道（去重）
    private List<String> countryList = new ArrayList<>(); // 藏书里出现过的国家（去重，v3.5：留实例快照供视图重建后重放）
    private String pendingSearchQuery;                   // 外部带入的搜索词（视图未就绪时缓存）
    private boolean spinnerReady = false; // 防止初始化 spinner 时触发筛选
    // v4.4：是否已收到本实例的第一次 books 推送。onViewCreated 里 applyViewMode()
    // 会同步调一次 applyFilters，此时首推未到、allBooks 为空，不挡住会在
    // 加载指示旁边闪现「空书架」文案；首推到达后置位，之后所有渲染正常放行
    private boolean booksReceived = false;
    private boolean sortReversed = false; // 排序倒序开关
    private int sortIndex = 0;            // 排序方式（arrays.xml sort_entries 下标）
    private int viewMode = 0;             // 视图模式：0=列表 1=封面网格 2=纯文字列表
    private android.animation.ObjectAnimator skeletonAnimator; // 首屏骨架屏呼吸动画
    private boolean selectionMode = false; // 多选模式（长按进入）
    private boolean shelfEmpty = false;    // 当前结果为空（空态时隐藏 FAB）
    // v4.36：购入 / 录入日期区间筛选（按日，null = 不限）
    private DayRange purchaseRange;
    private DayRange entryRange;
    private TextView rowPurchaseRange;
    // v4.38：价格区间（按定价），null = 不限
    private Double priceMin;
    private Double priceMax;
    private TextView rowPriceRange;
    private TextView rowEntryRange;
    // v4.53：系列筛选（空 = 不限）
    private String seriesFilter = "";
    private TextView rowSeriesFilter;
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();
    private List<Book> lastResult = new ArrayList<>(); // 当前筛选结果（全选用）

    // v4.41：搜索索引（bookId → 归一化原文 + 拼音 + 首字母）与 150ms 输入防抖
    private final java.util.Map<Long, String> searchIndex = new java.util.HashMap<>();
    private final android.os.Handler searchDebounce =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchApply = this::applyFilters;

    // v4.42：智能书架（在读/借出中/超期…）与未归还记录
    // v4.55：判定与计数收敛到 util/SmartShelf，书架只负责 UI
    private static final String[] SMART_NAMES = com.bai.bookshelf.util.SmartShelf.NAMES;
    private final TextView[] smartChips = new TextView[SMART_NAMES.length];
    private int smartShelf = -1;
    private final java.util.Map<Long, com.bai.bookshelf.data.entity.BorrowRecord> openLoanByBook =
            new java.util.HashMap<>();

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
        // v4.18：应用 targetSdk 36，在 Android 15+ 强制 edge-to-edge，
        // 内容会画到状态栏之下。v4.17 把顶部工具栏换成自定义 LinearLayout 后，
        // 丢失了 MaterialToolbar 自带的自动避让能力，搜索框被状态栏压住点不到。
        // 这里显式给头部加状态栏内边距：头部自身是主色背景，
        // 加高后状态栏与头部连成一片（与 v4.16 观感一致），内容下移不被遮挡。
        InsetsHelper.applyTopInset(b.header);

        // v4.4：首次加载指示改在视图创建时显示（此实例还没有任何数据可显示时），
        // 两段式渲染下 books 一到就隐藏（见 onBooksPushed 第一段）——
        // 指示只覆盖「Room 首推」的空窗，不再被启动期长任务拖长
        if (allBooks.isEmpty()) {
            showLoading(true);
        }
        // 列表 / 封面 / 文字 三适配器：点击在多选模式下是切换选中，否则进详情页
        BookAdapter.OnBookClick openDetail = book -> {
            if (selectionMode) {
                toggleSelect(book);
                return;
            }
            // v4.39：不再走封面 shared element 过渡。实机计时显示：共享元素要和
            // 系统做一次位置握手，详情首帧被硬生生推后 90ms 左右（270ms vs 180ms），
            // 点书的手感明显发滞。改用系统默认的页面动画，首帧直接可见。
            Intent intent = new Intent(requireContext(), BookDetailActivity.class);
            intent.putExtra(BookDetailActivity.EXTRA_BOOK, book);
            startActivity(intent);
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
        b.btnSelEdit.setOnClickListener(v -> showBatchEdit());
        b.btnSelShare.setOnClickListener(v -> shareSelected());
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
            updateFilterActionIcons();
            applyFilters();
        });
        // 排序方式：行尾图标改为弹出单选项，省掉一个下拉框
        b.btnSort.setOnClickListener(v -> showSortPicker());
        // 视图切换：点开三项小菜单，不再单键循环（避免把图标当成状态标签）
        b.btnView.setOnClickListener(v -> showViewPicker());
        updateFilterActionIcons();

        // 筛选 spinner：藏书状态 / 阅读状态 / 排序（地点/标签/渠道/分类在数据加载后填充）
        // 全部使用紧凑闭合样式（小字号小内边距），下拉弹层保持系统默认
        b.spStock.setAdapter(new HighlightAdapter<>(R.array.filter_stock_entries));
        b.spReadFilter.setAdapter(new HighlightAdapter<>(R.array.filter_read_entries));

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
        b.spChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent.getAdapter() instanceof HighlightAdapter) {
                    ((HighlightAdapter<?>) parent.getAdapter()).setHighlight(position);
                }
                recordSelection(parent, position);
                // 首个事件来自程序填充（适配器刚装好），不当作用户选择
                boolean userDriven = lastChannelSpinnerPos >= 0;
                lastChannelSpinnerPos = position;
                if (!spinnerReady) {
                    return;
                }
                if (position > 0 && userDriven) {
                    channelTypeSel = position - 1; // ownType 0/1/2
                    channelSubSel = "";            // 换了大类，先回到整组
                    applyFilters();
                    showChannelChips(channelTypeSel);
                } else {
                    if (position == 0) {
                        channelTypeSel = -1;
                        channelSubSel = "";
                        showChannelChips(-1);
                    }
                    applyFilters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        // 分类下拉（v4.10）：在通用筛选行为之上，选中一级后在下方行内展开二级 chips
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
                    showCategoryChips(categoryEntries.get(position - 1));
                } else {
                    if (position == 0) {
                        catFilterSub = "";
                        showCategoryChips(null);
                    }
                    applyFilters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        b.spCountry.setOnItemSelectedListener(filterListener);
        setupQuickFilters();
        setupSmartShelves();

        // 搜索框：边输边筛
        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // v4.41：输入防抖 150ms，连续打字时不在每个字符上重排整架
                searchDebounce.removeCallbacks(searchApply);
                searchDebounce.postDelayed(searchApply, 150);
            }

            @Override
            public void afterTextChanged(Editable s) {
                // v4.16：有内容时显示右侧清除按钮
                if (b != null) {
                    b.btnClearSearch.setVisibility(
                            s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
        });
        // 清除按钮：一键清空搜索词（立即生效，不等防抖）
        b.btnClearSearch.setOnClickListener(v -> {
            searchDebounce.removeCallbacks(searchApply);
            b.etSearch.setText("");
            applyFilters();
            b.etSearch.requestFocus();
        });

        // 录入方式选择
        b.btnAdd.setOnClickListener(v -> showAddOptions());
        // 空态主按钮：直接进扫码
        b.btnEmptyAdd.setOnClickListener(v ->
                launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        // 扫码快捷入口
        b.fabScan.setOnClickListener(v -> launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        // 向上滚动时收起 FAB，向下滚动时再出现（只移动，不做弹跳）
        b.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (selectionMode || shelfEmpty) {
                    return;
                }
                if (dy > 4) {
                    b.fabScan.hide();
                } else if (dy < -4) {
                    b.fabScan.show();
                }
            }
        });

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
        if (b != null) {
            b.recycler.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
        if (b != null) {
            b.fabScan.hide();
        }
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
        if (b != null && !shelfEmpty) {
            b.fabScan.show();
        }
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

    /** v4.52：把多选的书分享成文字书单 */
    private void shareSelected() {
        if (selectedIds.isEmpty()) {
            return;
        }
        java.util.List<Book> chosen = new java.util.ArrayList<>();
        for (Book bk : allBooks) {
            if (selectedIds.contains(bk.id)) {
                chosen.add(bk);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("我的书单 · 共 ").append(chosen.size()).append(" 本").append(nl).append(nl);
        int i = 1;
        for (Book bk : chosen) {
            sb.append(i++).append(". 《")
                    .append(com.bai.bookshelf.util.BookText.bareTitle(bk.title)).append("》");
            if (bk.author != null && !bk.author.isEmpty()) {
                sb.append(" · ").append(bk.author);
            }
            if (bk.rating > 0) {
                sb.append("  ★").append(bk.rating);
            }
            sb.append(nl);
        }
        sb.append(nl).append("—— 来自「小书房」");
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        startActivity(android.content.Intent.createChooser(send, "分享书单"));
    }

    /** 批量删除确认 */
    private void confirmBatchDelete() {        int count = selectedIds.size();
        if (count == 0) {
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("批量删除")
                .setMessage("确定删除已选的 " + count + " 本书吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    java.util.Set<Long> ids = new java.util.HashSet<>(selectedIds);
                    app.io(() -> {
                        // v4.23：级联清理收敛到 BookCascade（原手写版漏借出记录与封面文件）
                        // 需要封面路径 → 先按 id 查书再删
                        for (Long id : ids) {
                            com.bai.bookshelf.data.entity.Book bk = app.db().bookDao().getById(id);
                            com.bai.bookshelf.util.BookCascade.deleteBook(
                                    app, id, bk == null ? null : bk.coverUrl);
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

    /** 批量设置（v4.41）：地点 / 标签 / 阅读状态，一次改多本 */
    private void showBatchEdit() {
        if (selectedIds.isEmpty()) {
            return;
        }
        String[] options = {"设置存放地点", "添加标签", "设置阅读状态"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("批量设置（已选 " + selectedIds.size() + " 本）")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showBatchLocation();
                    } else if (which == 1) {
                        showBatchTag();
                    } else {
                        showBatchRead();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchLocation() {
        String[] names = new String[locations.size() + 1];
        names[0] = "未设置";
        for (int i = 0; i < locations.size(); i++) {
            names[i + 1] = locations.get(i).name;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("设置存放地点")
                .setItems(names, (d, which) -> {
                    Long locationId = which == 0 ? null : locations.get(which - 1).id;
                    applyBatch(book -> book.locationId = locationId,
                            "已更新 " + selectedIds.size() + " 本的存放地点");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchTag() {
        DialogInputBinding input = DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("要添加的标签（如：待读、儿童）");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("批量添加标签")
                .setView(input.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String tag = input.edit.getText().toString().trim();
                    if (tag.isEmpty()) {
                        return;
                    }
                    ensureTagInLibrary(tag);
                    applyBatch(book -> appendTag(book, tag),
                            "已给 " + selectedIds.size() + " 本加上「" + tag + "」");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchRead() {
        String[] names = {"未读", "在读", "已读"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("设置阅读状态")
                .setItems(names, (d, which) ->
                        applyBatch(book -> book.readStatus = which,
                                "已把 " + selectedIds.size() + " 本设为「" + names[which] + "」"))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 标签库没有就补一条，否则筛选下拉里找不到新标签 */
    private void ensureTagInLibrary(String tag) {
        for (com.bai.bookshelf.data.entity.Tag t : tagList) {
            if (t.name.equals(tag)) {
                return;
            }
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            com.bai.bookshelf.data.entity.Tag t = new com.bai.bookshelf.data.entity.Tag();
            t.name = tag;
            t.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
            app.db().tagDao().insert(t);
        });
    }

    private static void appendTag(Book book, String tag) {
        for (String t : book.tags.split(",")) {
            if (t.trim().equals(tag)) {
                return;
            }
        }
        book.tags = book.tags.trim().isEmpty() ? tag : book.tags + "," + tag;
    }

    /** 对已选书籍批量应用一处修改；写完由 Room 推送刷新书架 */
    private void applyBatch(java.util.function.Consumer<Book> change, String message) {
        final List<Book> targets = new ArrayList<>();
        for (Book book : allBooks) {
            if (selectedIds.contains(book.id)) {
                targets.add(book);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            for (Book book : targets) {
                change.accept(book);
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
            }
            app.ui(() -> {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                exitSelection();
            });
        });
    }

    /** 切换 RecyclerView 的视图模式（列表 / 封面网格 / 纯文字 三态循环） */
    private void applyViewMode() {
        if (b == null) {
            return;
        }
        // v4.18：切换视图前清掉旧的间距分配器，避免叠加导致间距越来越大
        b.recycler.removeItemDecoration(gridSpacing);
        gridSpacing = null;
        if (viewMode == 1) {
            // v4.16：网格列数随屏幕宽度自适应（values / sw600dp / sw840dp 覆盖）
            int span = getResources().getInteger(R.integer.shelf_grid_span);
            b.recycler.setLayoutManager(new GridLayoutManager(requireContext(), span));
            b.recycler.setAdapter(gridAdapter);
            // v4.18：间距统一由分配器控制（item 自身不再带 margin）
            // v4.19：页面左右留白改由 RecyclerView 的 padding 承担，
            // 分配器只管列间距且每列左右对称 —— 这样 GridLayoutManager
            // 平分出来的每一列内容宽度完全相同，封面比例才不会参差不齐。
            float density = getResources().getDisplayMetrics().density;
            int spacingX = Math.round(6 * density);
            int spacingY = Math.round(10 * density);
            int edge = getResources().getDimensionPixelSize(R.dimen.page_padding);
            setRecyclerEdgePadding(edge);
            gridSpacing = new ShelfGridSpacing(span, spacingX, spacingY);
            b.recycler.addItemDecoration(gridSpacing);
        } else if (viewMode == 2) {
            b.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            b.recycler.setAdapter(textAdapter);
            setRecyclerEdgePadding(0);
        } else {
            b.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            b.recycler.setAdapter(adapter);
            setRecyclerEdgePadding(0);
        }
        // v3.2：改成「只渲染当前视图」后，换适配器必须重新喂一次数据，
        // 否则新挂载的适配器是空的。首次加载时 allBooks 为空，applyFilters 会直接返回。
        updateFilterActionIcons();
        if (b.llLoading.getVisibility() == View.VISIBLE) {
            showLoading(true); // 骨架屏跟随视图：网格用九宫格，列表用行
        }
        applyFilters();
    }

    /**
     * 设置列表左右留白（网格模式用；列表模式由 item 自带 marginHorizontal 处理）。
     *
     * <p>v4.19：网格模式下把页面留白放到 RecyclerView 的 padding 上，
     * 让 GridLayoutManager 在「扣掉留白后的宽度」里平分列，每列宽度才一致。
     */
    private void setRecyclerEdgePadding(int edgePx) {
        if (b == null || b.recycler == null) {
            return;
        }
        b.recycler.setPadding(edgePx, b.recycler.getPaddingTop(), edgePx, b.recycler.getPaddingBottom());
    }

    /** 行尾三个轻量图标的当前状态（排序激活 / 倒序激活 / 当前视图） */
    private void updateFilterActionIcons() {
        if (b == null) {
            return;
        }
        android.content.Context ctx = requireContext();
        int active = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int inactive = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        String[] sortNames = getResources().getStringArray(R.array.sort_entries);
        int safeSort = Math.max(0, Math.min(sortIndex, sortNames.length - 1));

        b.btnSort.setImageTintList(ColorStateList.valueOf(sortIndex > 0 ? active : inactive));
        b.btnSort.setContentDescription("排序：" + sortNames[safeSort] + "，点击更改");

        b.btnSortReverse.setImageTintList(ColorStateList.valueOf(sortReversed ? active : inactive));
        b.btnSortReverse.setBackground(sortReversed
                ? ContextCompat.getDrawable(ctx, R.drawable.bg_icon_button_active)
                : borderlessBackground());
        b.btnSortReverse.setContentDescription(sortReversed ? "已开启倒序，点击关闭" : "倒序，点击开启");

        int icon;
        String viewName;
        if (viewMode == 1) {
            icon = R.drawable.ic_view_grid;
            viewName = "封面";
        } else if (viewMode == 2) {
            icon = R.drawable.ic_view_text;
            viewName = "文字";
        } else {
            icon = R.drawable.ic_view_list;
            viewName = "列表";
        }
        b.btnView.setImageResource(icon);
        b.btnView.setContentDescription("当前视图：" + viewName + "，点击切换");
    }

    private Drawable borderlessBackground() {
        TypedValue tv = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            return ContextCompat.getDrawable(requireContext(), tv.resourceId);
        }
        return null;
    }

    /** 排序方式选择（替代原来的下拉框） */
    private void showSortPicker() {
        String[] sortNames = getResources().getStringArray(R.array.sort_entries);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("排序方式")
                .setSingleChoiceItems(sortNames, sortIndex, (dialog, which) -> {
                    sortIndex = which;
                    updateFilterActionIcons();
                    applyFilters();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 视图三项小菜单（v4.26，替代单键三态循环） */
    private void showViewPicker() {
        String[] views = {"列表", "封面", "文字"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("视图")
                .setSingleChoiceItems(views, viewMode, (dialog, which) -> {
                    dialog.dismiss();
                    if (viewMode != which) {
                        viewMode = which;
                        requireContext().getSharedPreferences("ui_prefs", 0)
                                .edit().putInt("shelf_view_mode", viewMode).apply();
                        applyViewMode();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 高频 4 个胶囊 + 更多面板；筛选弹层统一走主题圆角弹窗 */
    private void setupQuickFilters() {
        bindPillFilter(b.spCategory, "分类");
        bindPillFilter(b.spTag, "标签");
        bindPillFilter(b.spStock, "状态");
        bindPillFilter(b.spReadFilter, "阅读");
        b.btnMoreFilters.setOnClickListener(v -> showMoreFilters());
        b.hsvFilters.setOnScrollChangeListener((v, sx, sy, osx, osy) -> updateFilterFade());
        b.hsvFilters.post(this::updateFilterFade);
        updateMoreFilterPill();
    }

    /** 胶囊点击改为统一单选弹窗，阻止 Spinner 自带的系统方角弹层 */
    private void bindPillFilter(android.widget.Spinner spinner, String label) {
        spinner.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showFilterChoice(spinner, label, null);
            }
            return true;
        });
    }

    private void updateFilterFade() {
        if (b == null) {
            return;
        }
        b.vFilterFade.setVisibility(
                b.hsvFilters.canScrollHorizontally(1) ? View.VISIBLE : View.GONE);
    }

    /** 更多筛选：地点 / 来源 / 国家 */
    private void showMoreFilters() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(4), pad, dp(4));
        buildMoreFilterRow(root, "地点", b.spLocation);
        buildMoreFilterRow(root, "渠道", b.spChannel);
        buildMoreFilterRow(root, "国家/地区", b.spCountry);
        java.util.List<String> seriesNames =
                com.bai.bookshelf.util.BookSeries.names(allBooks);
        if (!seriesNames.isEmpty()) {
            buildSeriesFilterRow(root, seriesNames);
        }
        buildPriceFilterRow(root);
        buildDateFilterRow(root, "购入日期", true);
        buildDateFilterRow(root, "录入日期", false);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("更多筛选")
                .setView(root)
                // v4.40：左下角一键清空全部筛选（快捷胶囊 + 地点/渠道/国家/价格/两个日期）
                .setNeutralButton("清除全部筛选", (d, w) -> clearAllFilters())
                .setNegativeButton("完成", null)
                .show();
    }

    /** 一键清除全部筛选条件（搜索关键词不动，它有自己的清除按钮） */
    private void clearAllFilters() {
        purchaseRange = null;
        entryRange = null;
        priceMin = null;
        priceMax = null;
        seriesFilter = "";
        smartShelf = -1;
        channelTypeSel = -1;
        channelSubSel = "";
        catFilterSub = "";
        if (rowPurchaseRange != null) {
            rowPurchaseRange.setText(dateRowText("购入日期", null));
        }
        if (rowEntryRange != null) {
            rowEntryRange.setText(dateRowText("录入日期", null));
        }
        if (rowPriceRange != null) {
            rowPriceRange.setText(priceRowText());
        }
        if (rowSeriesFilter != null) {
            rowSeriesFilter.setText(seriesFilterText());
        }
        b.spCategory.setSelection(0);
        b.spTag.setSelection(0);
        b.spStock.setSelection(0);
        b.spReadFilter.setSelection(0);
        b.spLocation.setSelection(0);
        b.spChannel.setSelection(0);
        b.spCountry.setSelection(0);
        showCategoryChips(null);
        showChannelChips(-1);
        updateMoreFilterPill();
        applyFilters();
    }

    private TextView buildMoreFilterRow(LinearLayout parent, String label,
                                        android.widget.Spinner spinner) {
        TextView row = new TextView(requireContext());
        row.setText(moreFilterText(label, spinner));
        row.setTextSize(15);
        row.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> showFilterChoice(spinner, label, row));
        parent.addView(row);
        return row;
    }

    private String moreFilterText(String label, android.widget.Spinner spinner) {
        boolean none = selPos(spinner) <= 0;
        Object item = spinner.getSelectedItem();
        String value = none || item == null ? "不限" : item.toString();
        return label + "：" + value;
    }

    // ======================= 系列筛选（v4.53） =======================

    private void buildSeriesFilterRow(LinearLayout parent, java.util.List<String> names) {
        TextView row = new TextView(requireContext());
        row.setText(seriesFilterText());
        row.setTextSize(15);
        row.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> showSeriesFilterDialog(row, names));
        rowSeriesFilter = row;
        parent.addView(row);
    }

    private String seriesFilterText() {
        return "系列：" + (seriesFilter.isEmpty() ? "不限" : seriesFilter);
    }

    private void showSeriesFilterDialog(TextView row, java.util.List<String> names) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Book book : allBooks) {
            String name = com.bai.bookshelf.util.BookSeries.name(book);
            if (name.isEmpty()) {
                continue;
            }
            Integer old = counts.get(name);
            counts.put(name, old == null ? 1 : old + 1);
        }
        String[] items = new String[names.size() + 1];
        items[0] = "不限";
        int checked = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Integer count = counts.get(name);
            items[i + 1] = name + (count == null ? "" : "（" + count + " 本）");
            if (name.equalsIgnoreCase(seriesFilter)) {
                checked = i + 1;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("按系列筛选")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    seriesFilter = which == 0 ? "" : names.get(which - 1);
                    row.setText(seriesFilterText());
                    d.dismiss();
                    updateMoreFilterPill();
                    applyFilters();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ======================= 价格区间筛选（v4.38，按定价） =======================

    private void buildPriceFilterRow(LinearLayout parent) {
        TextView row = new TextView(requireContext());
        row.setTextSize(15);
        row.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setText(priceRowText());
        row.setOnClickListener(v -> showPriceFilterDialog(row));
        rowPriceRange = row;
        parent.addView(row);
    }

    private String priceRowText() {
        if (priceMin == null && priceMax == null) {
            return "价格：不限";
        }
        String from = priceMin == null ? "不限" : fmtPrice(priceMin);
        String to = priceMax == null ? "不限" : fmtPrice(priceMax);
        return "价格：" + from + " — " + to + " 元";
    }

    private static String fmtPrice(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 价格区间弹窗：最低价 / 最高价两个输入框，留空 = 不限 */
    private void showPriceFilterDialog(TextView row) {
        android.content.Context ctx = requireContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(4));

        com.google.android.material.textfield.TextInputLayout minTil =
                new com.google.android.material.textfield.TextInputLayout(ctx);
        minTil.setHint("最低价（元）");
        com.google.android.material.textfield.TextInputEditText minEdit =
                new com.google.android.material.textfield.TextInputEditText(ctx);
        minEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        minEdit.setSingleLine(true);
        if (priceMin != null) {
            minEdit.setText(fmtPrice(priceMin));
        }
        minTil.addView(minEdit);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(8);
        root.addView(minTil, lp1);

        com.google.android.material.textfield.TextInputLayout maxTil =
                new com.google.android.material.textfield.TextInputLayout(ctx);
        maxTil.setHint("最高价（元）");
        com.google.android.material.textfield.TextInputEditText maxEdit =
                new com.google.android.material.textfield.TextInputEditText(ctx);
        maxEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxEdit.setSingleLine(true);
        if (priceMax != null) {
            maxEdit.setText(fmtPrice(priceMax));
        }
        maxTil.addView(maxEdit);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        root.addView(maxTil, lp2);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("价格（按定价）")
                .setView(root)
                .setNeutralButton("清除", (d, w) -> {
                    priceMin = null;
                    priceMax = null;
                    row.setText(priceRowText());
                    updateMoreFilterPill();
                    applyFilters();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("应用", (d, w) -> {
                    Double min = parsePrice(minEdit.getText().toString());
                    Double max = parsePrice(maxEdit.getText().toString());
                    if (min != null && max != null && min > max) {
                        Toast.makeText(ctx, "最低价高于最高价", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    priceMin = min;
                    priceMax = max;
                    row.setText(priceRowText());
                    updateMoreFilterPill();
                    applyFilters();
                })
                .show();
    }

    private static Double parsePrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ======================= 日期区间筛选（v4.37：按日 + 年/月/日滚轮） =======================

    /** 按日的日期区间；key 为 yyyyMMdd */
    private static final class DayRange {
        final int startKey, endKey;

        DayRange(int startKey, int endKey) {
            this.startKey = startKey;
            this.endKey = endKey;
        }

        static int keyOf(int year, int month, int day) {
            return year * 10000 + month * 100 + day;
        }

        String label() {
            return fmt(startKey) + " — " + fmt(endKey);
        }

        private static String fmt(int key) {
            return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                    key / 10000, key / 100 % 100, key % 100);
        }
    }

    private static boolean inRange(int startKey, int endKey, DayRange range) {
        return endKey >= range.startKey && startKey <= range.endKey;
    }

    private String dateRowText(String label, DayRange range) {
        return label + "：" + (range == null ? "不限" : range.label());
    }

    private void buildDateFilterRow(LinearLayout parent, String label, boolean purchase) {
        TextView row = new TextView(requireContext());
        row.setTextSize(15);
        row.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setText(dateRowText(label, purchase ? purchaseRange : entryRange));
        row.setOnClickListener(v -> showDateFilterDialog(label, purchase, row));
        if (purchase) {
            rowPurchaseRange = row;
        } else {
            rowEntryRange = row;
        }
        parent.addView(row);
    }

    /** 日期区间选择：直接开自绘日历（点开始 / 点结束，或一键预设） */
    private void showDateFilterDialog(String label, boolean purchase, TextView row) {
        DayRange cur = purchase ? purchaseRange : entryRange;
        com.bai.bookshelf.ui.widget.CalendarPicker.showRange(
                requireActivity(), label,
                cur == null ? 0 : cur.startKey,
                cur == null ? 0 : cur.endKey,
                (startKey, endKey) -> {
                    DayRange range = new DayRange(startKey, endKey);
                    if (purchase) {
                        purchaseRange = range;
                    } else {
                        entryRange = range;
                    }
                    row.setText(dateRowText(label, range));
                    updateMoreFilterPill();
                    applyFilters();
                },
                () -> {
                    if (purchase) {
                        purchaseRange = null;
                    } else {
                        entryRange = null;
                    }
                    row.setText(dateRowText(label, null));
                    updateMoreFilterPill();
                    applyFilters();
                });
    }

    /** 统一圆角单选弹窗：用于所有筛选胶囊与更多筛选 */
    private void showFilterChoice(android.widget.Spinner spinner, String label, TextView row) {
        android.widget.Adapter adapter = spinner.getAdapter();
        if (adapter == null || adapter.getCount() == 0) {
            Toast.makeText(requireContext(), "筛选数据还在加载…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = new String[adapter.getCount()];
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            items[i] = item == null ? "" : item.toString();
        }
        if (items.length > 0) {
            // 首项在胶囊关闭态兼作栏目名（分类/标签/…），弹窗里显示为「不限」
            items[0] = "不限";
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(label)
                .setSingleChoiceItems(items, Math.max(0, spinner.getSelectedItemPosition()),
                        (dialog, which) -> {
                            dialog.dismiss();
                            spinner.setSelection(which);
                            // 兜底：隐藏的额外 Spinner 未完成 layout 时可能不派发 onItemSelected
                            if (spinner == b.spChannel) {
                                channelTypeSel = which <= 0 ? -1 : which - 1;
                                channelSubSel = "";
                                showChannelChips(channelTypeSel);
                            }
                            if (row != null) {
                                row.setText(moreFilterText(label, spinner));
                            }
                            updateMoreFilterPill();
                            applyFilters();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 更多胶囊：有额外筛选时显示角标数量并切成选中态 */
    private void updateMoreFilterPill() {
        if (b == null) {
            return;
        }
        int active = (selPos(b.spLocation) > 0 ? 1 : 0)
                + (selPos(b.spChannel) > 0 ? 1 : 0)
                + (selPos(b.spCountry) > 0 ? 1 : 0)
                + (!seriesFilter.isEmpty() ? 1 : 0)
                + (purchaseRange != null ? 1 : 0)
                + (entryRange != null ? 1 : 0)
                + (priceMin != null || priceMax != null ? 1 : 0);
        boolean on = active > 0;
        b.tvMoreFilters.setText("更多");
        b.tvMoreFilters.setBackgroundResource(on
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
        int textColor = on
                ? ThemeManager.resolveColor(requireContext(), ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(requireContext(), R.attr.on_surface);
        b.tvMoreFilters.setTextColor(textColor);
        b.tvMoreFilters.setCompoundDrawableTintList(ColorStateList.valueOf(on ? textColor
                : ThemeManager.resolveColor(requireContext(), R.attr.on_surface_variant)));
        b.tvMoreBadge.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            b.tvMoreBadge.setText(String.valueOf(active));
        }
    }

    /** 当前排序名（显示在头部 meta 行，避免图标没有状态） */
    private String sortLabel() {
        String[] names = getResources().getStringArray(R.array.sort_entries);
        int i = Math.max(0, Math.min(sortIndex, names.length - 1));
        return names[i];
    }

    /** 头部左侧：全库册数与地点数（地点字典到达后才有地点数） */
    private void updateShelfMeta() {
        if (b == null) {
            return;
        }
        StringBuilder meta = new StringBuilder().append(allBooks.size()).append(" 册");
        if (!locations.isEmpty()) {
            meta.append(" · ").append(locations.size()).append(" 个地点");
        }
        b.tvShelfMeta.setText(meta);
    }

    /** 首屏骨架屏：呼吸动画只在加载期间运行 */
    private void showLoading(boolean show) {
        if (b == null) {
            return;
        }
        b.llLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // 骨架屏按当前视图选择形状，避免网格模式先闪一下列表骨架
            b.llLoading.removeAllViews();
            if (viewMode == 1) {
                b.llLoading.addView(getLayoutInflater().inflate(
                        R.layout.view_skeleton_grid, b.llLoading, false));
            } else {
                for (int i = 0; i < 5; i++) {
                    b.llLoading.addView(getLayoutInflater().inflate(
                            R.layout.item_skeleton_row, b.llLoading, false));
                }
            }
            if (skeletonAnimator == null) {
                skeletonAnimator = android.animation.ObjectAnimator.ofFloat(b.llLoading, "alpha", 0.45f, 1f);
                skeletonAnimator.setDuration(900);
                skeletonAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                skeletonAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            }
            skeletonAnimator.start();
        } else {
            if (skeletonAnimator != null) {
                skeletonAnimator.cancel();
            }
            b.llLoading.setAlpha(1f);
        }
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
            // 只有首屏（本实例还没有任何数据）才收起骨架屏，
            // 后续刷新列表里本来就有旧内容，闪一下反而更难看
            showLoading(false);
        }
        allBooks = BookSanitizer.normalizeAll(
                pushed == null ? new ArrayList<>() : pushed);
        // v4.41：异步重建搜索索引（拼音转写不放在按键路径上）
        refreshSearchIndexAsync();
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
            try {
                channelRowsAll = app.db().sourceChannelDao().getAll();
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
            List<com.bai.bookshelf.data.entity.BorrowRecord> openLoans = null;
            try {
                openLoans = app.db().borrowRecordDao().getAllOpen();
            } catch (Exception ignored) {
            }

            final List<Location> fLocs = locs;
            final List<com.bai.bookshelf.data.entity.Tag> fTags = tags;
            final List<Category> fCategories = categories;
            final List<String> fCountries = countries == null ? new ArrayList<>() : countries;
            final List<String> fChannels = channels == null ? new ArrayList<>() : channels;
            final List<com.bai.bookshelf.data.entity.BorrowRecord> fOpen =
                    openLoans == null ? new ArrayList<>() : openLoans;

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                locations = fLocs == null ? new ArrayList<>() : fLocs;
                tagList = fTags == null ? new ArrayList<>() : fTags;
                categoryList = fCategories == null ? new ArrayList<>() : fCategories;
                channelList = fChannels;
                countryList = fCountries; // 留实例快照，旋转屏重建视图时兜底重放用
                setOpenLoans(fOpen);
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
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            android.widget.TextView tv =
                    (android.widget.TextView) super.getView(position, convertView, parent);
            boolean active = position > 0;
            int primary = ThemeManager.resolveColor(requireContext(), ThemeManager.ATTR_COLOR_PRIMARY);
            tv.setBackgroundResource(active
                    ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
            tv.setTextColor(active ? primary
                    : ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
            // 滤镜胶囊的细线下箭头（选中态跟主色，未选中跟次级文字色）
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down_line, 0);
            tv.setCompoundDrawableTintList(ColorStateList.valueOf(active ? primary
                    : ThemeManager.resolveColor(requireContext(), R.attr.on_surface_variant)));
            tv.setCompoundDrawablePadding(dp(1));
            return tv;
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
                tv.setTextColor(ThemeManager.resolveColor(
                        requireContext(), R.attr.on_surface));
                tv.setBackgroundColor(ThemeManager.resolveColor(
                        requireContext(), R.attr.surface));
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

    /**
     * 来源筛选下拉（v4.10 两级化）：首项「来源」= 不限，其余为三个来源大类；
     * 选中大类后在下方行内展开该组二级渠道 chips。
     */
    private void fillChannelSpinner() {
        List<String> names = new ArrayList<>();
        names.add("来源");
        names.addAll(java.util.Arrays.asList(
                getResources().getStringArray(R.array.own_type_entries)));
        fillSpinnerIfChanged(b.spChannel, names, channelSpinnerCache);
    }

    /** 来源二级渠道 chips 行内展开（v4.10）：type<0 时收起；渠道=渠道库该组 + 藏书里出现过的旧渠道名 */
    private void showChannelChips(int type) {
        chipChannelType = type;
        if (type < 0 || b == null) {
            b.scrollChannelChips.setVisibility(View.GONE);
            b.llChannelChips.removeAllViews();
            return;
        }
        String[] typeNames = getResources().getStringArray(R.array.own_type_entries);
        b.llChannelChips.removeAllViews();
        b.llChannelChips.addView(buildChip("全部「" + typeNames[type] + "」",
                channelSubSel.isEmpty(), v -> {
                    channelSubSel = "";
                    showChannelChips(type);
                    applyFilters();
                }));
        // 渠道库该组的渠道 + 藏书里出现过的该大类旧渠道名（老数据可能不在库里）
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (com.bai.bookshelf.data.entity.SourceChannel ch : channelRowsAll) {
            if (ch.ownType == type) {
                names.add(ch.name);
            }
        }
        for (Book bk : allBooks) {
            String cn = bk.sourceChannel == null ? "" : bk.sourceChannel.trim();
            if (bk.ownType == type && !cn.isEmpty()) {
                names.add(cn);
            }
        }
        for (String name : names) {
            b.llChannelChips.addView(buildChip(name, name.equals(channelSubSel), v -> {
                channelSubSel = name;
                showChannelChips(type);
                applyFilters();
            }));
        }
        b.scrollChannelChips.setVisibility(View.VISIBLE);
    }

    /** 行内筛选 chip（v4.10 小号圆角标签；v4.24 与筛选胶囊统一形状） */
    private android.widget.TextView buildChip(String text, boolean selected,
                                              View.OnClickListener listener) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackgroundResource(selected
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
        // v4.16：选中态文字用当前主题主色（原先写死 R.color.primary，换肤后不跟随）
        tv.setTextColor(selected
                ? ThemeManager.resolveColor(requireContext(),
                ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        tv.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * 分类筛选下拉（v4.10 两段式）：首项「分类」= 不限，其余只列一级栏目；
     * 选中一级后在下方行内展开该组二级 chips（不再用弹窗）。
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

    /** 分类二级 chips 行内展开（v4.10）：top 为 null 时收起 */
    private void showCategoryChips(Category top) {
        chipCategoryTopId = top == null ? -1 : top.id;
        if (top == null || b == null) {
            b.scrollCategoryChips.setVisibility(View.GONE);
            b.llCategoryChips.removeAllViews();
            return;
        }
        b.llCategoryChips.removeAllViews();
        b.llCategoryChips.addView(buildChip("全部「" + top.name + "」", catFilterSub.isEmpty(), v -> {
            catFilterSub = "";
            showCategoryChips(top);
            applyFilters();
        }));
        for (Category c : categoryList) {
            if (c.parentId != null && c.parentId == top.id) {
                b.llCategoryChips.addView(buildChip(c.name, c.name.equals(catFilterSub), v -> {
                    catFilterSub = c.name;
                    showCategoryChips(top);
                    applyFilters();
                }));
            }
        }
        b.scrollCategoryChips.setVisibility(View.VISIBLE);
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
        if (b == null) {
            return;
        }
        updateMoreFilterPill();
        updateSmartShelves();
        // v4.4：本实例还没收到过 books 推送时不渲染（onViewCreated 的
        // applyViewMode 会提前调进来），避免加载指示旁边闪现空态文案
        if (!booksReceived) {
            return;
        }
        // v4.4：不再因字典 Spinner 未就绪而整体跳过——两段式渲染下 books 推送
        // 到达时先按「关键词 + 藏书/阅读状态 + 排序」渲染列表（这些 Spinner 内容
        // 写死在布局里，始终可用），字典 Spinner 空的时候一律按「不限」处理，
        // 字典补查完成后（spinnerReady 置位）第二段会再精筛一次。
        // 空 Spinner 的 getSelectedItemPosition() 返回 INVALID_POSITION(-1)，
        // 统一夹到 0（=「不限」），避免误触发字典过滤。
        String keyword = b.etSearch.getText().toString().trim();
        String queryNorm = com.bai.bookshelf.util.SearchText.normalize(keyword);
        int stockFilter = selPos(b.spStock);     // 0=全部
        int readFilter = selPos(b.spReadFilter); // 0=全部
        int locFilter = selPos(b.spLocation);    // 0=全部
        int tagFilter = selPos(b.spTag);         // 0=全部
        int catFilter = selPos(b.spCategory);    // 0=全部
        int sortType = sortIndex;
        String countryFilter = b.spCountry.getSelectedItemPosition() > 0
                ? String.valueOf(b.spCountry.getSelectedItem()) : "";

        List<Book> result = new ArrayList<>();
        for (Book book : allBooks) {
            // 关键词：书名/副标题/作者/译者/出版社/ISBN/标签/分类/国家，
            // 忽略大小写与空格标点，并支持全拼 / 首字母（v4.41）
            if (!queryNorm.isEmpty()) {
                String haystack = searchIndex.get(book.id);
                boolean hit = haystack != null
                        ? haystack.contains(queryNorm)
                        : com.bai.bookshelf.util.SearchText.matchesFallback(book, queryNorm);
                if (!hit) {
                    continue;
                }
            }
            // 智能书架（v4.42）：一次只看一类（在读 / 借出中 / 超期…）
            if (smartShelf >= 0 && !matchesSmartShelf(book, smartShelf)) {
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
            // 来源（v4.10 两级：先按大类 ownType，二级渠道名再精筛；老数据直接挂在一级名下的也保留）
            if (channelTypeSel >= 0) {
                if (book.ownType != channelTypeSel) {
                    continue;
                }
                if (!channelSubSel.isEmpty() && !book.sourceChannel.equals(channelSubSel)) {
                    continue;
                }
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
            // 系列（v4.53，等值匹配；不带系列的书在启用该筛选时不显示）
            if (!seriesFilter.isEmpty() && !seriesFilter.equalsIgnoreCase(
                    com.bai.bookshelf.util.BookSeries.name(book))) {
                continue;
            }
            // 价格区间（v4.38，按定价）：未填定价的书在启用该筛选时不显示
            if (priceMin != null || priceMax != null) {
                if (book.listPrice <= 0) {
                    continue;
                }
                if (priceMin != null && book.listPrice < priceMin - 1e-9) {
                    continue;
                }
                if (priceMax != null && book.listPrice > priceMax + 1e-9) {
                    continue;
                }
            }
            // 购入日期区间（v4.35）：按书目日期精度展开成区间再取交集
            if (purchaseRange != null) {
                int[] span = com.bai.bookshelf.util.DateKeys.purchaseSpan(book.purchaseDate);
                if (span == null || !inRange(span[0], span[1], purchaseRange)) {
                    continue;
                }
            }
            // 录入日期区间（v4.35）：createdAt 毫秒 → 当日
            if (entryRange != null) {
                int k = com.bai.bookshelf.util.DateKeys.dayKey(book.createdAt);
                if (!inRange(k, k, entryRange)) {
                    continue;
                }
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
        // v4.41：搜索词同时给三个适配器，命中处高亮
        adapter.setKeyword(keyword);
        gridAdapter.setKeyword(keyword);
        textAdapter.setKeyword(keyword);
        int activeFilters = activeFilterCount(stockFilter, readFilter, locFilter, tagFilter,
                catFilter, countryFilter);
        boolean filtered = activeFilters > 0 || !queryNorm.isEmpty() || smartShelf >= 0;
        bindResultLine(result.size(), activeFilters, queryNorm.isEmpty() ? "" : keyword);
        b.tvShelfResultCount.setVisibility(View.VISIBLE);
        updateShelfMeta();
        b.hsvFilters.post(this::updateFilterFade);
        // v4.28：书架为空时隐藏 FAB（空态已有主按钮，避免一屏两个扫码入口）
        shelfEmpty = result.isEmpty();
        if (shelfEmpty) {
            b.fabScan.hide();
        } else if (!selectionMode) {
            b.fabScan.show();
        }
        Map<Long, String> locMap = locationNameMap(); // 只构造一次（原本构造了两次）
        if (viewMode == 1) {
            gridAdapter.submit(result);
        } else if (viewMode == 2) {
            textAdapter.submit(result, locMap);
        } else {
            adapter.submit(result, locMap);
        }
        updateEmptyState(result.isEmpty(), keyword, filtered);
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
        // v4.24：空态拆成「标题 + 说明 + 主按钮」，只有真的没有藏书时才引导扫码
        b.llEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            return;
        }
        b.tvEmptyDesc.setVisibility(View.VISIBLE);
        if (!keyword.isEmpty()) {
            b.tvEmptyTitle.setText("没有找到「" + keyword.trim() + "」");
            b.tvEmptyDesc.setText("换个关键词，或清空筛选条件试试");
            // v4.41：空结果给出口，而不是只留一句提示
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("清空搜索");
            b.btnEmptyAdd.setOnClickListener(v -> {
                searchDebounce.removeCallbacks(searchApply);
                b.etSearch.setText("");
                applyFilters();
            });
        } else if (hasFilter) {
            b.tvEmptyTitle.setText("当前筛选下没有藏书");
            b.tvEmptyDesc.setText("试试清除筛选条件，看全部藏书");
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("清除筛选");
            b.btnEmptyAdd.setOnClickListener(v -> clearAllFilters());
        } else {
            b.tvEmptyTitle.setText("书架还是空的");
            b.tvEmptyDesc.setText("扫码录入第一本书，开始整理你的书房");
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("扫码录入第一本书");
            b.btnEmptyAdd.setOnClickListener(v ->
                    launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        }
    }

    /** 当前生效的筛选项数量（不含搜索关键词） */
    private int activeFilterCount(int stock, int read, int loc, int tag, int cat, String country) {
        int count = 0;
        if (stock > 0) count++;
        if (read > 0) count++;
        if (loc > 0) count++;
        if (tag > 0) count++;
        if (cat > 0) count++;
        if (channelTypeSel >= 0) count++;
        if (!country.isEmpty()) count++;
        if (!seriesFilter.isEmpty()) count++;
        if (priceMin != null || priceMax != null) count++;
        if (purchaseRange != null) count++;
        if (entryRange != null) count++;
        return count;
    }

    /**
     * 头部结果行（v4.41）：有筛选时「共 N 本 · 已筛选 X 项」+ 右侧固定「清除」按钮，
     * 只搜索时「共 N 本 · 搜索结果」+「清空」。按钮固定占位，不会被文字挤掉。
     */
    private void bindResultLine(int count, int activeFilters, String keyword) {
        if (activeFilters == 0 && keyword.isEmpty() && smartShelf < 0) {
            b.tvShelfResultCount.setText("按" + sortLabel());
            b.btnClearFilters.setVisibility(View.GONE);
            return;
        }
        b.btnClearFilters.setVisibility(View.VISIBLE);
        StringBuilder text = new StringBuilder("共 ").append(count).append(" 本");
        if (smartShelf >= 0) {
            text.append(" · ").append(SMART_NAMES[smartShelf]);
        }
        if (activeFilters > 0) {
            text.append(" · 已筛选 ").append(activeFilters).append(" 项");
        }
        if (!keyword.isEmpty()) {
            text.append(" · 搜索");
        }
        b.tvShelfResultCount.setText(text);
        b.btnClearFilters.setText("清除");
        // 结果行的清除是「一键回到全部」：智能书架、筛选项、搜索词一起清
        b.btnClearFilters.setOnClickListener(v -> {
            searchDebounce.removeCallbacks(searchApply);
            if (!b.etSearch.getText().toString().isEmpty()) {
                b.etSearch.setText("");
            }
            clearAllFilters();
        });
    }

    // ======================= 智能书架（v4.42） =======================

    /** 建一次胶囊（数量与选中态由 updateSmartShelves 刷新） */
    private void setupSmartShelves() {
        if (b == null) {
            return;
        }
        b.llSmart.removeAllViews();
        int textColor = ThemeManager.resolveColor(requireContext(), R.attr.on_surface);
        for (int i = 0; i < SMART_NAMES.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setTextSize(12);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setTextColor(textColor);
            chip.setBackgroundResource(R.drawable.bg_filter_pill);
            chip.setClickable(true);
            chip.setFocusable(true);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            final int index = i;
            chip.setOnClickListener(v -> {
                smartShelf = smartShelf == index ? -1 : index;
                applyFilters();
            });
            b.llSmart.addView(chip);
            smartChips[i] = chip;
        }
    }

    /** 刷新智能书架：数量为全库口径；数量为 0 且未选中的入口隐藏 */
    private void updateSmartShelves() {
        if (b == null) {
            return;
        }
        int[] counts = com.bai.bookshelf.util.SmartShelf.counts(
                allBooks, openLoanByBook, com.bai.bookshelf.util.BorrowReminder.todayKey());
        boolean any = false;
        int primary = ThemeManager.resolveColor(requireContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int normal = ThemeManager.resolveColor(requireContext(), R.attr.on_surface);
        for (int i = 0; i < SMART_NAMES.length; i++) {
            TextView chip = smartChips[i];
            if (chip == null) {
                continue;
            }
            boolean show = counts[i] > 0 || smartShelf == i;
            chip.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                continue;
            }
            any = true;
            boolean active = smartShelf == i;
            chip.setText(SMART_NAMES[i] + " " + counts[i]);
            chip.setBackgroundResource(active
                    ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
            chip.setTextColor(active ? primary : normal);
        }
        b.hsvSmart.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** 智能书架匹配规则（与入口数量同一套，避免数字与结果对不上） */
    private boolean matchesSmartShelf(Book book, int index) {
        return com.bai.bookshelf.util.SmartShelf.matches(book, index,
                openLoanByBook.get(book.id), com.bai.bookshelf.util.BorrowReminder.todayKey());
    }

    /** 更新未归还记录（超期判断用），不触发筛选（第二段末尾统一 applyFilters） */
    private void setOpenLoans(List<com.bai.bookshelf.data.entity.BorrowRecord> open) {
        openLoanByBook.clear();
        for (com.bai.bookshelf.data.entity.BorrowRecord r : open) {
            if (!openLoanByBook.containsKey(r.bookId)) {
                openLoanByBook.put(r.bookId, r);
            }
        }
    }

    /** 从详情返回 / 切回书架时刷新未归还记录（逐本借出不会改 books 表，Room 不会推送） */
    private void refreshOpenLoans() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.BorrowRecord> open;
            try {
                open = app.db().borrowRecordDao().getAllOpen();
            } catch (Exception e) {
                open = new ArrayList<>();
            }
            final List<com.bai.bookshelf.data.entity.BorrowRecord> fOpen = open;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                setOpenLoans(fOpen);
                applyFilters();
            });
        });
    }

    /** 数据变化后异步重建搜索索引（拼音转写较贵，不放在按键路径上） */
    private void refreshSearchIndexAsync() {
        final List<Book> snapshot = new ArrayList<>(allBooks);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            java.util.Map<Long, String> map = new java.util.HashMap<>(snapshot.size() * 2);
            // v4.50：摘抄原文/批注/标签也进索引，搜金句能定位到书
            java.util.Map<Long, StringBuilder> extras = new java.util.HashMap<>();
            try {
                for (com.bai.bookshelf.data.entity.Excerpt e
                        : app.db().excerptDao().getAll()) {
                    StringBuilder sb = extras.get(e.bookId);
                    if (sb == null) {
                        sb = new StringBuilder();
                        extras.put(e.bookId, sb);
                    }
                    sb.append(com.bai.bookshelf.util.SearchText.normalize(e.content)).append(' ')
                            .append(com.bai.bookshelf.util.SearchText.normalize(e.note)).append(' ')
                            .append(com.bai.bookshelf.util.SearchText.normalize(e.tags));
                }
            } catch (Exception ignored) {
            }
            for (Book book : snapshot) {
                StringBuilder extra = extras.get(book.id);
                map.put(book.id, com.bai.bookshelf.util.SearchText.haystack(
                        book, extra == null ? "" : extra.toString()));
            }
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                searchIndex.clear();
                searchIndex.putAll(map);
                // 索引晚于输入就绪时补一次筛选（此时才可能命中拼音）
                if (!b.etSearch.getText().toString().trim().isEmpty()
                        && !com.bai.bookshelf.util.SearchText
                                .normalize(b.etSearch.getText().toString()).isEmpty()) {
                    applyFilters();
                }
            });
        });
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

    /** 录入方式选择（v4.34：全屏透明窗口 + 自绘遮罩 + 底部锚定，彻底绕开浮动窗口定位问题） */
    private void showAddOptions() {
        SheetAddBookBinding sb = SheetAddBookBinding.inflate(getLayoutInflater());

        // 不再用 setLayout(宽度/重力) 去“钉”一个浮动窗口：个别 ROM 会把窗口
        // 量成 WRAP 高度/贴错位置（真机反馈：面板出现在屏幕中间）。
        // 改为全屏窗口 + 内部 FrameLayout：遮罩铺满，面板用 gravity=bottom 锚定，
        // 无论系统怎么处理窗口，面板都贴在屏幕底部。
        android.app.Dialog sheet = new android.app.Dialog(requireContext());
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(requireContext());
        android.view.View scrim = new android.view.View(requireContext());
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(v -> sheet.dismiss());
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int bottomInset = InsetsHelper.systemBars(requireActivity().getWindow().getDecorView()).bottom;
        // 面板高度：先量内容，超过屏幕 88% 就封顶（内部 ScrollView 滚动）
        int maxH = (int) (screenH * 0.88f);
        sb.getRoot().measure(
                android.view.View.MeasureSpec.makeMeasureSpec(screenW,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(maxH,
                        android.view.View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(sb.getRoot().getMeasuredHeight(), maxH);
        // 底部给手势条留白（全屏窗口不会被系统自动避让）
        sb.sheetContent.setPadding(
                sb.sheetContent.getPaddingLeft(), sb.sheetContent.getPaddingTop(),
                sb.sheetContent.getPaddingRight(),
                sb.sheetContent.getPaddingBottom() + bottomInset);
        android.widget.FrameLayout.LayoutParams sheetLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, sheetH,
                        android.view.Gravity.BOTTOM);
        root.addView(sb.getRoot(), sheetLp);

        sheet.setContentView(root);
        android.view.Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_Bookshelf_Sheet);
            InsetsHelper.makeSheetWindowFullScreen(window);
        }

        sb.rowScan.setOnClickListener(v -> {
            sheet.dismiss();
            launchAndRefresh(new Intent(requireContext(), ScanActivity.class));
        });
        sb.rowIsbn.setOnClickListener(v -> {
            sheet.dismiss();
            showIsbnInput();
        });
        sb.rowManual.setOnClickListener(v -> {
            sheet.dismiss();
            launchAndRefresh(new Intent(requireContext(), BookEditActivity.class));
        });

        sheet.show();
        // 全屏窗口：位置与重力无关，尺寸也钉死，异常 ROM 上也只会「铺满」而不会偏移
        if (window != null) {
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** 手动输入 ISBN 的弹窗 */
    private void showIsbnInput() {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        db.inputLayout.setHint("输入 10 或 13 位 ISBN");
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(requireContext())
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
    public void onResume() {
        super.onResume();
        // 逐本借出/归还不改 books 表，Room 不会推送——回到书架时单独刷新借出中记录
        if (b != null && booksReceived) {
            refreshOpenLoans();
        }
    }

    @Override
    public void onDestroyView() {
        if (skeletonAnimator != null) {
            skeletonAnimator.cancel();
            skeletonAnimator = null;
        }
        searchDebounce.removeCallbacks(searchApply);
        super.onDestroyView();
        b = null;
    }
}
