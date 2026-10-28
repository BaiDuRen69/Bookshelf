package com.bai.bookshelf.ui.shelf;

import androidx.recyclerview.widget.DiffUtil;

import com.bai.bookshelf.data.entity.Book;

import java.util.List;
import java.util.Objects;

/**
 * 书架 DiffUtil 回调（v4.55）：只重绑真正变化的行。
 * 搜索词或地点名变化时 forceRebind=true，强制刷新高亮/地点文案。
 */
final class BookDiffCallback extends DiffUtil.Callback {

    private final List<Book> oldList;
    private final List<Book> newList;
    private final boolean forceRebind;

    BookDiffCallback(List<Book> oldList, List<Book> newList, boolean forceRebind) {
        this.oldList = oldList;
        this.newList = newList;
        this.forceRebind = forceRebind;
    }

    @Override
    public int getOldListSize() {
        return oldList.size();
    }

    @Override
    public int getNewListSize() {
        return newList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldPos, int newPos) {
        return oldList.get(oldPos).id == newList.get(newPos).id;
    }

    @Override
    public boolean areContentsTheSame(int oldPos, int newPos) {
        if (forceRebind) {
            return false;
        }
        return sameContent(oldList.get(oldPos), newList.get(newPos));
    }

    /** 列表/网格实际展示（以及会影响角标/高亮）的字段 */
    static boolean sameContent(Book a, Book b) {
        return a.updatedAt == b.updatedAt
                && a.stockStatus == b.stockStatus
                && a.readStatus == b.readStatus
                && a.rating == b.rating
                && Objects.equals(a.locationId, b.locationId)
                && Objects.equals(a.title, b.title)
                && Objects.equals(a.author, b.author)
                && Objects.equals(a.publisher, b.publisher)
                && Objects.equals(a.coverUrl, b.coverUrl)
                && Objects.equals(a.tags, b.tags);
    }
}
