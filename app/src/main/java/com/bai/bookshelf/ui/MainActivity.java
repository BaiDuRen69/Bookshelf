package com.bai.bookshelf.ui;

import com.bai.bookshelf.app.BookshelfApp;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityMainBinding;
import com.bai.bookshelf.ui.checkin.CheckInFragment;
import com.bai.bookshelf.ui.mine.MineFragment;
import com.bai.bookshelf.ui.shelf.ShelfFragment;
import com.bai.bookshelf.ui.stats.StatsFragment;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.SplashExit;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 主界面：承载底部四 Tab（书架 / 统计 / 打卡 / 我的）。
 * v5.10：Tab 改数组驱动，顺序固定；dock 等分 4 格，指示器按 items[index] 定位。
 */
public class MainActivity extends BaseActivity {

    /** 顺序固定「书架 / 统计 / 打卡 / 我的」，不要把打卡挪到末尾 */
    public static final int TAB_SHELF = 0;
    public static final int TAB_STATS = 1;
    public static final int TAB_CHECKIN = 2;
    public static final int TAB_MINE = 3;

    /** 二级页/外部带入的搜索词 Intent Extra（v2.9：国家/出版社/作者二级页点击跳转用） */
    public static final String EXTRA_SEARCH_QUERY = "search_query";

    /** 直接打开某个 Tab（v5.8.16：全局搜索里「自动备份 / 应用锁」这类入口用） */
    public static final String EXTRA_TAB = "tab_index";
    /** 扫书架二维码：切到书架并按该地点筛选（v5.8.18） */
    public static final String EXTRA_LOCATION_ID = "shelf_location_id";
    /** 到期通知点进来：直接切到统计借阅栏的在借清单（v5.8.32） */
    public static final String EXTRA_OPEN_LOANS = "open_loans";

    private static final String[] TAB_TAGS = {
            "tab_shelf", "tab_stats", "tab_checkin", "tab_mine"};

    /** v4.23：保存当前 Tab 索引的 key（旋转屏/进程回收后恢复） */
    private static final String KEY_TAB = "main_current_tab";
    /**
     * v5.10 一次性兼容标记：旧版存档只存 KEY_TAB，其中 2 = 我的；
     * 打卡插到第 3 位后「我的」变成 3。新存档一定带这个标记，
     * 所以只有真正来自四 Tab 之前版本的 Bundle 才会做 2 → 3 转换。
     */
    private static final String KEY_TAB_MIGRATED = "main_tab_migrated_v4";

    /** v4.23：当前选中的 Tab 索引，供 onSaveInstanceState 保存 */
    private int currentTab = 0;

    // v4.5：Activity 重建时 FragmentManager 会还原旧实例，字段必须改指还原实例，
    // 否则 selectTab 会把新实例再 add 一遍，新旧两套叠加。
    private final Fragment[] tabs = new Fragment[4];

    private ActivityMainBinding binding;

    /** v4.1：四个 Tab 的 Fragment 是否已挂载（show/hide 复用模式） */
    private boolean fragmentsAdded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v5.8.7：冷启动一镜到底 —— 字标淡出、内容淡入（compat 库，24+ 统一行为）
        // v5.8.32：退场实现收到 util/SplashExit，31+ 交给系统，避开 1.0.1 的 getter NPE
        SplashExit.install(this);
        super.onCreate(savedInstanceState);
        // v5.8.28：把可能被 splash 退场流程覆盖掉的用户主题抢回来。
        ThemeManager.applyTheme(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsHelper.applyBottomInset(binding.bottomNav);
        bindNavIndicator();

        // 优先复用 FragmentManager 还原的实例（带 tag 找回），没有才新建
        if (savedInstanceState != null) {
            for (int i = 0; i < tabs.length; i++) {
                Fragment restored = getSupportFragmentManager()
                        .findFragmentByTag(TAB_TAGS[i]);
                if (restored != null) {
                    tabs[i] = restored;
                }
            }
        }
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) {
                tabs[i] = newTabFragment(i);
            }
        }

        binding.tabShelf.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(TAB_SHELF);
        });
        binding.tabStats.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            // v5.8.32：角标亮着时点 Tab 直接落到逾期清单，省一次翻找
            if (badgeOverdue > 0) {
                focusOverdueLoans();
            } else {
                selectTab(TAB_STATS);
            }
        });
        binding.tabCheckin.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(TAB_CHECKIN);
        });
        binding.tabMine.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(TAB_MINE);
        });

        int restoredTab = 0;
        if (savedInstanceState != null) {
            // 一次性兼容规则抽到纯 JVM 的 TabRestore（TabRestoreTest 覆盖）：
            // 旧存档里 2 = 我的 → 3；新存档带 KEY_TAB_MIGRATED，2 已是打卡，不转换。
            restoredTab = com.bai.bookshelf.util.TabRestore.restore(
                    savedInstanceState.getInt(KEY_TAB, 0),
                    savedInstanceState.getBoolean(KEY_TAB_MIGRATED, false));
        }
        selectTab(restoredTab);
        refreshTabBadges();

        handleSearchIntent(getIntent());

        // v4.7：冷启动时检查应用更新（仅全新启动检查一次）
        if (savedInstanceState == null) {
            com.bai.bookshelf.util.UpdateChecker.checkOnLaunch(this);
        }
    }

    private static Fragment newTabFragment(int index) {
        switch (index) {
            case TAB_STATS:
                return new StatsFragment();
            case TAB_CHECKIN:
                return new CheckInFragment();
            case TAB_MINE:
                return new MineFragment();
            case TAB_SHELF:
            default:
                return new ShelfFragment();
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSearchIntent(intent);
    }

    /** 处理「跳到某个 Tab / 跳到书架并搜索某关键词」的 Intent（统计页、二级页、全局搜索） */
    private void handleSearchIntent(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        int tab = intent.getIntExtra(EXTRA_TAB, -1);
        if (tab >= 0 && tab < tabs.length) {
            intent.removeExtra(EXTRA_TAB);
            selectTab(tab);
        }
        // v5.8.32：到期通知的 contentIntent 带这个标记，点通知直达在借清单
        if (intent.getBooleanExtra(EXTRA_OPEN_LOANS, false)) {
            intent.removeExtra(EXTRA_OPEN_LOANS);
            focusOverdueLoans();
        }
        long locationId = intent.getLongExtra(EXTRA_LOCATION_ID, -1);
        if (locationId <= 0) {
            // 第三方扫码器 / 系统相机走深链进来时，地点在 data 里
            android.net.Uri data = intent.getData();
            if (data != null) {
                locationId = com.bai.bookshelf.util.LocationQr.parse(data.toString());
            }
        }
        if (locationId > 0) {
            intent.removeExtra(EXTRA_LOCATION_ID);
            filterShelfByLocation(locationId);
        }
        String q = intent.getStringExtra(EXTRA_SEARCH_QUERY);
        if (q == null || q.trim().isEmpty()) {
            return;
        }
        intent.removeExtra(EXTRA_SEARCH_QUERY);
        searchOnShelf(q.trim());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTabBadges();
    }

    /**
     * v5.8.25：dock 首次布局完成后才允许指示器做滑动动画。
     * 首帧（含 onCreate 恢复上次 Tab）必须直接落位，否则开机能看到指示器从左边飞过来。
     */
    private boolean navAnimated = false;

    /** 供 Fragment / 二级页切 Tab（打卡空态「打开书架」用） */
    public void showTab(int index) {
        if (index >= 0 && index < tabs.length) {
            selectTab(index);
        }
    }

    private void selectTab(int index) {
        int previousTab = currentTab;
        currentTab = index;
        Fragment target = tabs[index];
        if (!fragmentsAdded) {
            // 首次进入：一次性挂载四个 Tab 的 Fragment（add + show/hide 复用实例）
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
            for (int i = 0; i < tabs.length; i++) {
                if (fm.findFragmentByTag(TAB_TAGS[i]) == null) {
                    tx.add(R.id.container, tabs[i], TAB_TAGS[i]);
                }
                if (i == index) {
                    tx.show(tabs[i]);
                } else {
                    tx.hide(tabs[i]);
                }
            }
            tx.commitNowAllowingStateLoss();
            fragmentsAdded = true;
        } else {
            androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            for (int i = 0; i < tabs.length; i++) {
                if (i == index) {
                    tx.show(tabs[i]);
                } else {
                    tx.hide(tabs[i]);
                }
            }
            tx.commit();
        }

        // v4.24：未选中用线性图标，只有选中的 Tab 换成填充图标
        // 打卡沿用同一枚线性图标，靠 nav_icon_tint 的 selected 态着色
        setTab(binding.ivTabShelf, TAB_SHELF, index == TAB_SHELF);
        setTab(binding.ivTabStats, TAB_STATS, index == TAB_STATS);
        setTab(binding.ivTabCheckin, TAB_CHECKIN, index == TAB_CHECKIN);
        setTab(binding.ivTabMine, TAB_MINE, index == TAB_MINE);
        moveNavIndicator(index, navAnimated);
        navAnimated = true;
        if (previousTab != index) {
            android.view.View targetView = target.getView();
            if (targetView != null) {
                targetView.setAlpha(0f);
                targetView.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    /**
     * v5.8.25：dock 每次重新布局（含首次测量、手势条内边距变化）都把指示器无动画归位。
     */
    private void bindNavIndicator() {
        binding.navDock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                moveNavIndicator(currentTab, false));
    }

    /** 指示器滑动动画：同一个 ValueAnimator 同时驱动 translationX 与 scaleX */
    private ValueAnimator navSlide;

    /** 把圆形指示器移到第 index 个 tab 的中心；animate=false 用于首次布局 */
    private void moveNavIndicator(int index, boolean animate) {
        View[] items = {binding.tabShelf, binding.tabStats,
                binding.tabCheckin, binding.tabMine};
        View target = items[index];
        if (target.getWidth() == 0) {
            target.post(() -> moveNavIndicator(index, false));
            return;
        }
        View pill = binding.navIndicator;
        float to = target.getX() + target.getWidth() / 2f - pill.getWidth() / 2f;
        if (navSlide != null && navSlide.isRunning()) {
            navSlide.cancel();
        }
        if (!animate) {
            pill.setTranslationX(to);
            pill.setScaleX(1f);
            return;
        }
        float from = pill.getTranslationX();
        if (from == to) {
            pill.setScaleX(1f);
            return;
        }
        navSlide = ValueAnimator.ofFloat(0f, 1f);
        navSlide.setDuration(260);
        navSlide.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        navSlide.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            pill.setTranslationX(from + (to - from) * f);
            pill.setScaleX(1f + 0.12f * (float) Math.sin(Math.PI * f));
        });
        navSlide.start();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TAB, currentTab);
        // 之后所有存档都带标记：旧存档里 2 = 我的，新存档里 2 = 打卡
        outState.putBoolean(KEY_TAB_MIGRATED, true);
    }

    /** 单个 Tab 的图标：选中换填充图形 + setSelected 走 nav_icon_tint 配色 */
    private void setTab(ImageView icon, int tabIndex, boolean selected) {
        int res;
        switch (tabIndex) {
            case TAB_STATS:
                res = selected ? R.drawable.ic_chart : R.drawable.ic_chart_line;
                break;
            case TAB_CHECKIN:
                res = R.drawable.ic_checkin_line;
                break;
            case TAB_MINE:
                res = selected ? R.drawable.ic_person : R.drawable.ic_person_line;
                break;
            case TAB_SHELF:
            default:
                res = selected ? R.drawable.ic_book_filled : R.drawable.ic_book_line;
                break;
        }
        icon.setImageResource(res);
        icon.setSelected(selected);
        icon.animate().scaleX(selected ? 1f : 0.92f).scaleY(selected ? 1f : 0.92f)
                .alpha(selected ? 1f : 0.85f).setDuration(200).start();
    }

    /** 底部角标（v5.8.7）：统计 = 借出逾期未还本数 */
    private void refreshTabBadges() {
        BookshelfApp app = BookshelfApp.get();
        if (!com.bai.bookshelf.util.ReminderPrefs.badgeOverdue(this)) {
            badgeOverdue = 0;
            bindBadge(binding.tvBadgeStats, 0);
            return;
        }
        app.io(() -> {
            int overdue = 0;
            try {
                int today = com.bai.bookshelf.util.DateKeys.todayKey();
                for (com.bai.bookshelf.data.entity.BorrowRecord r
                        : app.db().borrowRecordDao().getAllOpen()) {
                    int due = com.bai.bookshelf.util.DateKeys.parseKey(r.dueDate);
                    if (due > 0 && due < today) {
                        overdue++;
                    }
                }
            } catch (Exception ignored) {
                // 库还没就绪时先不显示角标
            }
            final int fOverdue = overdue;
            app.ui(() -> bindBadge(binding.tvBadgeStats, fOverdue));
        });
    }

    /** 当前角标显示的逾期本数，决定点统计 Tab 要不要直达在借清单 */
    private int badgeOverdue = 0;

    private void bindBadge(TextView badge, int count) {
        if (badge == null) {
            return;
        }
        badgeOverdue = count;
        setStatsTooltip(count);
        if (count <= 0) {
            badge.setVisibility(android.view.View.GONE);
            return;
        }
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setContentDescription("逾期 " + count + " 本");
        badge.setVisibility(android.view.View.VISIBLE);
    }

    /** setTooltipText 是 API 26 才有的，minSdk 24 要挡一下 */
    private void setStatsTooltip(int count) {
        if (android.os.Build.VERSION.SDK_INT < 26 || binding == null) {
            return;
        }
        binding.tabStats.setTooltipText(count > 0
                ? "借出逾期未还 " + count + " 本" : "统计");
    }

    /**
     * 直达「逾期未还」：切到统计借阅栏并滚到在借清单（v5.8.32）。
     * commit 是异步的，必须先 executePendingTransactions 把 StatsFragment 的视图建起来。
     */
    public void focusOverdueLoans() {
        selectTab(TAB_STATS);
        getSupportFragmentManager().executePendingTransactions();
        Fragment stats = tabs[TAB_STATS];
        if (stats instanceof StatsFragment) {
            ((StatsFragment) stats).requestOverdueFocus();
        }
    }

    /** 统计页/二级页点作者/出版社/国家：切到书架 Tab 并按该关键词搜索 */
    public void searchOnShelf(String keyword) {
        selectTab(TAB_SHELF);
        getSupportFragmentManager().executePendingTransactions();
        Fragment shelf = tabs[TAB_SHELF];
        if (shelf instanceof ShelfFragment) {
            ((ShelfFragment) shelf).requestSearch(keyword);
        }
    }

    /** 扫书架二维码：切到书架并只看这个地点（v5.8.18） */
    public void filterShelfByLocation(long locationId) {
        selectTab(TAB_SHELF);
        getSupportFragmentManager().executePendingTransactions();
        Fragment shelf = tabs[TAB_SHELF];
        if (shelf instanceof ShelfFragment) {
            ((ShelfFragment) shelf).requestLocationFilter(locationId);
        }
    }
}
