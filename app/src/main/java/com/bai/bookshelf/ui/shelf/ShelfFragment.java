package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.util.DateKeys;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.InsetsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private List<Book> allBooks = new ArrayList<>();
    private List<Location> locations = new ArrayList<>();
    private List<com.bai.bookshelf.data.entity.Tag> tagList = new ArrayList<>();
    private List<Category> categoryList = new ArrayList<>();




    /** v4.10 两段式来源筛选：选中的来源大类（-1 = 不限）与二级渠道名（空 = 整组） */
    private int channelTypeSel = -1;


    /** 来源渠道库全量（按 ownType 分组，v4.10） */
    private List<com.bai.bookshelf.data.entity.SourceChannel> channelRowsAll = new ArrayList<>();
    private List<String> channelList = new ArrayList<>(); // 藏书里出现过的来源渠道（去重）
    private List<String> countryList = new ArrayList<>(); // 藏书里出现过的国家（去重，v3.5：留实例快照供视图重建后重放）
    private String pendingSearchQuery;                   // 外部带入的搜索词（视图未就绪时缓存）
    /** 扫书架二维码带进来的地点筛选（v5.8.18，等地字典表到位后才能落到 Spinner 上） */
    private long pendingLocationId = -1;
    private boolean spinnerReady = false; // 防止初始化 spinner 时触发筛选
    // v4.4：是否已收到本实例的第一次 books 推送。onViewCreated 里 applyViewMode()
    // 会同步调一次 applyFilters，此时首推未到、allBooks 为空，不挡住会在
    // 加载指示旁边闪现「空书架」文案；首推到达后置位，之后所有渲染正常放行
    private boolean booksReceived = false;
    private boolean sortReversed = false; // 排序倒序开关
    private int sortIndex = 0;            // 排序方式（arrays.xml sort_entries 下标）
    private int viewMode = 0;             // 视图模式：0=列表 1=封面网格 2=纯文字列表
    private boolean shelfEmpty = false;    // 当前结果为空（空态时隐藏 FAB）
    // v4.36：购入 / 录入日期区间筛选（按日，null = 不限）
    // v4.38/v4.53：系列 / 价格 / 日期区间（v5.6 收敛到 ShelfFilterState）
    private final ShelfFilterState filterState = new ShelfFilterState();
    private ShelfFilterRows filterRows;
    private ShelfBatchActions batchActions;
    /** 多选状态（长按进入；视图重建后重新挂载） */
    private final ShelfSelection selection = new ShelfSelection(() -> shelfEmpty);
    /** 头部 meta / 结果行 / 空态（v5.6 从本类抽出） */
    private ShelfStatusView statusView;
    /** 快捷筛选胶囊 + 更多筛选面板（v5.6 从本类抽出） */
    private ShelfFilterPanel filterPanel;
    /** 来源/分类的二级 chips（v5.6 从本类抽出） */
    private ShelfSecondaryChips chips;
    /** 筛选下拉监听（v5.6 从本类抽出） */
    private ShelfFilterListeners filterListeners;
    /** 视图模式与排序图标（v5.6 从本类抽出） */
    private ShelfViewMode viewModeCtl;
    private List<Book> lastResult = new ArrayList<>(); // 当前筛选结果（全选用）

    // v4.41：搜索索引（bookId → 归一化原文 + 拼音 + 首字母）与 150ms 输入防抖
    private final java.util.Map<Long, String> searchIndex = new java.util.HashMap<>();
    /** v5.8.20：每本书上次入索引时的 updatedAt，用来只重算变过的书 */
    private final java.util.Map<Long, Long> indexedStamps = new java.util.HashMap<>();
    /** 上次索引看到的摘抄表指纹（条数 + 最后改动时刻） */
    private String excerptFingerprint = "";
    /** 是否已经注册 books 的 LiveData（库就绪回调可能被调用不止一次） */
    private boolean observingBooks;
    /** 筛选/搜索的代际号（v5.8.13）：只有最新一次的结果允许上屏 */
    private long filterGeneration;

    /** v5.8.16：当前结果的字母桶（按书名/作者排序时才有值） */
    private List<String> indexLetters = new ArrayList<>();

    /** 页头字标行是否已折叠（v5.8.5） */
    private boolean headerCollapsed;
    private int heroFullHeight;
    private ShelfSearchBar searchBar;

    // v4.42：智能书架（在读/借出中/超期…）与未归还记录
    // v4.55：判定与计数收敛到 util/SmartShelf，书架只负责 UI
    private static final String[] SMART_NAMES = com.bai.bookshelf.util.SmartShelf.NAMES;
    private SmartShelfBar smartBar;
    private int smartShelf = -1;
    /** 新人「下一步」卡本轮是否可见 */
    /** 首屏骨架屏（v5.6 从本类抽出） */
    private final ShelfLoading loading = new ShelfLoading();
    /** 未归还借出记录（v5.6 从本类抽出） */
    private final ShelfLoans loans = new ShelfLoans();

    /** 筛选下拉的内容缓存与选中记忆（v5.6 从本类抽出） */
    private final ShelfFilterSpinners filterSpinners = new ShelfFilterSpinners();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // v3.3：视图即将销毁重建，先把各 Spinner 当前的选中文案存下来，
        // 否则用户在重建后会被重置回「不限」
        filterSpinners.saveSelections(b);
        b = FragmentShelfBinding.inflate(inflater, container, false);
        // v3.3 关键修复：视图是每次 onCreateView 全新创建的，而内容缓存是 Fragment 实例级的；
        // 若不同步重置，就会出现「缓存认为内容没变 → 跳过填充」，
        // 但新 Spinner 其实还是空的 → 分类/标签/地点/来源/国家全部空白且无法选择。
        spinnerReady = false;          // 新视图的 Spinner 还没数据
        filterSpinners.onViewRecreated(); // 清空缓存，强制下次填充
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // v4.18：应用 targetSdk 36，在 Android 15+ 强制 edge-to-edge，
        // 内容会画到状态栏之下。v4.17 把顶部工具栏换成自定义 LinearLayout 后，
        // 丢失了 MaterialToolbar 自带的自动避让能力，搜索框被状态栏压住点不到。
        // 这里显式给头部加状态栏内边距：头部自身是主色背景，
        // 加高后状态栏与头部连成一片（与 v4.16 观感一致），内容下移不被遮挡。
        InsetsHelper.applyTopInset(b.header);
        chips = new ShelfSecondaryChips(b, new ShelfSecondaryChips.Host() {
            @Override
            public List<com.bai.bookshelf.data.entity.SourceChannel> channelRows() {
                return channelRowsAll;
            }

            @Override
            public List<Book> allBooks() {
                return allBooks;
            }

            @Override
            public List<Category> categories() {
                return categoryList;
            }

            @Override
            public void applyFilters() {
                ShelfFragment.this.applyFilters();
            }
        });

        // v4.4：首次加载指示改在视图创建时显示（此实例还没有任何数据可显示时），
        // 两段式渲染下 books 一到就隐藏（见 onBooksPushed 第一段）——
        // 指示只覆盖「Room 首推」的空窗，不再被启动期长任务拖长
        if (allBooks.isEmpty()) {
            loading.show(b, true, viewMode);
        }
        // 列表 / 封面 / 文字 三适配器：点击在多选模式下是切换选中，否则进详情页
        BookAdapter.OnBookClick openDetail = (book, cover) -> {
            if (selection.isActive()) {
                selection.toggle(book);
                return;
            }
            // v4.39：不再走封面 shared element 过渡。实机计时显示：共享元素要和
            // 系统做一次位置握手，详情首帧被硬生生推后 90ms 左右（270ms vs 180ms），
            // 点书的手感明显发滞。改用系统默认的页面动画，首帧直接可见。
            // 用户点开了某条结果 = 这次搜索有用，记进「最近搜索」
            if (searchBar != null && !searchBar.text().isEmpty()) {
                com.bai.bookshelf.util.RecentSearches.add(requireContext(), searchBar.text());
            }
            Intent intent = new Intent(requireContext(), BookDetailActivity.class);
            intent.putExtra(BookDetailActivity.EXTRA_BOOK, book);
            // v5.8.5：封面共享元素过渡（v4.39 曾因首帧慢去掉，本轮按需求接回，
            // 用 300ms 兜底避免离线封面把详情页卡住）
            if (cover != null && cover.getTransitionName() != null && getActivity() != null) {
                intent.putExtra(BookDetailActivity.EXTRA_TRANSITION_NAME,
                        cover.getTransitionName());
                android.app.ActivityOptions options =
                        android.app.ActivityOptions.makeSceneTransitionAnimation(
                                requireActivity(), cover, cover.getTransitionName());
                startActivity(intent, options.toBundle());
            } else {
                startActivity(intent);
            }
        };
        adapter = new BookAdapter(openDetail);
        gridAdapter = new BookGridAdapter(openDetail::onClick);
        textAdapter = new BookAdapter(openDetail, true); // 无封面
        // 长按进入多选模式（三个适配器行为一致）
        BookAdapter.OnBookLongClick enterSelect = selection::enter;
        adapter.setOnBookLongClick(enterSelect);
        textAdapter.setOnBookLongClick(enterSelect);
        gridAdapter.setOnBookLongClick(selection::enter);
        selection.bind(b, adapter, textAdapter, gridAdapter);

        statusView = new ShelfStatusView(b,
                () -> {
                    String[] names = getResources().getStringArray(R.array.sort_entries);
                    int i = Math.max(0, Math.min(sortIndex, names.length - 1));
                    return names[i];
                },
                this::clearAllFilters,
                () -> searchBar.clearNow(),
                () -> searchBar.clearSilently(),
                () -> launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        viewModeCtl = new ShelfViewMode(requireContext(),
                () -> b,
                () -> adapter,
                () -> textAdapter,
                () -> gridAdapter,
                () -> viewMode,
                () -> sortIndex,
                () -> sortReversed,
                this::applyFilters,
                () -> loading.show(b, true, viewMode));
        viewModeCtl.apply();

        // 倒序开关：切换后立即重排
        b.btnSortReverse.setOnClickListener(v -> {
            sortReversed = !sortReversed;
            viewModeCtl.updateIcons();
            applyFilters();
        });
        // 排序方式：行尾图标改为弹出单选项，省掉一个下拉框
        b.btnSort.setOnClickListener(v -> ShelfDisplayMenu.showSort(
                requireActivity(), sortIndex, which -> {
                    sortIndex = which;
                    viewModeCtl.updateIcons();
                    applyFilters();
                }));
        // 视图切换：点开三项小菜单，不再单键循环（避免把图标当成状态标签）
        b.btnView.setOnClickListener(v -> ShelfDisplayMenu.showView(
                requireActivity(), viewMode, which -> {
                    if (viewMode != which) {
                        viewMode = which;
                        requireContext().getSharedPreferences("ui_prefs", 0)
                                .edit().putInt("shelf_view_mode", viewMode).apply();
                        viewModeCtl.apply();
                    }
                }));
        viewModeCtl.updateIcons();

        // 筛选 spinner：藏书状态 / 阅读状态 / 排序（地点/标签/渠道/分类在数据加载后填充）
        // 全部使用紧凑闭合样式（小字号小内边距），下拉弹层保持系统默认
        b.spStock.setAdapter(new HighlightAdapter<>(requireContext(), R.array.filter_stock_entries));
        b.spReadFilter.setAdapter(new HighlightAdapter<>(requireContext(), R.array.filter_read_entries));

        filterListeners = new ShelfFilterListeners(b,
                () -> spinnerReady,
                this::applyFilters,
                (parent, position) -> filterSpinners.recordSelection(b, parent, position),
                type -> channelTypeSel = type,
                () -> chips,
                () -> filterSpinners.categoryEntries());
        filterListeners.attach();
        filterPanel = new ShelfFilterPanel(b, filterState,
                this::clearAllFilters,
                which -> {
                    channelTypeSel = which <= 0 ? -1 : which - 1;
                    chips.clearChannelSub();
                    chips.showChannel(channelTypeSel);
                },
                () -> {
                    filterPanel.updateMoreFilterPill();
                    applyFilters();
                },
                root -> filterRows.appendTo(root));
        filterPanel.setup();
        smartBar = new SmartShelfBar(requireActivity(), b.llSmart, b.hsvSmart, index -> {
            smartShelf = index;
            applyFilters();
        });
        smartBar.setup();
        b.tvSmartToggle.setOnClickListener(v ->
                smartBar.setExpanded(!smartBar.expanded()));

        searchBar = new ShelfSearchBar(b.etSearch, b.btnClearSearch, this::applyFilters);
        // v5.8.1 起：键盘「搜索」键的响应统一由 ShelfRecentSearch 注册（记历史 + 就地筛选），
        // 全局搜索页从聚焦后那枚「全局搜索 ›」胶囊进；这里不再重复注册 editorAction，
        // 否则两处 setOnEditorActionListener 后者吃掉前者（v5.9.0 清理时发现的旧遮蔽）。
        ShelfRecentSearch.setup(recentSearchHost());
        searchBar.setup();
        setupOnboarding();

        filterRows = new ShelfFilterRows(requireActivity(), () -> allBooks, filterState, () -> {
            filterPanel.updateMoreFilterPill();
            applyFilters();
        });
        batchActions = new ShelfBatchActions(requireActivity(),
                () -> selection.ids(),
                () -> allBooks,
                () -> locations,
                () -> tagList,
                () -> selection.exit());
        // v5.8.17：多选操作条的五个按钮（v5.6 抽出批量动作类时漏了接线，一直点了没反应）
        b.btnSelAll.setOnClickListener(v -> selection.selectAll(lastResult));
        b.btnSelCancel.setOnClickListener(v -> selection.exit());
        b.btnSelShare.setOnClickListener(v -> batchActions.showShareMenu());
        b.btnSelEdit.setOnClickListener(v -> batchActions.showBatchEdit());
        b.btnSelDelete.setOnClickListener(v -> batchActions.confirmDelete());

        // 录入方式选择
        b.btnAdd.setOnClickListener(v ->
                ShelfAddActions.showAddOptions(requireActivity(), this::launchAndRefresh));
        // 空态主按钮：直接进扫码
        b.btnEmptyAdd.setOnClickListener(v ->
                launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        // 扫码快捷入口
        b.fabScan.setOnClickListener(v -> launchAndRefresh(new Intent(requireContext(), ScanActivity.class)));
        // 向上滚动时收起 FAB，向下滚动时再出现（只移动，不做弹跳）
        b.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (selection.isActive() || shelfEmpty) {
                    return;
                }
                if (dy > 4) {
                    b.fabScan.hide();
                } else if (dy < -4) {
                    b.fabScan.show();
                }
                // v5.8.5：滚过 96dp 把字标行折叠，回到顶部再展开
                setHeaderCollapsed(recyclerView.computeVerticalScrollOffset()
                        > (int) (96 * getResources().getDisplayMetrics().density));
            }
        });
        setupIndexSidebar();

        // v4.2（评审 #7）：响应式观察 books 表——任何增删改（包括纯文本字段编辑）
        // 都由 Room 自动推送，无需指纹比对、RESULT_OK 标脏或手动触发。
        // 注册时 Room 会立即推送一次当前数据（冷启动首次加载 / 旋转屏恢复都靠它）。
        // v5.8.3：库没就绪（修复中 / 打不开）时先排队，等开库成功再注册；
        // 冷启动时本方法早于后台开库完成，直接跳过会让书架永远空白
        BookshelfApp.get().whenDbReady(this::observeBooks);

        // 外部（统计页点击作者/出版社/国家）带入的搜索词：视图重建后应用一次
        if (pendingSearchQuery != null) {
            String q = pendingSearchQuery;
            pendingSearchQuery = null;
            searchBar.setText(q);
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
            searchBar.setText(keyword.trim());
        }
    }

    /** 扫书架二维码进来：只筛这个地点的书（v5.8.18） */
    public void requestLocationFilter(long locationId) {
        pendingLocationId = locationId;
        if (b != null && spinnerReady) {
            applyPendingLocationFilter();
        }
    }

    /** 字典表到位后把待应用的地点筛选落到 Spinner 上；地点不存在就放弃 */
    private void applyPendingLocationFilter() {
        if (pendingLocationId <= 0 || b == null) {
            return;
        }
        for (int i = 0; i < locations.size(); i++) {
            if (locations.get(i).id == pendingLocationId) {
                b.spLocation.setSelection(i + 1); // 首项「地点」= 不限
                pendingLocationId = -1;
                applyFilters();
                return;
            }
        }
        pendingLocationId = -1;
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
    // 多选状态见 ShelfSelection，批量动作见 ShelfBatchActions（v5.6 从本类抽出）

    /** 一键清除全部筛选条件（搜索关键词不动，它有自己的清除按钮） */
    private void clearAllFilters() {
        filterState.clear();
        smartShelf = -1;
        if (smartBar != null) {
            smartBar.clearSelection();
        }
        channelTypeSel = -1;
        chips.clearChannelSub();
        chips.clearCategorySub();
        if (filterRows != null) {
            filterRows.refreshRows();
        }
        b.spCategory.setSelection(0);
        b.spTag.setSelection(0);
        b.spStock.setSelection(0);
        b.spReadFilter.setSelection(0);
        b.spLocation.setSelection(0);
        b.spChannel.setSelection(0);
        b.spCountry.setSelection(0);
        chips.showCategory(null);
        chips.showChannel(-1);
        filterPanel.updateMoreFilterPill();
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
    /** 新人下一步卡：只在「第一本书」时出现一次，四个动作都能直达 */
    private void setupOnboarding() {
        b.btnOnboardingClose.setOnClickListener(v -> {
            requireContext().getSharedPreferences("ui_prefs", 0).edit()
                    .putBoolean("onboarding_next_done", true).apply();
            updateOnboarding();
        });
        b.btnOnboardingBackup.setOnClickListener(v -> {
            android.content.Context ctx = requireContext();
            com.bai.bookshelf.util.BackupManager.setEnabled(ctx, true);
            com.bai.bookshelf.util.BackupManager.autoBackupIfEnabled(ctx);
            String extra = com.bai.bookshelf.util.BackupManager.getTreeUri(ctx) == null
                    ? "；可在「我的 → 数据与备份」选择外部文件夹" : "";
            Toast.makeText(ctx, "自动备份已开启（每天最多一次）" + extra,
                    Toast.LENGTH_LONG).show();
        });
        b.btnOnboardingGoal.setOnClickListener(v ->
                com.bai.bookshelf.ui.stats.ReadingGoalDialog.show(requireContext(), null));
        b.btnOnboardingInventory.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ScanActivity.class);
            intent.putExtra(ScanActivity.EXTRA_INVENTORY, true);
            startActivity(intent);
        });
        b.btnOnboardingWish.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(),
                    com.bai.bookshelf.ui.wish.WishListActivity.class);
            intent.setAction(com.bai.bookshelf.ui.wish.WishListActivity.ACTION_ADD_WISH);
            startActivity(intent);
        });
    }

    private void updateOnboarding() {
        if (b == null) {
            return;
        }
        int owned = com.bai.bookshelf.util.BookFlags.countOwned(allBooks);
        // v5.8.1：只要「有书且还没关过」就一直显示。原先只在恰好 1 本时出现一次，
        // 而且是「显示即记为已完成」，第二本书进来后想再找这张卡就没了。
        boolean done = requireContext().getSharedPreferences("ui_prefs", 0)
                .getBoolean("onboarding_next_done", false);
        b.cardOnboarding.setVisibility(owned >= 1 && !done ? View.VISIBLE : View.GONE);
    }

    /**
     * 页头字标行折叠/展开（v5.8.5）。
     *
     * <p>用高度补间而不是直接 GONE，避免列表内容突然跳一格；展开高度只量一次并缓存。
     */
    private void setHeaderCollapsed(boolean collapse) {
        if (b == null || headerCollapsed == collapse) {
            return;
        }
        final View hero = b.llShelfHero;
        if (heroFullHeight <= 0) {
            heroFullHeight = hero.getHeight();
        }
        final int full = heroFullHeight > 0 ? heroFullHeight
                : (int) (64 * getResources().getDisplayMetrics().density);
        headerCollapsed = collapse;
        updateFilterSummary();
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(
                collapse ? full : 0, collapse ? 0 : full);
        anim.setDuration(160);
        anim.addUpdateListener(a -> {
            int h = (int) a.getAnimatedValue();
            android.view.ViewGroup.LayoutParams lp = hero.getLayoutParams();
            lp.height = h;
            hero.setLayoutParams(lp);
            hero.setAlpha(full == 0 ? 1f : Math.max(0f, h / (float) full));
        });
        anim.start();
    }

    /** v5.8.14：页头收起后留一行「关键词 · 已读 · 分类」摘要，提醒列表正在被筛过 */
    private void updateFilterSummary() {
        if (b == null) {
            return;
        }
        b.llFilterSummary.removeAllViews();
        if (!headerCollapsed) {
            b.hsvFilterSummary.setVisibility(View.GONE);
            return;
        }
        List<String> labels = activeFilterLabels();
        if (labels.isEmpty()) {
            b.hsvFilterSummary.setVisibility(View.GONE);
            return;
        }
        for (String label : labels) {
            b.llFilterSummary.addView(buildSummaryChip(label, null));
        }
        b.llFilterSummary.addView(buildSummaryChip("清除筛选", this::clearAllFilters));
        b.hsvFilterSummary.setVisibility(View.VISIBLE);
    }

    private List<String> activeFilterLabels() {
        List<String> labels = new ArrayList<>();
        if (searchBar != null) {
            String keyword = searchBar.text().trim();
            if (!keyword.isEmpty()) {
                if (keyword.length() > 12) {
                    keyword = keyword.substring(0, 12) + "…";
                }
                labels.add("“" + keyword + "”");
            }
        }
        if (smartShelf >= 0 && smartShelf < SMART_NAMES.length) {
            labels.add(SMART_NAMES[smartShelf]);
        }
        addSpinnerLabel(labels, b.spStock);
        addSpinnerLabel(labels, b.spReadFilter);
        addSpinnerLabel(labels, b.spCategory);
        addSpinnerLabel(labels, b.spTag);
        addSpinnerLabel(labels, b.spLocation);
        addSpinnerLabel(labels, b.spCountry);
        addSpinnerLabel(labels, b.spChannel);
        if (filterState.hasSeries()) {
            labels.add("系列：" + filterState.series);
        }
        if (filterState.hasPrice()) {
            labels.add("价格区间");
        }
        if (filterState.hasPurchaseRange()) {
            labels.add("购入日期");
        }
        if (filterState.hasEntryRange()) {
            labels.add("录入日期");
        }
        return labels;
    }

    private void addSpinnerLabel(List<String> labels, android.widget.Spinner spinner) {
        if (spinner.getSelectedItemPosition() <= 0) {
            return;
        }
        Object item = spinner.getSelectedItem();
        if (item != null) {
            labels.add(item.toString());
        }
    }

    private TextView buildSummaryChip(String text, Runnable onClick) {
        TextView chip = new TextView(requireContext());
        chip.setText(text);
        com.bai.bookshelf.util.Typo.text(chip, R.dimen.text_caption);
        chip.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(requireContext(),
                com.google.android.material.R.attr.colorOnPrimary));
        chip.setBackgroundResource(R.drawable.bg_summary_chip);
        float density = getResources().getDisplayMetrics().density;
        chip.setPadding((int) (8 * density), (int) (3 * density),
                (int) (8 * density), (int) (3 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (6 * density));
        chip.setLayoutParams(lp);
        if (onClick != null) {
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> onClick.run());
        }
        return chip;
    }

    /** 库就绪后注册 books 响应式观察（冷启动时本页比开库早，必须排队等一次） */    /** 库就绪后注册 books 响应式观察（冷启动时本页比开库早，必须排队等一次） */
    private void observeBooks() {
        if (b == null || !isAdded() || observingBooks) {
            return;
        }
        try {
            observingBooks = true;
            BookshelfApp.get().db().bookDao().getAllLive()
                    .observe(getViewLifecycleOwner(), this::onBooksPushed);
        } catch (RuntimeException e) {
            observingBooks = false; // 库又坏了：修复页接管
        }
    }

    private void onBooksPushed(java.util.List<Book> pushed) {        if (b == null) {
            return;
        }
        // ---- 第一段：books 即到即渲染（主线程） ----
        booksReceived = true; // 首推已到，applyFilters 从此放行（v4.4）
        if (allBooks.isEmpty()) {
            // 只有首屏（本实例还没有任何数据）才收起骨架屏，
            // 后续刷新列表里本来就有旧内容，闪一下反而更难看
            loading.show(b, false, viewMode);
        }
        allBooks = BookSanitizer.normalizeAll(
                pushed == null ? new ArrayList<>() : pushed);
        updateOnboarding();
        // v4.41：异步重建搜索索引（拼音转写不放在按键路径上）
        refreshSearchIndexAsync();
        // 外部带入的搜索词兜底：先灌搜索框（TextWatcher 会触发一次筛选），再统一 applyFilters
        if (pendingSearchQuery != null) {
            String q = pendingSearchQuery;
            pendingSearchQuery = null;
            searchBar.setText(q);
        }
        applyFilters(); // 字典 Spinner 未就绪时自动降级为「不限」（见 applyFilters 注释）

        // ---- 第二段：字典表补查（io 线程，主线程回填） ----
        ShelfDictionaries.loadAsync(BookshelfApp.get(), data -> {
            if (b == null) {
                return;
            }
            locations = data.locations;
            tagList = data.tags;
            categoryList = data.categories;
            if (data.channelRows != null) {
                channelRowsAll = data.channelRows;
            }
            channelList = data.channels;
            countryList = data.countries; // 留实例快照，旋转屏重建视图时兜底重放用
            loans.set(data.openLoans);
            // 各 Spinner 仅在内容变化时重建，避免无谓的全量 notify
            filterSpinners.fillCountry(b, data.countries);
            filterSpinners.fillLocation(b, locations);
            filterSpinners.fillTag(b, tagList);
            filterSpinners.fillChannel(b);
            filterSpinners.fillCategory(b, categoryList);
            filterSpinners.markFilled(); // 本视图的 Spinner 都已有数据
            spinnerReady = true;
            applyPendingLocationFilter();
            applyFilters();
        });
    }

    /** 按搜索词 + 筛选条件 + 排序方式生成展示列表 */
    /** 空 Spinner 的 getSelectedItemPosition() 返回 INVALID_POSITION(-1)，夹到 0（=「不限」/首项） */
    private static int selPos(android.widget.Spinner sp) {
        int pos = sp.getSelectedItemPosition();
        return pos < 0 ? 0 : pos;
    }

    /** 最近搜索胶囊的宿主接线：回填与提交都回到这里的筛选 */
    private ShelfRecentSearch.Host recentSearchHost() {
        return new ShelfRecentSearch.Host() {
            @Override
            public android.content.Context context() {
                return requireContext();
            }

            @Override
            public com.bai.bookshelf.databinding.FragmentShelfBinding binding() {
                return b;
            }

            @Override
            public ShelfSearchBar searchBar() {
                return searchBar;
            }

            @Override
            public void applyQuery() {
                applyFilters();
            }
        };
    }

    /** v5.8.16：右侧字母索引条——点一下或按住拖动都跳，拖动时中间显示气泡 */
    private void setupIndexSidebar() {
        b.indexSidebar.setContentDescription("字母索引，按字母跳转");
        b.indexSidebar.setListener(new IndexSidebarView.Listener() {
            @Override
            public void onLetter(String letter) {
                int pos = com.bai.bookshelf.util.ShelfIndex
                        .firstPositionAtOrAfter(indexLetters, letter);
                if (pos < 0) {
                    return;
                }
                RecyclerView.LayoutManager lm = b.recycler.getLayoutManager();
                if (lm instanceof androidx.recyclerview.widget.GridLayoutManager) {
                    ((androidx.recyclerview.widget.GridLayoutManager) lm)
                            .scrollToPositionWithOffset(pos, 0);
                } else {
                    b.recycler.scrollToPosition(pos);
                }
                b.tvIndexBubble.setText(letter);
                b.tvIndexBubble.setVisibility(View.VISIBLE);
            }

            @Override
            public void onDragEnd() {
                b.tvIndexBubble.setVisibility(View.GONE);
            }
        });
    }

    /** 结果够长且确实是字母序时才露出索引条 */
    private void updateIndexBar() {
        if (b == null) {
            return;
        }
        java.util.Set<String> available =
                com.bai.bookshelf.util.ShelfIndex.available(indexLetters);
        boolean show = indexLetters.size() >= com.bai.bookshelf.util.ShelfIndex.MIN_ITEMS
                && available.size() >= 2;
        b.indexSidebar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            b.tvIndexBubble.setVisibility(View.GONE);
            return;
        }
        b.indexSidebar.setAvailable(available);
    }

    private void applyFilters() {
        if (b == null) {
            return;
        }
        updateFilterSummary();
        if (filterPanel != null) {
            filterPanel.updateMoreFilterPill();
        }
        if (smartBar != null) {
            boolean smartAny = smartBar.update(allBooks, loans.map(),
                    com.bai.bookshelf.util.DateKeys.todayKey());
            b.tvSmartToggle.setVisibility(smartAny ? View.VISIBLE : View.GONE);
            if (!smartAny) {
                smartBar.setExpanded(false);
            }
        }
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
        String keyword = searchBar.text();
        String queryNorm = com.bai.bookshelf.util.SearchText.normalize(keyword);
        int stockFilter = selPos(b.spStock);     // 0=全部
        int readFilter = selPos(b.spReadFilter); // 0=全部
        int locFilter = selPos(b.spLocation);    // 0=全部
        int tagFilter = selPos(b.spTag);         // 0=全部
        int catFilter = selPos(b.spCategory);    // 0=全部
        int sortType = sortIndex;
        String countryFilter = b.spCountry.getSelectedItemPosition() > 0
                ? String.valueOf(b.spCountry.getSelectedItem()) : "";

        ShelfQuery.Criteria criteria = new ShelfQuery.Criteria();
        criteria.stockFilter = stockFilter;
        criteria.readFilter = readFilter;
        criteria.locationId = locFilter > 0 && locFilter <= locations.size()
                ? locations.get(locFilter - 1).id : null;
        criteria.tagName = tagFilter > 0 && tagFilter <= tagList.size()
                ? tagList.get(tagFilter - 1).name : "";
        criteria.category = catFilter > 0 && catFilter - 1 < filterSpinners.categoryEntries().size()
                ? filterSpinners.categoryEntries().get(catFilter - 1) : null;
        criteria.categorySub = chips.categorySub();
        criteria.categoryList = categoryList;
        criteria.channelType = channelTypeSel;
        criteria.channelSub = chips.channelSub();
        criteria.country = countryFilter;
        criteria.series = filterState.series;
        criteria.priceMin = filterState.priceMin;
        criteria.priceMax = filterState.priceMax;
        criteria.purchaseRange = filterState.purchaseRangeKeys();
        criteria.entryRange = filterState.entryRangeKeys();
        criteria.smartShelf = smartShelf;
        criteria.sortType = sortIndex;
        criteria.sortReversed = sortReversed;
        // v5.8.13：筛选/搜索一律下后台 + 代际号。以前每敲一个字就在主线程对全库
        // 跑一次 ShelfQuery（1000 本时肉眼可见掉帧），现在主线程只做快照，
        // 结果回来时如果期间又输入了新的词，直接丢掉过期结果。
        final ShelfQuery.Criteria fCriteria = criteria;
        final String fKeyword = keyword;
        final String fQueryNorm = queryNorm;
        final List<Book> fSnapshot = new ArrayList<>(allBooks);
        final Map<Long, String> fIndex = searchIndex;
        final Map<Long, com.bai.bookshelf.data.entity.BorrowRecord> fLoans = loans.map();
        final int fToday = com.bai.bookshelf.util.DateKeys.todayKey();
        final int fSmart = smartShelf;
        final int fActive = activeFilterCount(stockFilter, readFilter, locFilter, tagFilter,
                catFilter, countryFilter);
        final long gen = ++filterGeneration;
        BookshelfApp.get().io(() -> {
            final List<Book> result =
                    ShelfQuery.query(fSnapshot, fCriteria, fQueryNorm, fIndex, fLoans, fToday);
            // 字母索引桶跟排序走：只有「按书名 / 按作者」是字母序，其余排序不出现索引条
            final List<String> letters =
                    com.bai.bookshelf.util.ShelfIndex.letters(result, fCriteria.sortType);
            BookshelfApp.get().ui(() -> {
                if (gen != filterGeneration || b == null) {
                    return; // 期间又输入/改了筛选，这批结果已经过期
                }
                // v3.2 优化：只给「当前正在显示」的适配器提交数据。
                lastResult = result;
                indexLetters = letters;
                updateIndexBar();
                // v4.41：搜索词同时给三个适配器，命中处高亮
                adapter.setKeyword(fKeyword);
                gridAdapter.setKeyword(fKeyword);
                textAdapter.setKeyword(fKeyword);
                boolean filtered = fActive > 0 || !fQueryNorm.isEmpty() || fSmart >= 0;
                statusView.bindResultLine(result.size(), fActive, fQueryNorm.isEmpty() ? "" : fKeyword,
                        fSmart >= 0 ? SMART_NAMES[fSmart] : "");
                b.tvShelfResultCount.setVisibility(View.VISIBLE);
                statusView.updateMeta(com.bai.bookshelf.util.BookFlags.countOwned(allBooks),
                        ownedCopies, locations.size());
                if (filterPanel != null) {
                    b.hsvFilters.post(filterPanel::updateFade);
                }
                // v4.28：书架为空时隐藏 FAB（空态已有主按钮，避免一屏两个扫码入口）
                shelfEmpty = result.isEmpty();
                if (shelfEmpty) {
                    b.fabScan.hide();
                } else if (!selection.isActive()) {
                    b.fabScan.show();
                }
                Map<Long, String> locMap = locationNameMap(); // 只构造一次（原本构造了两次）
                // v5.8.33：组合态「在读 · 借给 谁」用的在外人名，来自 ShelfLoans 已有的那次查询，不额外查库
                Map<Long, java.util.List<String>> lenderMap = loans.lenders();
                if (viewMode == 1) {
                    gridAdapter.setLenders(lenderMap);
                    gridAdapter.submit(result);
                } else if (viewMode == 2) {
                    textAdapter.submit(result, locMap, lenderMap);
                } else {
                    adapter.submit(result, locMap, lenderMap);
                }
                statusView.updateEmptyState(result.isEmpty(), fKeyword, filtered);
            });
        });
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
        if (filterState.hasSeries()) count++;
        if (filterState.hasPrice()) count++;
        if (filterState.hasPurchaseRange()) count++;
        if (filterState.hasEntryRange()) count++;
        return count;
    }

    // ======================= 智能书架（v4.42，UI 见 SmartShelfBar） =======================

    /**
     * 全库物理册数（v5.8 双口径）：册数要读逐本副本表，只能在 io 线程算，
     * 算完缓存到这里，页头 {@link ShelfStatusView#updateMeta} 直接用。
     */
    private int ownedCopies;

    /**
     * 数据变化后更新搜索索引（v5.8.20 改增量）。
     *
     * <p>拼音转写很贵（真机实测 1000 本全量重建 3.6 秒），以前每改一本书就整表重来一遍，
     * 还要重读全部摘抄。现在按 updatedAt 找出真正变过的书，只重算这些本；
     * 摘抄表用一条指纹查询判断有没有变，变了才重读。首次或改动面超过四分之一时才整表重建。
     */
    private void refreshSearchIndexAsync() {
        final List<Book> snapshot = new ArrayList<>(allBooks);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            String fingerprint = null;
            try {
                fingerprint = app.db().excerptDao().fingerprint();
            } catch (Exception ignored) {
                // 读不到指纹就当摘抄没变，最多晚一轮再修
            }
            final boolean excerptsChanged = fingerprint != null
                    && !fingerprint.equals(excerptFingerprint);
            if (fingerprint != null) {
                excerptFingerprint = fingerprint;
            }

            final Set<Long> dirty;
            final Set<Long> gone;
            List<com.bai.bookshelf.data.entity.Excerpt> allExcerpts = null;
            if (excerptsChanged) {
                try {
                    allExcerpts = app.db().excerptDao().getAll();
                } catch (Exception ignored) {
                    allExcerpts = new ArrayList<>();
                }
            }
            Set<Long> touched = new HashSet<>();
            if (allExcerpts != null) {
                for (com.bai.bookshelf.data.entity.Excerpt e : allExcerpts) {
                    touched.add(e.bookId);
                }
            }
            dirty = com.bai.bookshelf.util.SearchIndex.dirtyIds(snapshot, indexedStamps, touched);
            gone = com.bai.bookshelf.util.SearchIndex.removedIds(indexedStamps.keySet(), snapshot);
            // 首次、记账与索引对不上、或改动面超过四分之一时，整表重建更划算
            final boolean full = com.bai.bookshelf.util.SearchIndex.needsFullRebuild(
                    indexedStamps.isEmpty() && !snapshot.isEmpty(),
                    searchIndex.size(), indexedStamps.size(), dirty.size(), snapshot.size());
            final List<Book> targets = new ArrayList<>();
            if (!full) {
                for (Book book : snapshot) {
                    if (dirty.contains(book.id)) {
                        targets.add(book);
                    }
                }
            }
            final Map<Long, String> rebuilt;
            final Map<Long, Long> rebuiltStamps = new HashMap<>();
            if (full) {
                List<com.bai.bookshelf.data.entity.Excerpt> excerpts = allExcerpts;
                if (excerpts == null) {
                    try {
                        excerpts = app.db().excerptDao().getAll();
                    } catch (Exception e) {
                        excerpts = new ArrayList<>();
                    }
                }
                rebuilt = com.bai.bookshelf.util.SearchIndex.build(snapshot, excerpts);
                for (Book book : snapshot) {
                    rebuiltStamps.put(book.id, book.updatedAt);
                }
            } else {
                rebuilt = new HashMap<>(searchIndex);
                rebuiltStamps.putAll(indexedStamps);
                for (Long id : gone) {
                    rebuilt.remove(id);
                    rebuiltStamps.remove(id);
                }
                for (Book book : targets) {
                    String text = "";
                    try {
                        text = com.bai.bookshelf.util.SearchIndex.excerptText(
                                app.db().excerptDao().getByBook(book.id));
                    } catch (Exception ignored) {
                    }
                    rebuilt.put(book.id,
                            com.bai.bookshelf.util.SearchIndex.forBook(book, text));
                    rebuiltStamps.put(book.id, book.updatedAt);
                }
            }
            final Map<Long, String> map = rebuilt;
            int copiesResult = -1;
            try {
                copiesResult = com.bai.bookshelf.util.LibraryStats.countOwnedCopies(snapshot,
                        com.bai.bookshelf.util.LibraryStats.groupCopies(
                                app.db().bookCopyDao().getAll()));
            } catch (Exception ignored) {
                // 读不到副本表就退回按种数显示，页头不该因此空着
            }
            final int copies = copiesResult;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                int titles = com.bai.bookshelf.util.BookFlags.countOwned(allBooks);
                ownedCopies = copies >= 0 ? copies : titles;
                searchIndex.clear();
                searchIndex.putAll(map);
                indexedStamps.clear();
                indexedStamps.putAll(rebuiltStamps);
                // 册数与种数一起决定页头文案，索引回来时顺手刷新一次
                statusView.updateMeta(titles, ownedCopies, locations.size());
                // 索引晚于输入就绪时补一次筛选（此时才可能命中拼音）
                if (!searchBar.normalized().isEmpty()) {
                    applyFilters();
                }
            });
        });
    }

    private Map<Long, String> locationNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Location loc : locations) {
            map.put(loc.id, loc.name);
        }
        return map;
    }

    @Override
    public void onResume() {
        super.onResume();
        // v5.8.16：详情页删书时本页还没有锚点，回到书架补弹撤销条
        if (b != null) {
            com.bai.bookshelf.util.UndoBar.attach(b.getRoot());
        }
        // 逐本借出/归还不改 books 表，Room 不会推送——回到书架时单独刷新借出中记录
        if (b != null && booksReceived) {
            loans.refreshAsync(BookshelfApp.get(), this::applyFilters);
        }
    }

    @Override
    public void onDestroyView() {
        loading.cancel();
        if (searchBar != null) {
            searchBar.cancelPending();
            searchBar = null;
        }
        selection.unbind();
        super.onDestroyView();
        b = null;
    }
}
