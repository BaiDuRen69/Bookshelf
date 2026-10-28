package com.bai.bookshelf.ui.shelf;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemBookGridBinding;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 书架封面视图适配器：封面大图 + 左上角「纸质书 / 阅读状态」角标 + 下方书名。
 */
public class BookGridAdapter extends RecyclerView.Adapter<BookGridAdapter.VH> {

    /** 列表项点击回调 */
    public interface OnBookClick {
        void onClick(Book book);
    }

    /** 列表项长按回调（进入多选） */
    public interface OnBookLongClick {
        void onLongClick(Book book);
    }

    private final List<Book> data = new ArrayList<>();
    /** v4.41：当前搜索词，用于书名命中高亮 */
    private String keyword = "";
    /** v4.55：上一次提交时的关键词，用于判断是否需要整体重绑高亮 */
    private String lastDiffKeyword = "";
    private final OnBookClick onBookClick;
    private OnBookLongClick onBookLongClick;
    private boolean selectionMode;                 // 多选模式：高亮已选项
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    public BookGridAdapter(OnBookClick onBookClick) {
        this.onBookClick = onBookClick;
        setHasStableIds(true); // v3.2：配合 getItemId 让 ViewHolder 可复用
    }

    public void setOnBookLongClick(OnBookLongClick listener) {
        this.onBookLongClick = listener;
    }

    /** 更新多选状态并刷新（ids 为外部维护的选中集合，这里只读） */
    public void setSelectionState(boolean mode, Set<Long> ids) {
        Set<Long> oldSelected = new HashSet<>(selectedIds);
        boolean modeChanged = selectionMode != mode;
        selectionMode = mode;
        selectedIds.clear();
        if (ids != null) {
            selectedIds.addAll(ids);
        }
        for (int i = 0; i < data.size(); i++) {
            long id = data.get(i).id;
            boolean was = oldSelected.contains(id);
            boolean now = selectedIds.contains(id);
            if (modeChanged ? (was || now) : was != now) {
                notifyItemChanged(i);
            }
        }
    }

    /** 整体替换数据（数据量小，直接全量刷新） */
    /** 设置搜索高亮词（空串 = 不高亮） */
    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword.trim();
    }

    /** 整体替换数据（v4.55：DiffUtil 增量刷新） */
    public void submit(List<Book> books) {
        boolean forceRebind = !keyword.equals(lastDiffKeyword);
        lastDiffKeyword = keyword;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new BookDiffCallback(new ArrayList<>(data), books, forceRebind), false);
        data.clear();
        data.addAll(books);
        diff.dispatchUpdatesTo(this);
    }

    /** v3.2：稳定 ID（书籍主键），让 ViewHolder 能被 RecycledViewPool 复用 */
    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBookGridBinding b = ItemBookGridBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Book book = data.get(position);
        h.b.tvTitle.setText(com.bai.bookshelf.util.SearchText.highlight(
                com.bai.bookshelf.util.BookText.displayTitle(book.title), keyword,
                ThemeManager.resolveColor(h.itemView.getContext(), ThemeManager.ATTR_COLOR_PRIMARY),
                ThemeManager.primaryAlpha(h.itemView.getContext(), 0x33)));
        // 生成封面配 6dp 布面书脊；真实封面只留 2dp 内边缘，避免叠成双书脊
        boolean generatedCover = book.coverUrl.isEmpty();
        h.b.vSpine.setVisibility(generatedCover ? View.VISIBLE : View.GONE);
        h.b.vEdge.setVisibility(generatedCover ? View.GONE : View.VISIBLE);
        bindStatusBadge(h, book);
        bindCover(h, book);

        // v4.19：封面高度交给 RatioImageView 按实测宽度自行换算（3:4），
        // 这里不再做任何高度估算，保证所有列的封面比例完全一致。

        // 多选模式下的选中高亮
        h.itemView.setBackgroundColor(selectionMode && selectedIds.contains(book.id)
                ? ThemeManager.primaryAlpha(h.itemView.getContext(), 0x22)
                : android.graphics.Color.TRANSPARENT);

        h.itemView.setOnClickListener(v -> onBookClick.onClick(book));
        h.itemView.setOnLongClickListener(v -> {
            if (onBookLongClick != null) {
                onBookLongClick.onLongClick(book);
                return true;
            }
            return false;
        });
    }

    /** 状态只保留「借出」「在读」，收成书脊底部的色条（v4.30） */
    private void bindStatusBadge(VH h, Book book) {
        boolean borrowed = book.stockStatus == 1;
        boolean reading = book.readStatus == 1;
        if (!borrowed && !reading) {
            h.b.vStatusAccent.setVisibility(View.GONE);
            h.b.vStatusAccent.setContentDescription(null);
            return;
        }
        android.content.Context ctx = h.itemView.getContext();
        h.b.vStatusAccent.setVisibility(View.VISIBLE);
        h.b.vStatusAccent.setContentDescription(borrowed ? "借出" : "在读");
        h.b.vStatusAccent.setBackgroundTintList(ColorStateList.valueOf(borrowed
                ? ContextCompat.getColor(ctx, R.color.badge_borrowed)
                : ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY)));
    }

    private void bindCover(VH h, Book book) {
        com.bai.bookshelf.util.CoverLoader.load(h.b.ivCover, book);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemBookGridBinding b;

        VH(ItemBookGridBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
