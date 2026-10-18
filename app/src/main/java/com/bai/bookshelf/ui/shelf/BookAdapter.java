package com.bai.bookshelf.ui.shelf;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemBookBinding;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架列表适配器：封面 + 书名/作者 + 地点/状态标签 + 评分。
 */
public class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

    /** 列表项点击回调 */
    public interface OnBookClick {
        void onClick(Book book);
    }

    /** 列表项长按回调（进入多选） */
    public interface OnBookLongClick {
        void onLongClick(Book book);
    }

    private final List<Book> data = new ArrayList<>();
    private Map<Long, String> locNames = new HashMap<>();
    private final OnBookClick onBookClick;
    private final boolean hideCover; // true=纯文字列表视图（不显示封面）
    private OnBookLongClick onBookLongClick;
    private boolean selectionMode;                 // 多选模式：高亮已选项
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    public BookAdapter(OnBookClick onBookClick) {
        this(onBookClick, false);
    }

    public BookAdapter(OnBookClick onBookClick, boolean hideCover) {
        this.onBookClick = onBookClick;
        this.hideCover = hideCover;
        setHasStableIds(true); // v3.2：配合 getItemId 让 ViewHolder 可复用
    }

    public void setOnBookLongClick(OnBookLongClick listener) {
        this.onBookLongClick = listener;
    }

    /** 更新多选状态并刷新（ids 为外部维护的选中集合，这里只读） */
    public void setSelectionState(boolean mode, java.util.Set<Long> ids) {
        selectionMode = mode;
        selectedIds.clear();
        if (ids != null) {
            selectedIds.addAll(ids);
        }
        notifyDataSetChanged();
    }

    /** 整体替换数据（数据量小，直接全量刷新） */
    public void submit(List<Book> books, Map<Long, String> locationNames) {
        data.clear();
        data.addAll(books);
        locNames = locationNames;
        notifyDataSetChanged();
    }

    /**
     * v3.2：启用稳定 ID，让 RecyclerView 的 RecycledViewPool 能正确复用 ViewHolder。
     *
     * <p>不加的话每次 notifyDataSetChanged 都被视为「全量变化」，缓存的 ViewHolder
     * 会被丢弃重建，封面随之重新走一遍 Glide 的 into()（含解码与圆角处理）。
     * 书籍 id 主键天然稳定，是启用此优化最合适的场景。
     */
    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBookBinding b = ItemBookBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Book book = data.get(position);
        h.b.tvTitle.setText(book.title);

        // 作者 · 出版社（自动跳过空字段）
        StringBuilder sub = new StringBuilder();
        if (!book.author.isEmpty()) {
            sub.append(book.author);
        }
        if (!book.publisher.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(book.publisher);
        }
        h.b.tvAuthor.setText(sub);

        // 地点与藏书状态标签
        String locName = book.locationId == null ? "未设置地点"
                : locNames.getOrDefault(book.locationId, "未设置地点");
        h.b.tvLocation.setText(locName);
        h.b.tvStock.setText(StatusText.stock(book.stockStatus) + " · " + StatusText.read(book.readStatus));

        // 评分（0 分不显示）
        h.b.tvRating.setText(book.rating > 0 ? "★ " + book.rating : "");

        // 标签独立行（ViewHolder 复用，先清空旧标签）
        h.b.tagsRow.removeAllViews();
        if (book.tags.trim().isEmpty()) {
            h.b.tagsRow.setVisibility(android.view.View.GONE);
        } else {
            h.b.tagsRow.setVisibility(android.view.View.VISIBLE);
            for (String t : book.tags.split(",")) {
                String name = t.trim();
                if (!name.isEmpty()) {
                    h.b.tagsRow.addView(buildTagView(h, name));
                }
            }
        }

        // 封面：支持网络链接与本地文件路径（拍照/相册封面）；纯文字视图直接隐藏
        if (hideCover) {
            h.b.ivCover.setVisibility(android.view.View.GONE);
        } else {
            h.b.ivCover.setVisibility(android.view.View.VISIBLE);
            Glide.with(h.b.ivCover)
                    .load(book.coverUrl.isEmpty() ? null
                            : (book.coverUrl.startsWith("http") ? book.coverUrl : new java.io.File(book.coverUrl)))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(h.b.ivCover);
        }

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

    /** 列表项里的单个小标签视图（琥珀色文字，与地点/状态标签区分） */
    private android.widget.TextView buildTagView(VH h, String name) {
        android.widget.TextView tv = new android.widget.TextView(h.itemView.getContext());
        tv.setText("# " + name);
        tv.setTextSize(11);
        tv.setTextColor(ThemeManager.resolveColor(h.itemView.getContext(), ThemeManager.ATTR_COLOR_SECONDARY));
        tv.setBackgroundResource(R.drawable.bg_chip);
        int dp4 = (int) (4 * h.itemView.getResources().getDisplayMetrics().density);
        int dp8 = (int) (8 * h.itemView.getResources().getDisplayMetrics().density);
        tv.setPadding(dp8, dp4, dp8, dp4);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp8);
        tv.setLayoutParams(lp);
        return tv;
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemBookBinding b;

        VH(ItemBookBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
