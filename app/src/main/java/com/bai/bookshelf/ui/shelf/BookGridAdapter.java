package com.bai.bookshelf.ui.shelf;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemBookGridBinding;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 书架封面视图适配器：封面大图 + 左上角「纸质书 / 阅读状态」角标 + 下方书名。
 */
public class BookGridAdapter extends RecyclerView.Adapter<BookGridAdapter.VH> {

    /** 列表项点击回调 */
    public interface OnBookClick {
        /** @param cover 封面视图，用于共享元素过渡 */
        void onClick(Book book, android.view.View cover);
    }

    /** 列表项长按回调（进入多选） */
    public interface OnBookLongClick {
        void onLongClick(Book book);
    }

    private final List<Book> data = new ArrayList<>();

    /** v5.8.7：首屏前 12 项 20ms stagger 淡入（每本书只跑一次） */
    private final java.util.Set<Long> entranceAnimated = new java.util.HashSet<>();
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
    /** 每本书当前在外的人名（v5.8.33）；网格卡只有书脊色条，所以进读屏文案 */
    private Map<Long, List<String>> lenders = new HashMap<>();

    public void setLenders(Map<Long, List<String>> lenderNames) {
        lenders = lenderNames == null ? new HashMap<>() : lenderNames;
    }

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
    public void onViewAttachedToWindow(@NonNull VH holder) {
        super.onViewAttachedToWindow(holder);
        int pos = holder.getBindingAdapterPosition();
        if (pos < 0 || pos >= data.size()) {
            return;
        }
        Book book = data.get(pos);
        android.view.View itemView = holder.itemView;
        if (entranceAnimated.contains(book.id)) {
            itemView.setAlpha(1f);
            itemView.setTranslationY(0f);
            return;
        }
        entranceAnimated.add(book.id);
        if (pos >= 12) {
            return;
        }
        float dp = itemView.getResources().getDisplayMetrics().density;
        itemView.setAlpha(0f);
        itemView.setTranslationY(12 * dp);
        itemView.animate().alpha(1f).translationY(0f)
                .setStartDelay(pos * 20L)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

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
        // v5.8.14：书脊厚度按册数 / 套装（网格与列表同一套规则）
        bindSpine(h, book);
        bindStatusBadge(h, book);
        bindProgressOverlay(h, book);
        bindCover(h, book);

        // v4.19：封面高度交给 RatioImageView 按实测宽度自行换算（3:4），
        // 这里不再做任何高度估算，保证所有列的封面比例完全一致。

        // 多选模式：封面盖一层 12% 主色蒙层 + 右上勾选圈（v5.8.15）
        boolean selected = selectionMode && selectedIds.contains(book.id);
        h.b.vSelectScrim.setVisibility(selected ? View.VISIBLE : View.GONE);
        h.b.ivSelectCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
        h.b.ivSelectCheck.setContentDescription(selected ? "已选中" : null);

        h.b.ivCover.setTransitionName("cover_" + book.id);
        h.itemView.setOnClickListener(v -> onBookClick.onClick(book, h.b.ivCover));
        h.itemView.setOnLongClickListener(v -> {
            if (onBookLongClick != null) {
                onBookLongClick.onLongClick(book);
                return true;
            }
            return false;
        });
    }

    /** 状态只保留「借出 / 遗失 / 在读 / 弃读」，收成书脊底部的色条（v4.30） */
    private void bindStatusBadge(VH h, Book book) {
        boolean borrowed = book.stockStatus == Book.STOCK_LENT;
        boolean lost = book.stockStatus == Book.STOCK_LOST;
        boolean reading = book.readStatus == Book.READING;
        boolean dropped = book.readStatus == Book.READ_DROPPED;
        if (!borrowed && !lost && !reading && !dropped) {
            h.b.vStatusAccent.setVisibility(View.GONE);
            h.b.vStatusAccent.setContentDescription(null);
            return;
        }
        android.content.Context ctx = h.itemView.getContext();
        h.b.vStatusAccent.setVisibility(View.VISIBLE);
        // 网格卡没有状态文字行（v4.30 只留书脊色条），组合态写进读屏文案与长按提示
        String who = book.readStatus == Book.READING
                ? com.bai.bookshelf.util.StatusText.lentTo(lenders.get(book.id)) : null;
        String label = lost ? "遗失" : borrowed ? "借出" : dropped ? "弃读" : "在读";
        if (who != null) {
            label = label + " · " + who;
        }
        h.b.vStatusAccent.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            h.b.vStatusAccent.setTooltipText(label);
        }
        h.b.vStatusAccent.setBackgroundTintList(ColorStateList.valueOf(
                com.bai.bookshelf.util.ShelfAccent.statusColor(ctx, book)));
    }

    /** v5.8.14：书脊宽度 6/8/10dp；真实封面用半透明书脊，避免压住封面原画 */
    private void bindSpine(VH h, Book book) {
        android.view.ViewGroup.LayoutParams lp = h.b.vSpine.getLayoutParams();
        int width = Math.round(com.bai.bookshelf.util.BookSpine.widthDp(book)
                * h.itemView.getResources().getDisplayMetrics().density);
        if (lp.width != width) {
            lp.width = width;
            h.b.vSpine.setLayoutParams(lp);
        }
        h.b.vSpine.setAlpha(com.bai.bookshelf.util.BookSpine.alpha(book));
        h.b.vSpine.setVisibility(View.VISIBLE);
    }

    /** v5.8.14：底部 2dp 进度线 + 右下角百分比（0 与 100 不显示） */
    private void bindProgressOverlay(VH h, Book book) {
        int progress = book.readingProgress;
        boolean show = book.readStatus == Book.READING && progress > 0 && progress < 100;
        h.b.pbCoverProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        h.b.tvProgressPercent.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        android.content.Context ctx = h.itemView.getContext();
        h.b.pbCoverProgress.setProgress(Math.min(100, progress));
        h.b.pbCoverProgress.setProgressTintList(ColorStateList.valueOf(
                com.bai.bookshelf.util.ShelfAccent.statusColor(ctx, book)));
        h.b.tvProgressPercent.setText(progress + "%");
        h.b.tvProgressPercent.setBackgroundTintList(ColorStateList.valueOf(
                com.bai.bookshelf.util.ShelfAccent.percentBackground(ctx, book)));
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
