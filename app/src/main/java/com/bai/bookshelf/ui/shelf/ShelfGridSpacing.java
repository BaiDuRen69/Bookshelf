package com.bai.bookshelf.ui.shelf;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 书架网格的间距分配器（v4.18 引入，v4.19 调整）。
 *
 * <p>反馈「书籍之间的间距过大」：原先每个 item 自带 marginHorizontal=4dp，
 * 加上卡片内边距，视觉上书籍之间空隙明显偏大。这里改为由本分配器统一
 * 分配「列间距 / 行间距」，item 自身不再带 margin，间距完全可控。
 *
 * <p><b>v4.19 调整：</b>原先把「页面左右留白」也算进列偏移里，结果是
 * 首列与末列各多吃了半份留白、中间列没有 —— GridLayoutManager 按 span 平分宽度后，
 * 三列的内容宽度变成 320 / 344 / 320，封面（统一高度）比例参差不齐，
 * 整屏看上去很「奇怪」。现在改成：
 * <ul>
 *   <li>页面左右留白交给 RecyclerView 自身的 padding（见 ShelfFragment.applyViewMode）；</li>
 *   <li>本分配器只负责列间距，且<b>每一列左右各留一半</b>，
 *       保证所有列的内容宽度完全一致。</li>
 * </ul>
 */
public class ShelfGridSpacing extends RecyclerView.ItemDecoration {

    /** 列间距（px） */
    private final int spacingX;
    /** 行间距（px） */
    private final int spacingY;
    /** 当前列数 */
    private int spanCount;

    public ShelfGridSpacing(int spanCount, int spacingX, int spacingY) {
        this.spanCount = Math.max(1, spanCount);
        this.spacingX = spacingX;
        this.spacingY = spacingY;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) {
            return;
        }
        GridLayoutManager glm = (GridLayoutManager) lm;
        int span = glm.getSpanCount();
        if (span != spanCount) {
            spanCount = span;
        }
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        // 左右：每列各留一半列间距 —— 所有列的内容宽度因此完全相同
        outRect.left = spacingX / 2;
        outRect.right = spacingX / 2;
        // 上下：首行不额外留白，其余行给行间距
        outRect.top = position < spanCount ? 0 : spacingY;
        outRect.bottom = 0;
    }
}
