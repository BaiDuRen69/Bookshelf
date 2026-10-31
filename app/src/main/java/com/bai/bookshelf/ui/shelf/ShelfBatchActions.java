package com.bai.bookshelf.ui.shelf;

import android.app.Activity;
import android.content.Intent;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.util.BookCascade;
import com.bai.bookshelf.util.BookText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 书架多选批量操作（v5.7，从 ShelfFragment 抽出）：
 * 分享书单 / 批量删除 / 批量设置地点、标签、阅读状态。
 * 选择态与操作条仍由书架维护，本类只读取当前选中集合。
 */
public final class ShelfBatchActions {

    private final Activity activity;
    private final java.util.function.Supplier<Set<Long>> selectedIds;
    private final java.util.function.Supplier<List<Book>> allBooks;
    private final java.util.function.Supplier<List<Location>> locations;
    private final java.util.function.Supplier<List<Tag>> tagList;
    private final Runnable exitSelection;
    private final Consumer<String> toast;

    public ShelfBatchActions(Activity activity,
                             java.util.function.Supplier<Set<Long>> selectedIds,
                             java.util.function.Supplier<List<Book>> allBooks,
                             java.util.function.Supplier<List<Location>> locations,
                             java.util.function.Supplier<List<Tag>> tagList,
                             Runnable exitSelection,
                             Consumer<String> toast) {
        this.activity = activity;
        this.selectedIds = selectedIds;
        this.allBooks = allBooks;
        this.locations = locations;
        this.tagList = tagList;
        this.exitSelection = exitSelection;
        this.toast = toast;
    }

    /** 把多选的书分享成文字书单 */
    public void shareSelected() {
        Set<Long> ids = selectedIds.get();
        if (ids.isEmpty()) {
            return;
        }
        List<Book> chosen = new ArrayList<>();
        for (Book book : allBooks.get()) {
            if (ids.contains(book.id)) {
                chosen.add(book);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("我的书单 · 共 ").append(chosen.size()).append(" 本").append(nl).append(nl);
        int i = 1;
        for (Book book : chosen) {
            sb.append(i++).append(". 《").append(BookText.bareTitle(book.title)).append("》");
            if (book.author != null && !book.author.isEmpty()) {
                sb.append(" · ").append(book.author);
            }
            double avgRating = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            if (avgRating > 0) {
                sb.append("  ★").append(
                        com.bai.bookshelf.util.ReadingRecords.formatRating(avgRating));
            }
            sb.append(nl);
        }
        sb.append(nl).append("—— 来自「小书房」");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        activity.startActivity(Intent.createChooser(send, "分享书单"));
    }

    /** 批量删除确认 */
    public void confirmDelete() {
        int count = selectedIds.get().size();
        if (count == 0) {
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("批量删除")
                .setMessage("确定删除已选的 " + count + " 本书吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    Set<Long> ids = new HashSet<>(selectedIds.get());
                    app.io(() -> {
                        for (Long id : ids) {
                            Book book = app.db().bookDao().getById(id);
                            BookCascade.deleteBook(app, id, book == null ? null : book.coverUrl);
                        }
                        app.ui(() -> {
                            exitSelection.run();
                            toast.accept("已删除 " + count + " 本书");
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 批量设置：地点 / 标签 / 阅读状态 */
    public void showBatchEdit() {
        if (selectedIds.get().isEmpty()) {
            return;
        }
        String[] options = {"设置存放地点", "添加标签", "设置阅读状态"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("批量设置（已选 " + selectedIds.get().size() + " 本）")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showBatchLocation();
                    } else if (which == 1) {
                        showBatchTag();
                    } else {
                        showBatchRead();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchLocation() {
        List<Location> locs = locations.get();
        String[] names = new String[locs.size() + 1];
        names[0] = "未设置";
        for (int i = 0; i < locs.size(); i++) {
            names[i + 1] = locs.get(i).name;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("设置存放地点")
                .setItems(names, (d, which) -> {
                    Long locationId = which == 0 ? null : locs.get(which - 1).id;
                    applyBatch(book -> book.locationId = locationId,
                            "已更新 " + selectedIds.get().size() + " 本的存放地点");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchTag() {
        DialogInputBinding input = DialogInputBinding.inflate(
                activity.getLayoutInflater());
        input.inputLayout.setHint("要添加的标签（如：待读、儿童）");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("批量添加标签")
                .setView(input.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String tag = input.edit.getText().toString().trim();
                    if (tag.isEmpty()) {
                        return;
                    }
                    ensureTagInLibrary(tag);
                    applyBatch(book -> appendTag(book, tag),
                            "已给 " + selectedIds.get().size() + " 本加上「" + tag + "」");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchRead() {
        String[] names = {"未读", "在读", "已读", "弃读"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("设置阅读状态")
                .setItems(names, (d, which) ->
                        applyBatch(book -> com.bai.bookshelf.util.ReadingRecords.applyStatus(
                                        book, which, com.bai.bookshelf.util.DateKeys
                                                .formatKey(com.bai.bookshelf.util.DateKeys
                                                        .todayKey())),
                                "已把 " + selectedIds.get().size() + " 本设为「" + names[which] + "」"))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void ensureTagInLibrary(String tag) {
        for (Tag t : tagList.get()) {
            if (t.name.equals(tag)) {
                return;
            }
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Tag t = new Tag();
            t.name = tag;
            t.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
            app.db().tagDao().insert(t);
        });
    }

    private static void appendTag(Book book, String tag) {
        for (String t : book.tags.split(",")) {
            if (t.trim().equals(tag)) {
                return;
            }
        }
        book.tags = book.tags.trim().isEmpty() ? tag : book.tags + "," + tag;
    }

    private void applyBatch(Consumer<Book> change, String message) {
        Set<Long> ids = selectedIds.get();
        List<Book> targets = new ArrayList<>();
        for (Book book : allBooks.get()) {
            if (ids.contains(book.id)) {
                targets.add(book);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            for (Book book : targets) {
                change.accept(book);
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
            }
            app.ui(() -> {
                toast.accept(message);
                exitSelection.run();
            });
        });
    }
}
