package com.bai.bookshelf.ui;

import com.bai.bookshelf.app.BookshelfApp;
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
import com.bai.bookshelf.ui.mine.MineFragment;
import com.bai.bookshelf.ui.shelf.ShelfFragment;
import com.bai.bookshelf.ui.stats.StatsFragment;
import com.bai.bookshelf.util.InsetsHelper;

/**
 * 主界面：承载底部三 Tab（书架 / 统计 / 我的）。
 * 底部导航为自定义布局（图标+文字间距固定 2dp，避免系统组件的间距问题）。
 */
public class MainActivity extends BaseActivity {

    /** 二级页/外部带入的搜索词 Intent Extra（v2.9：国家/出版社/作者二级页点击跳转用） */
    public static final String EXTRA_SEARCH_QUERY = "search_query";

    /** 直接打开某个 Tab（v5.8.16：全局搜索里「自动备份 / 应用锁」这类入口用） */
    public static final String EXTRA_TAB = "tab_index";
    /** 扫书架二维码：切到书架并按该地点筛选（v5.8.18） */
    public static final String EXTRA_LOCATION_ID = "shelf_location_id";

    // v4.5：三个 Tab 挂载到 FragmentManager 时的 tag，用于 Activity 重建后找回还原实例
    private static final String TAG_SHELF = "tab_shelf";
    private static final String TAG_STATS = "tab_stats";
    private static final String TAG_MINE = "tab_mine";

    /** v4.23：保存当前 Tab 索引的 key（旋转屏/进程回收后恢复） */
    private static final String KEY_TAB = "main_current_tab";

    /** v4.23：当前选中的 Tab 索引（0=书架 1=统计 2=我的），供 onSaveInstanceState 保存 */
    private int currentTab = 0;

    // v4.5：不再用字段初始化直接 new——Activity 重建（旋转屏/进程被杀后恢复）时
    // FragmentManager 会还原上次的实例，字段必须改指还原实例，否则 selectTab 会
    // 把新实例再 add 一遍，新旧两套叠加（旧书架 hide 不掉，透在其它 Tab 下面）
    private ShelfFragment shelfFragment;
    private Fragment statsFragment;
    private Fragment mineFragment;

    private ActivityMainBinding binding;

    /** v4.1：三个 Tab 的 Fragment 是否已挂载（show/hide 复用模式） */
    private boolean fragmentsAdded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v5.8.7：冷启动一镜到底 —— 字标淡出、内容淡入（compat 库，24+ 统一行为）
        installSplashExit();
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // v4.18：Android 15+ 强制 edge-to-edge，底部导航会被系统手势条压住，
        // 给它加导航栏内边距，让三个 Tab 上移到手势条上方
        InsetsHelper.applyBottomInset(binding.bottomNav);
        // v5.8.25：dock 重新布局时把圆形指示器无动画归位（首次落位也走这里）
        bindNavIndicator();

        // v4.5：优先复用 FragmentManager 还原的实例（带 tag 找回），没有才新建
        if (savedInstanceState != null) {
            shelfFragment = (ShelfFragment) getSupportFragmentManager().findFragmentByTag(TAG_SHELF);
            statsFragment = getSupportFragmentManager().findFragmentByTag(TAG_STATS);
            mineFragment = (MineFragment) getSupportFragmentManager().findFragmentByTag(TAG_MINE);
        }
        if (shelfFragment == null) {
            shelfFragment = new ShelfFragment();
        }
        if (statsFragment == null) {
            statsFragment = new StatsFragment();
        }
        if (mineFragment == null) {
            mineFragment = new MineFragment();
        }

        binding.tabShelf.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(0);
        });
        binding.tabStats.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(1);
        });
        binding.tabMine.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectTab(2);
        });

        // v4.23：恢复旋转屏/进程回收前的 Tab（savedInstanceState 为 null 时默认书架）
        int restoredTab = 0;
        if (savedInstanceState != null) {
            restoredTab = savedInstanceState.getInt(KEY_TAB, 0);
        }
        selectTab(restoredTab);
        refreshTabBadges();

        // v2.9：被二级页以 REORDER_TO_FRONT 重新拉起时，走 onNewIntent；
        // 全新实例启动时（冷启动带 extra），在 onCreate 里应用
        handleSearchIntent(getIntent());

        // v4.7：冷启动时检查应用更新（受「我的 → 应用更新」开关与忽略选项控制）。
        // 仅全新启动检查一次，旋转屏/进程恢复重建（savedInstanceState != null）不重复弹。
        if (savedInstanceState == null) {
            com.bai.bookshelf.util.UpdateChecker.checkOnLaunch(this);
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
        if (tab >= 0 && tab <= 2) {
            intent.removeExtra(EXTRA_TAB);
            selectTab(tab);
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
        // 消费掉，避免旋转屏幕/重复 onNewIntent 时重复应用
        intent.removeExtra(EXTRA_SEARCH_QUERY);
        searchOnShelf(q.trim());
    }

    /** 切换 Tab：显示目标 Fragment、隐藏其余两个 + 更新三个 Tab 的选中配色 */
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

    private void selectTab(int index) {
        int previousTab = currentTab;
        currentTab = index;
        Fragment target = index == 1 ? statsFragment : (index == 2 ? mineFragment : shelfFragment);
        if (!fragmentsAdded) {
            // 首次进入：一次性挂载三个 Tab 的 Fragment（v4.1，评审 #8）。
            // 旧实现每次切 Tab 都 replace()，Fragment 连同视图销毁重建——
            // 逼出了 ShelfFragment 一整套「视图重建补偿」逻辑（v3.3/v3.5），
            // 统计页也要每次重新聚合。改为 add 一次 + show/hide 复用实例，
            // 切 Tab 只切可见性，视图与数据原地保留。
            // v4.5 修复：挂载时带上 tag；若 FragmentManager 里已有还原的同名
            // 实例（Activity 重建），只 show/hide、绝不重复 add，杜绝新旧两套
            // Fragment 叠加导致「旧书架透在统计/我的下面」。
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
            Fragment[] fs = {shelfFragment, statsFragment, mineFragment};
            String[] tags = {TAG_SHELF, TAG_STATS, TAG_MINE};
            for (int i = 0; i < fs.length; i++) {
                if (fm.findFragmentByTag(tags[i]) == null) {
                    tx.add(R.id.container, fs[i], tags[i]);
                }
                if (fs[i] == target) {
                    tx.show(fs[i]); // 还原实例可能处于 hidden 状态，目标页强制显示
                } else {
                    tx.hide(fs[i]);
                }
            }
            tx.commitNowAllowingStateLoss(); // 同步执行，保证 view 立即可用（搜索词兜底依赖）
            fragmentsAdded = true;
        } else {
            androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            for (Fragment f : new Fragment[]{shelfFragment, statsFragment, mineFragment}) {
                if (f == target) {
                    tx.show(f);
                } else {
                    tx.hide(f);
                }
            }
            tx.commit();
        }

        // v4.24：未选中用线性图标，只有选中的 Tab 换成填充图标
        // v5.8.25：配色交给 res/color/nav_icon_tint 选择器，代码只负责 setSelected
        setTab(binding.ivTabShelf, 0, index == 0);
        setTab(binding.ivTabStats, 1, index == 1);
        setTab(binding.ivTabMine, 2, index == 2);
        // 圆形指示器：首帧直接落位，之后滑动跟随
        moveNavIndicator(index, navAnimated);
        navAnimated = true;
        // v4.30：真交叉淡入——旧页 110ms 淡出后再把新页 180ms 淡入，
        // 避免「瞬间换页 + 新页淡入」看起来像硬切
        if (previousTab != index) {
            android.view.View targetView = target.getView();
            if (targetView != null) {
                targetView.setAlpha(0f);
                targetView.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    /**
     * v5.8.25：dock 每次重新布局（含首次测量、手势条内边距变化）都把指示器无动画归位，
     * 避免指示器停在旧坐标上。
     */
    private void bindNavIndicator() {
        binding.navDock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                moveNavIndicator(currentTab, false));
    }

    /** 把圆形指示器移到第 index 个 tab 的中心；animate=false 用于首次布局 */
    private void moveNavIndicator(int index, boolean animate) {
        View[] items = {binding.tabShelf, binding.tabStats, binding.tabMine};
        View target = items[index];
        if (target.getWidth() == 0) {
            target.post(() -> moveNavIndicator(index, false));
            return;
        }
        float to = target.getX() + target.getWidth() / 2f
                - binding.navIndicator.getWidth() / 2f;
        if (!animate) {
            binding.navIndicator.animate().cancel();
            binding.navIndicator.setTranslationX(to);
            return;
        }
        binding.navIndicator.animate().cancel();
        binding.navIndicator.animate()
                .translationX(to)
                .setDuration(240)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .start();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        // v4.23：旋转屏后停在原 Tab，而不是总是跳回书架
        outState.putInt(KEY_TAB, currentTab);
    }

    /** 单个 Tab 的图标：选中换填充图形 + setSelected 走 nav_icon_tint 配色 */
    private void setTab(ImageView icon, int tabIndex, boolean selected) {
        int res;
        if (tabIndex == 0) {
            res = selected ? R.drawable.ic_book_filled : R.drawable.ic_book_line;
        } else if (tabIndex == 1) {
            res = selected ? R.drawable.ic_chart : R.drawable.ic_chart_line;
        } else {
            res = selected ? R.drawable.ic_person : R.drawable.ic_person_line;
        }
        icon.setImageResource(res);
        // v5.8.25：颜色由 res/color/nav_icon_tint.xml 的状态选择器负责
        icon.setSelected(selected);
        // v5.8.7：选中图标 0.9 → 1，未选中回落
        icon.animate().scaleX(selected ? 1f : 0.92f).scaleY(selected ? 1f : 0.92f)
                .setDuration(120).start();
    }

    /** 底部角标（v5.8.7）：统计 = 逾期笔数，我的 = 想读本数 */
    private void refreshTabBadges() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            int overdue = 0;
            int wish = 0;
            try {
                int today = com.bai.bookshelf.util.DateKeys.todayKey();
                for (com.bai.bookshelf.data.entity.BorrowRecord r
                        : app.db().borrowRecordDao().getAllOpen()) {
                    int due = com.bai.bookshelf.util.DateKeys.parseKey(r.dueDate);
                    if (due > 0 && due < today) {
                        overdue++;
                    }
                }
                wish = app.db().wishBookDao().getAll().size();
            } catch (Exception ignored) {
                // 库还没就绪时先不显示角标
            }
            final int fOverdue = overdue;
            final int fWish = wish;
            app.ui(() -> {
                bindBadge(binding.tvBadgeStats, fOverdue);
                bindBadge(binding.tvBadgeMine, fWish);
            });
        });
    }

    private void bindBadge(TextView badge, int count) {
        if (badge == null) {
            return;
        }
        if (count <= 0) {
            badge.setVisibility(android.view.View.GONE);
            return;
        }
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setContentDescription(count + " 条待处理");
        badge.setVisibility(android.view.View.VISIBLE);
    }

    /** Splash 退场：字标淡出放大，主内容随后淡入 */
    private void installSplashExit() {
        androidx.core.splashscreen.SplashScreen splash =
                androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(provider -> {
            android.view.View icon = provider.getIconView();
            if (icon != null) {
                icon.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f)
                        .setDuration(180)
                        .withEndAction(provider::remove)
                        .start();
            } else {
                provider.remove();
            }
        });
    }

    /**
     * 统计页/二级页点击作者/出版社/国家分布条目：切到书架 Tab 并按该关键词搜索。
     * v2.9 修复：commit 是异步的，原先切 Tab 后立刻灌搜索词会落到尚未创建的视图上
     * （表现为「只跳转、不搜索」）。先 executePendingTransactions 强制事务执行完，
     * 保证书架视图已就绪，再由 ShelfFragment 把关键词灌进搜索框。
     */
    public void searchOnShelf(String keyword) {
        selectTab(0);
        getSupportFragmentManager().executePendingTransactions();
        shelfFragment.requestSearch(keyword);
    }

    /** 扫书架二维码：切到书架并只看这个地点（v5.8.18） */
    public void filterShelfByLocation(long locationId) {
        selectTab(0);
        getSupportFragmentManager().executePendingTransactions();
        if (shelfFragment != null) {
            shelfFragment.requestLocationFilter(locationId);
        }
    }
}
