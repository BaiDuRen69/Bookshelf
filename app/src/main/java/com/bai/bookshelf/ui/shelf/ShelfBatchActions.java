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
import com.bai.bookshelf.util.UndoBar;
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

    public ShelfBatchActions(Activity activity,
                             java.util.function.Supplier<Set<Long>> selectedIds,
                             java.util.function.Supplier<List<Book>> allBooks,
                             java.util.function.Supplier<List<Location>> locations,
                             java.util.function.Supplier<List<Tag>> tagList,
                             Runnable exitSelection) {
        this.activity = activity;
        this.selectedIds = selectedIds;
        this.allBooks = allBooks;
        this.locations = locations;
        this.tagList = tagList;
        this.exitSelection = exitSelection;
    }

    /** 多选分享菜单（v5.8.18）：文字书单，或把在外的书发成借阅条 */
    public void showShareMenu() {
        int count = selectedIds.get().size();
        if (count == 0) {
            return;
        }
        String[] options = {"文字书单", "借阅条（只发在外的书，逾期排前面）"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("分享已选的 " + count + " 种")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        shareSelected();
                    } else {
                        shareLendSlips();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 把选中且当前在外借的书逐本画成借阅条，一次发出去 */
    private void shareLendSlips() {
        Set<Long> ids = new HashSet<>(selectedIds.get());
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
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            int today = com.bai.bookshelf.util.BorrowReminder.todayKey();
            final List<com.bai.bookshelf.ui.detail.DetailShare.Slip> slips = new ArrayList<>();
            for (Book book : chosen) {
                for (com.bai.bookshelf.data.entity.BorrowRecord record
                        : app.db().borrowRecordDao().findOpenListByBook(book.id)) {
                    int due = com.bai.bookshelf.util.BorrowReminder.parseKey(record.dueDate);
                    int overdue = due > 0 && due < today
                            ? com.bai.bookshelf.util.BorrowReminder.daysBetween(due, today) : 0;
                    slips.add(new com.bai.bookshelf.ui.detail.DetailShare.Slip(book, record, overdue));
                }
            }
            // 逾期的排前面，越久越靠前
            slips.sort((a, b) -> b.overdueDays - a.overdueDays);
            app.ui(() -> {
                if (slips.isEmpty()) {
                    android.widget.Toast.makeText(activity, "选中的书没有在外的",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                com.bai.bookshelf.ui.detail.DetailShare.shareLendSlips(
                        activity, slips, anchorView());
            });
        });
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
        sb.append("我的书单 · 共 ").append(chosen.size()).append(" 种").append(nl).append(nl);
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
                .setMessage("确定删除已选的 " + count + " 种书吗？删除后 8 秒内可在提示条上撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    Set<Long> ids = new HashSet<>(selectedIds.get());
                    app.io(() -> {
                        // v5.8.16：整批删完留快照，8 秒内一次撤销全部恢复
                        List<BookCascade.Deleted> deleted = new ArrayList<>();
                        for (Long id : ids) {
                            deleted.add(BookCascade.deleteRecoverable(app, id));
                        }
                        app.ui(() -> {
                            exitSelection.run();
                            UndoBar.offer(anchorView(), "已删除 " + count + " 种书",
                                    () -> app.io(() -> {
                                        for (BookCascade.Deleted one : deleted) {
                                            BookCascade.restore(app, one);
                                        }
                                    }),
                                    () -> app.io(() -> {
                                        for (BookCascade.Deleted one : deleted) {
                                            BookCascade.deleteFiles(app, one.files);
                                        }
                                    }));
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Snackbar 的锚点：整个页面根视图 */
    private android.view.View anchorView() {
        return activity.findViewById(android.R.id.content);
    }

    /** 批量设置：地点 / 标签 / 阅读状态 / 借出 */
    public void showBatchEdit() {
        if (selectedIds.get().isEmpty()) {
            return;
        }
        String[] options = {"设置存放地点", "添加标签", "设置阅读状态", "借给某人"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("批量设置（已选 " + selectedIds.get().size() + " 种）")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showBatchLocation();
                    } else if (which == 1) {
                        showBatchTag();
                    } else if (which == 2) {
                        showBatchRead();
                    } else {
                        showBatchLend();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ==================== 批量借出（v5.8.18）====================

    /** 一次把 N 本登记给同一个人：每本借出 1 册，已经在外面的跳过 */
    private void showBatchLend() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.Borrower> all;
            try {
                all = app.db().borrowerDao().getAll();
            } catch (Exception e) {
                all = new ArrayList<>();
            }
            final List<com.bai.bookshelf.data.entity.Borrower> fAll = all;
            app.ui(() -> showBorrowerPicker(fAll));
        });
    }

    private void showBorrowerPicker(List<com.bai.bookshelf.data.entity.Borrower> all) {
        String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name;
        }
        names[all.size()] = "＋ 新建借阅人";
        new MaterialAlertDialogBuilder(activity)
                .setTitle("借给谁（已选 " + selectedIds.get().size() + " 种）")
                .setItems(names, (d, which) -> {
                    if (which == all.size()) {
                        showNewBorrowerThenConfirm();
                    } else {
                        confirmLend(all.get(which).name);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showNewBorrowerThenConfirm() {
        DialogInputBinding input = DialogInputBinding.inflate(activity.getLayoutInflater());
        input.inputLayout.setHint("借阅人名字");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建借阅人")
                .setView(input.getRoot())
                .setPositiveButton("下一步", (d, w) -> {
                    String name = input.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        android.widget.Toast.makeText(activity, "先填名字",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        com.bai.bookshelf.data.entity.Borrower borrower =
                                new com.bai.bookshelf.data.entity.Borrower();
                        borrower.name = name;
                        app.db().borrowerDao().insert(borrower);
                        app.ui(() -> confirmLend(name));
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 确认页：一行可点的应还日期 + 说明 */
    private void confirmLend(String borrower) {
        final String[] due = {defaultDueDate()};
        android.widget.TextView row = new android.widget.TextView(activity);
        row.setText("应还日期：" + due[0] + "（点按修改）");
        row.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                activity, R.attr.on_surface));
        row.setBackgroundResource(R.drawable.bg_search);
        int pad = Math.round(14 * activity.getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);
        row.setOnClickListener(v -> com.bai.bookshelf.ui.widget.CalendarPicker.showSingle(
                activity, "应还日期",
                com.bai.bookshelf.util.BorrowReminder.parseKey(due[0]),
                key -> {
                    due[0] = com.bai.bookshelf.util.BorrowReminder.formatKey(key);
                    row.setText("应还日期：" + due[0] + "（点按修改）");
                },
                () -> {
                    due[0] = "";
                    row.setText("应还日期：未设置（点按设置）");
                }));
        new MaterialAlertDialogBuilder(activity)
                .setTitle("借给 " + borrower)
                .setMessage("把已选的 " + selectedIds.get().size()
                        + " 种各借出 1 册；已经在外面的书会自动跳过。")
                .setView(row)
                .setPositiveButton("确认借出", (d, w) -> doBatchLend(borrower, due[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 默认应还日期：今天 + 30 天（与单本借出一致） */
    private String defaultDueDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, 30);
        return com.bai.bookshelf.util.BorrowReminder.formatKey(
                c.get(java.util.Calendar.YEAR) * 10000
                        + (c.get(java.util.Calendar.MONTH) + 1) * 100
                        + c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private void doBatchLend(String borrower, String dueDate) {
        Set<Long> ids = new HashSet<>(selectedIds.get());
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
            String today = com.bai.bookshelf.util.DateKeys
                    .formatKey(com.bai.bookshelf.util.DateKeys.todayKey());
            final List<Long> recordIds = new ArrayList<>();
            final List<Book> before = new ArrayList<>();
            int skipped = 0;
            for (Book book : targets) {
                if (!app.db().borrowRecordDao().findOpenListByBook(book.id).isEmpty()) {
                    skipped++;
                    continue;
                }
                Book snapshot = com.bai.bookshelf.util.BookSnapshot.copy(book);
                if (snapshot == null) {
                    skipped++;
                    continue;
                }
                com.bai.bookshelf.data.entity.BorrowRecord record =
                        new com.bai.bookshelf.data.entity.BorrowRecord();
                record.bookId = book.id;
                record.borrower = borrower;
                record.lendDate = today;
                record.dueDate = dueDate == null ? "" : dueDate;
                record.status = com.bai.bookshelf.data.entity.BorrowRecord.STATUS_OUT;
                record.copyIndex = 1;
                long recordId = app.db().borrowRecordDao().insert(record);
                if (recordId <= 0) {
                    continue;
                }
                recordIds.add(recordId);
                before.add(snapshot);
                book.stockStatus = Book.STOCK_LENT;
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
            }
            final int lent = recordIds.size();
            final int fSkipped = skipped;
            app.ui(() -> {
                exitSelection.run();
                if (lent == 0) {
                    android.widget.Toast.makeText(activity, "这些书都已经在外面了",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String message = "已把 " + lent + " 本借给 " + borrower
                        + (fSkipped > 0 ? "（" + fSkipped + " 本已在外面，跳过）" : "");
                UndoBar.offer(anchorView(), message, () -> app.io(() -> {
                    long revertAt = System.currentTimeMillis();
                    for (long recordId : recordIds) {
                        app.db().borrowRecordDao().deleteById(recordId);
                    }
                    for (Book old : before) {
                        old.updatedAt = revertAt;
                        old.dirty = true;
                        app.db().bookDao().update(old);
                    }
                }), null);
                com.bai.bookshelf.util.BorrowReminder.refresh(activity);
            });
        });
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
                            "已更新 " + selectedIds.get().size() + " 种的存放地点");
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
                            "已给 " + selectedIds.get().size() + " 种加上「" + tag + "」");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showBatchRead() {
        String[] names = {"未读", "在读", "已读", "弃读"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("设置阅读状态")
                .setItems(names, (d, which) ->
                        applyBatch(book -> applyReadFlow(book, which),
                                "已把 " + selectedIds.get().size() + " 种设为「" + names[which] + "」"))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * v5.8.31：批量改状态也走会话状态机，不再就地改写最后一场。
     * 「设为未读」= 逐场撤销直到清空（与旧 applyStatus(READ_NONE) 一样会清掉历史，
     * 语义没变，只是改成用 ReadingFlow 的动作表达）。
     */
    private static void applyReadFlow(Book book, int which) {
        String today = com.bai.bookshelf.util.DateKeys.formatKey(
                com.bai.bookshelf.util.DateKeys.todayKey());
        switch (which) {
            case Book.READ_NONE:
                while (com.bai.bookshelf.util.ReadingFlow.undoToNone(book)) {
                    // 一场一场撤，撤空即未读
                }
                break;
            case Book.READING:
                com.bai.bookshelf.util.ReadingFlow.start(book, today);
                break;
            case Book.READ_DONE:
                com.bai.bookshelf.util.ReadingFlow.finish(book, today);
                break;
            default:
                com.bai.bookshelf.util.ReadingFlow.drop(book, today);
                break;
        }
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
        // v5.8.16：批量改动可能落在不同字段上，直接留「改动前的整行」做撤销
        List<Book> before = new ArrayList<>();
        for (Book book : targets) {
            Book snapshot = com.bai.bookshelf.util.BookSnapshot.copy(book);
            if (snapshot != null) {
                before.add(snapshot);
            }
        }
        final boolean undoable = before.size() == targets.size();
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
                exitSelection.run();
                UndoBar.offer(anchorView(), message, undoable ? () -> app.io(() -> {
                    long revertAt = System.currentTimeMillis();
                    for (Book old : before) {
                        old.updatedAt = revertAt;
                        old.dirty = true;
                        app.db().bookDao().update(old);
                    }
                }) : null, null);
            });
        });
    }
}
