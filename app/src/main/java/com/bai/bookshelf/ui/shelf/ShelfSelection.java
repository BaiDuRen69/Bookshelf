package com.bai.bookshelf.ui.shelf;

import android.view.HapticFeedbackConstants;
import android.view.View;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.FragmentShelfBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 书架的多选状态：长按进入、点按切换、全选/取消，并把状态同步到操作条与三个适配器。
 * 选中集合是 Fragment 实例级的，视图重建后由 {@link #bind} 重新挂到新视图上。
 */
public class ShelfSelection {

    private final BooleanSupplier shelfEmpty;

    private FragmentShelfBinding b;
    private BookAdapter adapter;
    private BookAdapter textAdapter;
    private BookGridAdapter gridAdapter;

    private boolean active = false;
    private final Set<Long> ids = new HashSet<>();

    public ShelfSelection(BooleanSupplier shelfEmpty) {
        this.shelfEmpty = shelfEmpty;
    }

    /** 视图（含三个适配器）重建后重新挂载并恢复选中状态 */
    public void bind(FragmentShelfBinding b, BookAdapter adapter,
                     BookAdapter textAdapter, BookGridAdapter gridAdapter) {
        this.b = b;
        this.adapter = adapter;
        this.textAdapter = textAdapter;
        this.gridAdapter = gridAdapter;
        refresh();
    }

    /** 视图销毁时释放引用，避免持有旧视图 */
    public void unbind() {
        b = null;
        adapter = null;
        textAdapter = null;
        gridAdapter = null;
    }

    public boolean isActive() {
        return active;
    }

    /** 当前选中的书籍 id（批量操作直接读取） */
    public Set<Long> ids() {
        return ids;
    }

    /** 长按某本书进入多选模式，并选中它 */
    public void enter(Book book) {
        if (active) {
            return; // 已在多选中，长按不重复触发
        }
        active = true;
        ids.add(book.id);
        if (b != null) {
            b.recycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            b.fabScan.hide();
        }
        refresh();
    }

    /** 多选模式下点击：切换该书的选中状态；选空了自动退出多选 */
    public void toggle(Book book) {
        if (!ids.remove(book.id)) {
            ids.add(book.id);
        }
        if (ids.isEmpty()) {
            exit();
            return;
        }
        refresh();
    }

    /** 全选当前筛选结果 */
    public void selectAll(List<Book> books) {
        for (Book book : books) {
            ids.add(book.id);
        }
        refresh();
    }

    /** 退出多选模式 */
    public void exit() {
        active = false;
        ids.clear();
        if (b != null && !shelfEmpty.getAsBoolean()) {
            b.fabScan.show();
        }
        refresh();
    }

    /** 把多选状态同步到操作条与三个适配器 */
    private void refresh() {
        if (b == null) {
            return;
        }
        b.selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        b.tvSelCount.setText("已选 " + ids.size() + " 种");
        adapter.setSelectionState(active, ids);
        textAdapter.setSelectionState(active, ids);
        gridAdapter.setSelectionState(active, ids);
    }
}
