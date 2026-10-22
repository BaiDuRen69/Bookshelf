package com.bai.bookshelf.ui.shelf;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemBookGridBinding;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

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
    public void setSelectionState(boolean mode, java.util.Set<Long> ids) {
        selectionMode = mode;
        selectedIds.clear();
        if (ids != null) {
            selectedIds.addAll(ids);
        }
        notifyDataSetChanged();
    }

    /** 整体替换数据（数据量小，直接全量刷新） */
    public void submit(List<Book> books) {
        data.clear();
        data.addAll(books);
        notifyDataSetChanged();
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
        h.b.tvTitle.setText(book.title);
        h.b.tvRead.setText(StatusText.read(book.readStatus));

        // v4.19：封面高度交给 RatioImageView 按实测宽度自行换算（3:4），
        // 这里不再做任何高度估算，保证所有列的封面比例完全一致。

        // 封面：支持网络链接与本地文件路径（拍照/相册封面）
        Glide.with(h.b.ivCover)
                .load(book.coverUrl.isEmpty() ? null
                        : (book.coverUrl.startsWith("http") ? book.coverUrl : new java.io.File(book.coverUrl)))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(h.b.ivCover);

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
