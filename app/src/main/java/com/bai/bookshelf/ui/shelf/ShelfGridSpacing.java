package com.bai.bookshelf.ui.shelf;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 书架网格的间距分配器（v4.18）。
 *
 * <p>反馈「书籍之间的间距过大」：原先每个 item 自带 marginHorizontal=4dp，
 * 加上卡片内边距，视觉上书籍之间空隙明显偏大。这里改为由本分配器统一
 * 分配「列间距 / 行间距」，item 自身不再带 margin，间距完全可控。
 *
 * <p>同时按列数动态调整封面高度：列数越多（平板/折叠屏），单列越窄，
 * 封面若保持固定高度会显得又高又窄，因此按「列数越少、封面越高」的
 * 规律换算，使各设备上的书籍比例都接近真实书籍（约 3:4）。
 */
public class ShelfGridSpacing extends RecyclerView.ItemDecoration {

    /** 列间距（px） */
    private final int spacingX;
    /** 行间距（px） */
    private final int spacingY;
    /** 左边界留白（px） */
    private final int edge;
    /** 当前列数 */
    private int spanCount;

    public ShelfGridSpacing(int spanCount, int spacingX, int spacingY, int edge) {
        this.spanCount = Math.max(1, spanCount);
        this.spacingX = spacingX;
        this.spacingY = spacingY;
        this.edge = edge;
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
        int column = position % spanCount;

        // 左右：首列贴左边距，其余列各分一份列间距
        if (column == 0) {
            outRect.left = edge;
            outRect.right = spacingX / 2;
        } else if (column == spanCount - 1) {
            outRect.left = spacingX / 2;
            outRect.right = edge;
        } else {
            outRect.left = spacingX / 2;
            outRect.right = spacingX / 2;
        }
        // 上下：首行不额外留白，其余行给行间距
        outRect.top = position < spanCount ? 0 : spacingY;
        outRect.bottom = 0;
    }

    /**
     * 按列数计算封面高度：列数越少单列越宽，封面相应更高，
     * 使书籍在手机 / 平板上的视觉比例接近真实书籍。
     */
    public static int coverHeightFor(int spanCount, int availableWidthPx, int spacingXPx, int edgePx) {
        int cols = Math.max(1, spanCount);
        int usable = availableWidthPx - edgePx * 2 - spacingXPx * (cols - 1);
        int itemWidth = usable / cols;
        // 真实书籍封面约 3:4（宽:高），此处按 0.75 倍放大一点，视觉更饱满
        return Math.max(120, (int) (itemWidth * 1.34f));
    }
}
