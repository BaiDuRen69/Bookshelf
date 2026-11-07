package com.bai.bookshelf.ui;

/**
 * Tab 重新可见时的统一刷新钩子。
 *
 * <p>MainActivity 用 add + show/hide 复用四个 Fragment，切 Tab 时 Fragment 的
 * onResume 不会再走一遍；从详情页/编辑页返回时也需要一个明确的重取数据入口。
 * 由 MainActivity 在 selectTab 与 onResume 里各调一次，页面自己做节流，
 * 不改 show/hide 结构、不新增广播或结果码。
 */
public interface TabRefresh {
    void onTabShown();
}
