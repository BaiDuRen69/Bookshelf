package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 书架的视图模式（v5.6 从 ShelfFragment 抽出）：
 * 列表 / 封面网格 / 纯文字三态切换、网格间距分配器与页面留白、
 * 行尾排序/倒序/视图三个图标的当前状态。
 */
public class ShelfViewMode {

    private final Context context;
    private final java.util.function.Supplier<FragmentShelfBinding> binding;
    private final java.util.function.Supplier<BookAdapter> adapter;
    private final java.util.function.Supplier<BookAdapter> textAdapter;
    private final java.util.function.Supplier<BookGridAdapter> gridAdapter;
    private final java.util.function.IntSupplier viewMode;
    private final java.util.function.IntSupplier sortIndex;
    private final java.util.function.BooleanSupplier sortReversed;
    private final Runnable applyFilters;
    private final Runnable showLoadingAgain;
    private ShelfGridSpacing gridSpacing;

    public ShelfViewMode(Context context,
                         java.util.function.Supplier<FragmentShelfBinding> binding,
                         java.util.function.Supplier<BookAdapter> adapter,
                         java.util.function.Supplier<BookAdapter> textAdapter,
                         java.util.function.Supplier<BookGridAdapter> gridAdapter,
                         java.util.function.IntSupplier viewMode,
                         java.util.function.IntSupplier sortIndex,
                         java.util.function.BooleanSupplier sortReversed,
                         Runnable applyFilters,
                         Runnable showLoadingAgain) {
        this.context = context;
        this.binding = binding;
        this.adapter = adapter;
        this.textAdapter = textAdapter;
        this.gridAdapter = gridAdapter;
        this.viewMode = viewMode;
        this.sortIndex = sortIndex;
        this.sortReversed = sortReversed;
        this.applyFilters = applyFilters;
        this.showLoadingAgain = showLoadingAgain;
    }

    /** 切换 RecyclerView 的视图模式（列表 / 封面网格 / 纯文字 三态循环） */
    public void apply() {
        FragmentShelfBinding b = binding.get();
        if (b == null) {
            return;
        }
        // v4.18：切换视图前清掉旧的间距分配器，避免叠加导致间距越来越大
        b.recycler.removeItemDecoration(gridSpacing);
        gridSpacing = null;
        int mode = viewMode.getAsInt();
        if (mode == 1) {
            // v4.16：网格列数随屏幕宽度自适应（values / sw600dp / sw840dp 覆盖）
            int span = context.getResources().getInteger(R.integer.shelf_grid_span);
            b.recycler.setLayoutManager(new GridLayoutManager(context, span));
            b.recycler.setAdapter(gridAdapter.get());
            // v4.18：间距统一由分配器控制（item 自身不再带 margin）
            // v4.19：页面左右留白改由 RecyclerView 的 padding 承担，
            // 分配器只管列间距且每列左右对称 —— 这样 GridLayoutManager
            // 平分出来的每一列内容宽度完全相同，封面比例才不会参差不齐。
            float density = context.getResources().getDisplayMetrics().density;
            int spacingX = Math.round(6 * density);
            int spacingY = Math.round(10 * density);
            int edge = context.getResources().getDimensionPixelSize(R.dimen.page_padding);
            setEdgePadding(edge);
            gridSpacing = new ShelfGridSpacing(span, spacingX, spacingY);
            b.recycler.addItemDecoration(gridSpacing);
        } else if (mode == 2) {
            b.recycler.setLayoutManager(new LinearLayoutManager(context));
            b.recycler.setAdapter(textAdapter.get());
            setEdgePadding(0);
        } else {
            b.recycler.setLayoutManager(new LinearLayoutManager(context));
            b.recycler.setAdapter(adapter.get());
            setEdgePadding(0);
        }
        // v3.2：改成「只渲染当前视图」后，换适配器必须重新喂一次数据，
        // 否则新挂载的适配器是空的。首次加载时 allBooks 为空，applyFilters 会直接返回。
        updateIcons();
        FragmentShelfBinding bind = binding.get();
        if (bind != null && bind.llLoading.getVisibility() == View.VISIBLE) {
            showLoadingAgain.run(); // 骨架屏跟随视图：网格用九宫格，列表用行
        }
        applyFilters.run();
    }

    /**
     * 设置列表左右留白（网格模式用；列表模式由 item 自带 marginHorizontal 处理）。
     *
     * <p>v4.19：网格模式下把页面留白放到 RecyclerView 的 padding 上，
     * 让 GridLayoutManager 在「扣掉留白后的宽度」里平分列，每列宽度才一致。
     */
    private void setEdgePadding(int edgePx) {
        FragmentShelfBinding b = binding.get();
        if (b == null || b.recycler == null) {
            return;
        }
        b.recycler.setPadding(edgePx, b.recycler.getPaddingTop(),
                edgePx, b.recycler.getPaddingBottom());
    }

    /** 行尾三个轻量图标的当前状态（排序激活 / 倒序激活 / 当前视图） */
    public void updateIcons() {
        FragmentShelfBinding b = binding.get();
        if (b == null) {
            return;
        }
        int active = ThemeManager.resolveColor(context, ThemeManager.ATTR_COLOR_PRIMARY);
        int inactive = ThemeManager.resolveColor(context, R.attr.on_surface_variant);
        String[] sortNames = context.getResources().getStringArray(R.array.sort_entries);
        int sort = sortIndex.getAsInt();
        int safeSort = Math.max(0, Math.min(sort, sortNames.length - 1));

        b.btnSort.setImageTintList(ColorStateList.valueOf(sort > 0 ? active : inactive));
        b.btnSort.setContentDescription("排序：" + sortNames[safeSort] + "，点击更改");

        boolean reversed = sortReversed.getAsBoolean();
        b.btnSortReverse.setImageTintList(ColorStateList.valueOf(reversed ? active : inactive));
        b.btnSortReverse.setBackground(reversed
                ? ContextCompat.getDrawable(context, R.drawable.bg_icon_button_active)
                : borderlessBackground());
        b.btnSortReverse.setContentDescription(reversed ? "已开启倒序，点击关闭" : "倒序，点击开启");

        int mode = viewMode.getAsInt();
        int icon;
        String viewName;
        if (mode == 1) {
            icon = R.drawable.ic_view_grid;
            viewName = "封面";
        } else if (mode == 2) {
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
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            return ContextCompat.getDrawable(context, tv.resourceId);
        }
        return null;
    }
}
