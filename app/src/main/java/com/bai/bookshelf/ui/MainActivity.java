package com.bai.bookshelf.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
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
import com.bai.bookshelf.util.ThemeManager;

/**
 * 主界面：承载底部三 Tab（书架 / 统计 / 我的）。
 * 底部导航为自定义布局（图标+文字间距固定 2dp，避免系统组件的间距问题）。
 */
public class MainActivity extends BaseActivity {

    /** 二级页/外部带入的搜索词 Intent Extra（v2.9：国家/出版社/作者二级页点击跳转用） */
    public static final String EXTRA_SEARCH_QUERY = "search_query";

    // v4.5：三个 Tab 挂载到 FragmentManager 时的 tag，用于 Activity 重建后找回还原实例
    private static final String TAG_SHELF = "tab_shelf";
    private static final String TAG_STATS = "tab_stats";
    private static final String TAG_MINE = "tab_mine";

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
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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

        binding.tabShelf.setOnClickListener(v -> selectTab(0));
        binding.tabStats.setOnClickListener(v -> selectTab(1));
        binding.tabMine.setOnClickListener(v -> selectTab(2));

        // 默认展示书架
        selectTab(0);

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

    /** 处理「跳到书架并搜索某关键词」的 Intent（来自统计页或二级页） */
    private void handleSearchIntent(android.content.Intent intent) {
        if (intent == null) {
            return;
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
    private void selectTab(int index) {
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

        int active = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        int inactive = ContextCompat.getColor(this, R.color.text_sub);
        tintTab(binding.ivTabShelf, binding.tvTabShelf, index == 0 ? active : inactive);
        tintTab(binding.ivTabStats, binding.tvTabStats, index == 1 ? active : inactive);
        tintTab(binding.ivTabMine, binding.tvTabMine, index == 2 ? active : inactive);
    }

    /** 单个 Tab 的图标与文字统一着色 */
    private void tintTab(ImageView icon, TextView label, int color) {
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
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
}
