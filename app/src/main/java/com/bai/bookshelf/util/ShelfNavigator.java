package com.bai.bookshelf.util;

import android.app.Activity;
import android.content.Intent;

import com.bai.bookshelf.ui.MainActivity;

/**
 * 跳转到书架页并按关键词搜索（v3.0 抽出，收拢统计页与三个分布二级页的重复跳转代码）。
 *
 * <p>背景：统计页的分布条目与「作者/出版社/国家分布」三个二级页都需要「点了条目 → 去书架看搜索结果」。
 * 跳转的关键点有两个：
 * <ol>
 *   <li>用 {@link Intent#FLAG_ACTIVITY_REORDER_TO_FRONT} 复用已存在的主界面实例，避免重复建栈；</li>
 *   <li>主界面在 {@code onCreate} 与 {@code onNewIntent} 两条路径都会读取并移除该 extra，不会重复触发。</li>
 * </ol>
 */
public final class ShelfNavigator {

    private ShelfNavigator() {
    }

    /** 跳书架页并展示 keyword 的搜索结果 */
    public static void search(Activity from, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        Intent intent = new Intent(from, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_SEARCH_QUERY, keyword.trim());
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(intent);
    }
}
