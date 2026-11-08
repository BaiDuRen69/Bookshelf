package com.bai.bookshelf.ui;

/**
 * Tab 重新可见时的统一刷新钩子，以及切 Tab 的内容层动效。
 *
 * <p>MainActivity 用 add + show/hide 复用四个 Fragment，切 Tab 时 Fragment 的
 * onResume 不会再走一遍；从详情页/编辑页返回时也需要一个明确的重取数据入口。
 * 由 MainActivity 在 selectTab 与 onResume 里各调一次，页面自己做节流，
 * 不改 show/hide 结构、不新增广播或结果码。
 */
public interface TabRefresh {
    /** Tab 重新可见时刷新数据；需要刷新的页面才覆盖 */
    default void onTabShown() {
    }

    /** 切走/切入前把内容层复位：取消动画、位移归零，避免带着残留状态回来 */
    default void settleContent() {
    }

    /**
     * 切到本页后内容层 6dp 上浮。只动内容容器、不碰页头与整页透明度，
     * 屏上始终只有一个不透明活页，位移露出的只是本页底色。
     */
    default void animateIn() {
    }
}
